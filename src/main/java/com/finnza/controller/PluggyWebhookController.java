package com.finnza.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finnza.service.PluggyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

/**
 * Webhooks da Pluggy (sem JWT). Configure a URL pública no dashboard Pluggy ou em {@code pluggy.webhook-url}.
 */
@Slf4j
@RestController
@RequestMapping("/api/webhooks/pluggy")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class PluggyWebhookController {

    private final PluggyService pluggyService;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<Void> receber(@RequestBody byte[] rawBody) {
        String json = rawBody == null ? "" : new String(rawBody, StandardCharsets.UTF_8);
        try {
            JsonNode root = objectMapper.readTree(json);
            String itemId = extrairItemId(root);
            String event = extrairTipoEvento(root);
            pluggyService.atualizarConexaoPorWebhook(itemId, event, json);
        } catch (Exception e) {
            log.warn("Webhook Pluggy: corpo ignorado ou inválido: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    private static String extrairTipoEvento(JsonNode root) {
        if (root.hasNonNull("event")) {
            return root.get("event").asText();
        }
        if (root.hasNonNull("type")) {
            return root.get("type").asText();
        }
        return null;
    }

    private static String extrairItemId(JsonNode root) {
        if (root.hasNonNull("itemId")) {
            return root.get("itemId").asText();
        }
        if (root.has("item") && root.get("item").isObject()) {
            JsonNode item = root.get("item");
            if (item.hasNonNull("id")) {
                return item.get("id").asText();
            }
        }
        if (root.hasNonNull("id") && root.get("id").isTextual()) {
            return root.get("id").asText();
        }
        return null;
    }
}
