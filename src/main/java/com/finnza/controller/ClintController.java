package com.finnza.controller;

import com.finnza.service.ClintService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller para integração com Clint via webhook
 * Endpoint público para receber dados do formulário da landing page
 */
@Slf4j
@RestController
@RequestMapping("/api/clint")
@CrossOrigin(origins = "*")
public class ClintController {

    @Autowired
    private ClintService clintService;

    /**
     * Endpoint para criar contato na Clint via webhook
     * Recebe dados do formulário da landing page e faz proxy para o webhook da Clint
     */
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> criarContato(@RequestBody Map<String, Object> contactData) {
        try {
            log.info("Recebendo dados do formulário para enviar à Clint: {}", contactData);
            
            Map<String, Object> response = clintService.enviarParaWebhook(contactData);
            
            log.info("Dados enviados com sucesso para a Clint");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Erro ao enviar dados para a Clint", e);
            return ResponseEntity.status(500).body(Map.of(
                "erro", true,
                "mensagem", "Erro ao enviar dados para a Clint: " + e.getMessage()
            ));
        }
    }
}
