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
 * Service para integração com webhook da Clint
 */
@Slf4j
@Service
public class ClintService {

    private final WebClient webClient;
    private final String webhookUrl;

    public ClintService(
            @Value("${clint.webhook.url:}") String webhookUrl) {
        this.webhookUrl = webhookUrl;

        this.webClient = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

        if (webhookUrl == null || webhookUrl.isEmpty()) {
            log.warn("ClintService iniciado sem URL do webhook configurada");
        } else {
            log.info("ClintService iniciado - Webhook URL: {}", webhookUrl);
        }
    }

    /**
     * Envia dados do contato para o webhook da Clint
     */
    public Map<String, Object> enviarParaWebhook(Map<String, Object> contactData) {
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            throw new RuntimeException("URL do webhook da Clint não configurada");
        }

        try {
            log.info("Enviando dados para webhook da Clint: {}", webhookUrl);
            log.debug("Dados do contato: {}", contactData);

            Map<String, Object> response = webClient.post()
                    .uri(webhookUrl)
                    .bodyValue(contactData)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            log.info("Resposta do webhook da Clint: {}", response);
            return response != null ? response : Map.of("sucesso", true);
        } catch (WebClientResponseException e) {
            log.error("Erro HTTP ao enviar para webhook da Clint: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Erro ao enviar dados para a Clint: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Erro ao enviar dados para webhook da Clint", e);
            throw new RuntimeException("Erro ao enviar dados para a Clint: " + e.getMessage(), e);
        }
    }
}
