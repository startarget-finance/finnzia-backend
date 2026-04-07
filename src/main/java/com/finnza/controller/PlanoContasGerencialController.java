package com.finnza.controller;

import com.finnza.dto.request.PlanoContasGerencialRequest;
import com.finnza.dto.response.PlanoContasGerencialDTO;
import com.finnza.service.PlanoContasGerencialService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Cadastro interno Finnzia — planos de contas gerenciais (sem integração Bom Controle).
 */
@RestController
@RequestMapping("/api/planos-contas-gerenciais")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class PlanoContasGerencialController {

    private final PlanoContasGerencialService service;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<?> listar(@RequestParam(value = "idEmpresa", required = false) Integer idEmpresa) {
        try {
            String email = emailAutenticado();
            List<PlanoContasGerencialDTO> lista = service.listar(email, idEmpresa);
            return ResponseEntity.ok(lista);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<?> buscar(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.buscar(emailAutenticado(), id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("mensagem", e.getMessage()));
        }
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<?> criar(@Valid @RequestBody PlanoContasGerencialRequest body) {
        try {
            PlanoContasGerencialDTO dto = service.criar(emailAutenticado(), body);
            return ResponseEntity.status(HttpStatus.CREATED).body(dto);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<?> atualizar(@PathVariable Long id, @Valid @RequestBody PlanoContasGerencialRequest body) {
        try {
            return ResponseEntity.ok(service.atualizar(emailAutenticado(), id, body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<?> excluir(@PathVariable Long id) {
        try {
            service.excluir(emailAutenticado(), id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", e.getMessage()));
        }
    }

    @PostMapping("/{id}/padrao")
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<?> marcarPadrao(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.marcarComoPadrao(emailAutenticado(), id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", e.getMessage()));
        }
    }

    private static String emailAutenticado() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new IllegalArgumentException("Não autenticado");
        }
        return auth.getName();
    }
}
