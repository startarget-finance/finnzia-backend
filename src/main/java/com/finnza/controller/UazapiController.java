package com.finnza.controller;

import com.finnza.service.UazapiService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoints para teste e uso do envio WhatsApp via uazapi.
 * Requer autenticação (JWT).
 */
@RestController
@RequestMapping("/api/uazapi")
@RequiredArgsConstructor
public class UazapiController {

    private final UazapiService uazapiService;

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("enabled", uazapiService.isEnabled()));
    }

    /**
     * Envia uma mensagem de teste para um número.
     * POST /api/uazapi/send
     * Body: { "phone": "5511999999999", "text": "Olá, mensagem de teste do Finnzia" }
     */
    @PostMapping("/send")
    public ResponseEntity<?> send(@RequestBody SendRequest request) {
        if (request == null || request.getPhone() == null || request.getText() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "phone e text são obrigatórios"));
        }
        boolean ok = uazapiService.sendText(request.getPhone(), request.getText());
        return ok
                ? ResponseEntity.ok(Map.of("sent", true, "message", "Mensagem enviada"))
                : ResponseEntity.status(500).body(Map.of("sent", false, "message", "Falha ao enviar ou uazapi desabilitado"));
    }

    @Data
    public static class SendRequest {
        private String phone;
        private String text;
    }
}
