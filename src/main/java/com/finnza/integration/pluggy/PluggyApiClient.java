package com.finnza.integration.pluggy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finnza.config.PluggyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cliente HTTP para Pluggy: {@code /auth}, {@code /connect_token}, {@code /accounts}, {@code /transactions}.
 */
@Slf4j
@Component
public class PluggyApiClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    private final WebClient pluggyWebClient;
    private final PluggyProperties properties;
    private final ObjectMapper objectMapper;

    private final AtomicReference<CachedApiKey> apiKeyCache = new AtomicReference<>();

    public PluggyApiClient(
            @Qualifier("pluggyWebClient") WebClient pluggyWebClient,
            PluggyProperties properties,
            ObjectMapper objectMapper) {
        this.pluggyWebClient = pluggyWebClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * API key Pluggy (via {@code POST /auth}), reutilizada por ~90 min para reduzir chamadas.
     */
    public String getOrCreateApiKey() {
        CachedApiKey cur = apiKeyCache.get();
        Instant now = Instant.now();
        if (cur != null && now.isBefore(cur.expiresAt())) {
            return cur.apiKey();
        }
        String key = createApiKey();
        apiKeyCache.set(new CachedApiKey(key, now.plus(90, ChronoUnit.MINUTES)));
        return key;
    }

    public void invalidateApiKeyCache() {
        apiKeyCache.set(null);
    }

    public String createApiKey() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("clientId", properties.getClientId().trim());
        body.put("clientSecret", properties.getClientSecret().trim());
        IllegalStateException last = null;
        for (int tentativa = 0; tentativa < 2; tentativa++) {
            try {
                JsonNode res = pluggyWebClient
                        .post()
                        .uri("/auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block(TIMEOUT);
                if (res == null || !res.hasNonNull("apiKey")) {
                    throw new IllegalStateException("Resposta Pluggy /auth sem apiKey");
                }
                return res.get("apiKey").asText();
            } catch (WebClientResponseException e) {
                log.warn("Pluggy /auth falhou: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
                last = new IllegalStateException("Falha na autenticação Pluggy: " + e.getStatusCode(), e);
                if (tentativa == 0 && isTransientPluggyFailure(e)) {
                    sleepQuietly(400);
                    continue;
                }
                throw last;
            } catch (WebClientRequestException e) {
                log.warn("Pluggy /auth rede/timeout: {}", e.getMessage());
                last = new IllegalStateException("Falha de rede na autenticação Pluggy", e);
                if (tentativa == 0) {
                    sleepQuietly(400);
                    continue;
                }
                throw last;
            }
        }
        throw last != null ? last : new IllegalStateException("Falha na autenticação Pluggy");
    }

    public String createConnectToken(String apiKey, String clientUserId, String itemIdToUpdate) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("clientUserId", clientUserId);
        options.put("avoidDuplicates", Boolean.TRUE);
        if (properties.getWebhookUrl() != null && !properties.getWebhookUrl().isBlank()) {
            options.put("webhookUrl", properties.getWebhookUrl().trim());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        if (itemIdToUpdate != null && !itemIdToUpdate.isBlank()) {
            body.put("itemId", itemIdToUpdate.trim());
        }
        body.put("options", options);

        IllegalStateException last = null;
        for (int tentativa = 0; tentativa < 2; tentativa++) {
            try {
                JsonNode res = pluggyWebClient
                        .post()
                        .uri("/connect_token")
                        .header("X-API-KEY", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block(TIMEOUT);
                if (res == null || !res.hasNonNull("accessToken")) {
                    throw new IllegalStateException("Resposta Pluggy /connect_token sem accessToken");
                }
                return res.get("accessToken").asText();
            } catch (WebClientResponseException e) {
                log.warn("Pluggy /connect_token falhou: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
                last = new IllegalStateException("Falha ao criar connect token Pluggy: " + e.getStatusCode(), e);
                if (tentativa == 0 && isTransientPluggyFailure(e)) {
                    sleepQuietly(500);
                    continue;
                }
                throw last;
            } catch (WebClientRequestException e) {
                log.warn("Pluggy /connect_token rede/timeout: {}", e.getMessage());
                last = new IllegalStateException("Falha de rede ao criar connect token Pluggy", e);
                if (tentativa == 0) {
                    sleepQuietly(500);
                    continue;
                }
                throw last;
            }
        }
        throw last != null ? last : new IllegalStateException("Falha ao criar connect token Pluggy");
    }

    private static boolean isTransientPluggyFailure(WebClientResponseException e) {
        int code = e.getStatusCode().value();
        return code >= 500 || code == 408;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** {@code GET /accounts?itemId=…} */
    public JsonNode listAccounts(String apiKey, String itemId) {
        String uri = UriComponentsBuilder.fromPath("/accounts")
                .queryParam("itemId", itemId.trim())
                .build(true)
                .toUriString();
        return getJson(apiKey, uri, "accounts");
    }

    /**
     * {@code GET /transactions?accountId=…&from&to&page&pageSize}
     *
     * @param from inclusive yyyy-MM-dd (opcional)
     * @param to   inclusive yyyy-MM-dd (opcional)
     */
    public JsonNode listTransactions(
            String apiKey,
            String accountId,
            String from,
            String to,
            int page,
            int pageSize
    ) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath("/transactions")
                .queryParam("accountId", accountId.trim())
                .queryParam("page", page)
                .queryParam("pageSize", Math.min(500, Math.max(1, pageSize)));
        if (from != null && !from.isBlank()) {
            b.queryParam("from", from.trim());
        }
        if (to != null && !to.isBlank()) {
            b.queryParam("to", to.trim());
        }
        return getJson(apiKey, b.build(true).toUriString(), "transactions");
    }

    private JsonNode getJson(String apiKey, String uri, String label) {
        try {
            JsonNode res = pluggyWebClient
                    .get()
                    .uri(uri)
                    .header("X-API-KEY", apiKey)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(TIMEOUT);
            if (res == null) {
                throw new IllegalStateException("Resposta Pluggy " + label + " vazia");
            }
            return res;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                invalidateApiKeyCache();
            }
            log.warn("Pluggy GET {} falhou: {} {}", label, e.getStatusCode(), e.getResponseBodyAsString());
            throw new IllegalStateException("Falha Pluggy " + label + ": " + e.getStatusCode(), e);
        }
    }

    private record CachedApiKey(String apiKey, Instant expiresAt) {}
}
