package com.finnza.controller;

import com.finnza.dto.request.AtualizarPlanoContasPadraoRequest;
import com.finnza.dto.response.PlanoContasPadraoResponse;
import com.finnza.service.PlanoContasPadraoSistemaService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/plano-contas-padrao")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class PlanoContasPadraoAdminController {

    private final PlanoContasPadraoSistemaService service;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PlanoContasPadraoResponse> obter() {
        return ResponseEntity.ok(service.obter());
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> salvar(@Valid @RequestBody AtualizarPlanoContasPadraoRequest request) {
        try {
            return ResponseEntity.ok(service.atualizar(emailAutenticado(), request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", e.getMessage()));
        }
    }

    @PostMapping("/restaurar-embutido")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PlanoContasPadraoResponse> restaurarEmbutido() {
        return ResponseEntity.ok(service.restaurarEmbutido(emailAutenticado()));
    }

    private static String emailAutenticado() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("Usuário não autenticado");
        }
        return auth.getName();
    }
}
