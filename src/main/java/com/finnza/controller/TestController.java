package com.finnza.controller;

import com.finnza.service.AsaasService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller temporário para testes de autenticação
 */
@RestController
@RequestMapping("/api/test")
@CrossOrigin(origins = "*")
public class TestController {

    @Autowired
    private AsaasService asaasService;

    @GetMapping("/auth")
    public ResponseEntity<Map<String, Object>> testAuth() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        Map<String, Object> response = new HashMap<>();
        response.put("authenticated", auth != null && auth.isAuthenticated() && !auth.getName().equals("anonymousUser"));
        response.put("username", auth != null ? auth.getName() : "null");
        response.put("authorities", auth != null ? auth.getAuthorities().toString() : "null");
        response.put("principal", auth != null ? auth.getPrincipal().getClass().getSimpleName() : "null");
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/asaas")
    public ResponseEntity<Map<String, Object>> testAsaas() {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("mockEnabled", asaasService.isMockEnabled());
            response.put("status", asaasService.isMockEnabled() ? "MOCK MODE" : "REAL API MODE");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("class", e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(error);
        }
    }
}

