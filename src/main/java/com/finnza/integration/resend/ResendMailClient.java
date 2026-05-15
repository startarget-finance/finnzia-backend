package com.finnza.integration.resend;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Envio transacional via <a href="https://resend.com/docs/api-reference/emails/send-email">Resend API</a>
 * (HTTPS :443). Adequado para PaaS que bloqueiam saída SMTP (ex.: Render free tier).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "resend")
public class ResendMailClient {

    private final RestClient restClient;

    public ResendMailClient(@Value("${app.mail.resend.api-key:}") String apiKey) {
        String key = apiKey != null ? apiKey.trim() : "";
        this.restClient =
                RestClient.builder()
                        .baseUrl("https://api.resend.com")
                        .defaultHeader("Authorization", "Bearer " + key)
                        .build();
    }

    /**
     * @param from formato Resend: {@code Nome <email@dominio.com>} (domínio ou remetente verificado no painel)
     */
    public void send(String from, String to, String subject, String html, String text) {
        if (!StringUtils.hasText(from) || !StringUtils.hasText(to)) {
            throw new IllegalArgumentException("from e to são obrigatórios");
        }
        Map<String, Object> body =
                Map.of(
                        "from", from.trim(),
                        "to", List.of(to.trim()),
                        "subject", subject != null ? subject : "",
                        "html", html != null ? html : "",
                        "text", text != null ? text : "");
        try {
            restClient
                    .post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Resend: e-mail aceito para envio (destino mascarado)");
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(
                    "Resend HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), e);
        }
    }
}
