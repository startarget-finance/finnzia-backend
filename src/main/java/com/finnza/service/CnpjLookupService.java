package com.finnza.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class CnpjLookupService {

    private static final long CACHE_TTL_MS = Duration.ofHours(6).toMillis();

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public Map<String, String> consultar(String cnpjRaw) {
        String cnpj = cnpjRaw == null ? "" : cnpjRaw.replaceAll("\\D", "");
        if (cnpj.length() != 14) {
            throw new IllegalArgumentException("CNPJ inválido");
        }

        CacheEntry cached = cache.get(cnpj);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiraEmMs() > now) {
            return cached.payload();
        }

        Map<String, String> payload = consultarBrasilApi(cnpj);
        if (payload == null) {
            payload = consultarReceitaWs(cnpj);
        }
        if (payload == null) {
            throw new IllegalStateException("Não foi possível consultar o CNPJ no momento");
        }

        cache.put(cnpj, new CacheEntry(payload, now + CACHE_TTL_MS));
        return payload;
    }

    private Map<String, String> consultarBrasilApi(String cnpj) {
        try {
            String response = httpGet("https://brasilapi.com.br/api/cnpj/v1/" + cnpj, 8000);
            if (response == null) {
                return null;
            }
            JsonNode node = objectMapper.readTree(response);
            String razao = trimToNull(node.path("razao_social").asText(null));
            String fantasia = trimToNull(node.path("nome_fantasia").asText(null));
            if (razao == null && fantasia == null) {
                return null;
            }
            return Map.of(
                    "cnpj", cnpj,
                    "razaoSocial", razao == null ? "" : razao,
                    "nomeFantasia", fantasia == null ? "" : fantasia
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, String> consultarReceitaWs(String cnpj) {
        try {
            String encoded = URLEncoder.encode(cnpj, StandardCharsets.UTF_8);
            String response = httpGet("https://www.receitaws.com.br/v1/cnpj/" + encoded, 10000);
            if (response == null) {
                return null;
            }
            JsonNode node = objectMapper.readTree(response);
            if ("ERROR".equalsIgnoreCase(node.path("status").asText())) {
                return null;
            }
            String razao = trimToNull(node.path("nome").asText(null));
            String fantasia = trimToNull(node.path("fantasia").asText(null));
            if (razao == null && fantasia == null) {
                return null;
            }
            return Map.of(
                    "cnpj", cnpj,
                    "razaoSocial", razao == null ? "" : razao,
                    "nomeFantasia", fantasia == null ? "" : fantasia
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private String httpGet(String url, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL endpoint = new URL(url);
            conn = (HttpURLConnection) endpoint.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        } catch (Exception ex) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private record CacheEntry(Map<String, String> payload, long expiraEmMs) {}
}
