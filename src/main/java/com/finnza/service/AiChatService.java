package com.finnza.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finnza.dto.response.ChatAiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AiChatService {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${ai.openai.api.key:}")
    private String openaiApiKey;

    @Value("${ai.openai.api.url:https://api.openai.com/v1/chat/completions}")
    private String openaiApiUrl;

    @Value("${ai.openai.model:gpt-4o-mini}")
    private String model;

    @Value("${ai.openai.max-tokens:1000}")
    private int maxTokens;

    @Value("${ai.openai.temperature:0.7}")
    private double temperature;

    @Value("${ai.system.prompt:Você é um assistente financeiro especializado em análise de contratos, gestão financeira e ERP. Responda de forma clara e profissional.}")
    private String systemPrompt;

    public AiChatService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    public ChatAiResponse enviarChat(String message) {
        if (message == null || message.isBlank()) {
            return new ChatAiResponse("Mensagem vazia.", Instant.now());
        }

        // Sem chave configurada => fallback seguro
        if (openaiApiKey == null || openaiApiKey.isBlank() ||
                openaiApiKey.equalsIgnoreCase("sk-your-openai-api-key-here")) {
            log.warn("AiChatService: OPENAI api key não configurada (ai.openai.api.key).");
            return new ChatAiResponse(
                    "Chat IA não configurado no servidor. Configure a variável OPENAI_API_KEY para habilitar respostas reais.",
                    Instant.now()
            );
        }

        try {
            String payload = montarPayload(message);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(openaiApiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + openaiApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("AiChatService: OpenAI retornou status {} body={}", response.statusCode(), response.body());
                return new ChatAiResponse("Erro ao processar sua solicitação na IA (OpenAI).", Instant.now());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content == null || content.isBlank()) {
                content = "A IA não retornou uma resposta válida.";
            }

            return new ChatAiResponse(content, Instant.now());
        } catch (IOException e) {
            log.error("AiChatService: Erro ao parsear resposta da IA", e);
            return new ChatAiResponse("Erro ao processar sua solicitação na IA.", Instant.now());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("AiChatService: Requisição IA interrompida", e);
            return new ChatAiResponse("Requisição para IA interrompida.", Instant.now());
        } catch (Exception e) {
            log.error("AiChatService: Erro inesperado", e);
            return new ChatAiResponse("Erro inesperado ao processar sua solicitação na IA.", Instant.now());
        }
    }

    private String montarPayload(String message) throws IOException {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("max_tokens", maxTokens);
        payload.put("temperature", temperature);

        List<Map<String, String>> msgs = List.of(
                Map.of(
                        "role", "system",
                        "content", systemPrompt
                ),
                Map.of(
                        "role", "user",
                        "content", message
                )
        );

        payload.put("messages", msgs);
        return objectMapper.writeValueAsString(payload);
    }
}

