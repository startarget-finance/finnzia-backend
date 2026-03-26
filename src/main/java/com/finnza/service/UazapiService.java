package com.finnza.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

/**
 * Serviço para envio de mensagens WhatsApp via uazapi (free.uazapi.com ou instância própria).
 * Configure UAZAPI_BASE_URL e UAZAPI_INSTANCE_TOKEN nas variáveis de ambiente (ou no IntelliJ).
 */
@Slf4j
@Service
public class UazapiService {

    private final WebClient webClient;
    private final boolean enabled;

    public UazapiService(
            @Value("${UAZAPI_BASE_URL:}") String baseUrl,
            @Value("${UAZAPI_INSTANCE_TOKEN:}") String instanceToken) {
        this.enabled = baseUrl != null && !baseUrl.isBlank() && instanceToken != null && !instanceToken.isBlank();

        if (enabled) {
            String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            this.webClient = WebClient.builder()
                    .baseUrl(url)
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + instanceToken)
                    .build();
            log.info("UazapiService ativo - baseUrl: {}", url);
        } else {
            this.webClient = null;
            log.info("UazapiService inativo - defina UAZAPI_BASE_URL e UAZAPI_INSTANCE_TOKEN para habilitar");
        }
    }

    /**
     * Envia uma mensagem de texto para um número WhatsApp.
     *
     * @param phone  Número com DDI, sem + (ex: 5511999999999)
     * @param text   Texto da mensagem
     * @return true se enviou com sucesso, false se serviço desabilitado ou erro
     */
    public boolean sendText(String phone, String text) {
        if (!enabled) {
            log.warn("UazapiService desabilitado - mensagem não enviada para {}", phone);
            return false;
        }
        if (phone == null || phone.isBlank() || text == null || text.isBlank()) {
            log.warn("UazapiService: phone ou text vazio");
            return false;
        }
        String number = phone.replaceAll("[^0-9]", "");
        if (number.length() < 10) {
            log.warn("UazapiService: número inválido {}", phone);
            return false;
        }

        try {
            Map<String, Object> body = Map.of(
                    "phone", number,
                    "text", text
            );
            String response = webClient.post()
                    .uri("/send-text")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.info("UazapiService: mensagem enviada para {} - resposta: {}", number, response);
            return true;
        } catch (WebClientResponseException e) {
            log.error("UazapiService: erro ao enviar para {} - status {} body {}", number, e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.error("UazapiService: erro ao enviar para {}", number, e);
            return false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
