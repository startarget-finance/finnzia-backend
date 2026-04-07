package com.finnza.controller;

import com.finnza.dto.request.FuncionarioCadastroRequest;
import com.finnza.dto.response.FuncionarioCadastroDTO;
import com.finnza.service.FuncionarioCadastroService;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/cadastro/funcionarios")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class FuncionarioCadastroController {

    private final FuncionarioCadastroService service;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<?> listar(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "idEmpresa", required = false) Integer idEmpresa,
            @RequestParam(value = "ativo", required = false) Boolean ativo,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "sort", defaultValue = "nomeCompleto,asc") String sort
    ) {
        try {
            Pageable pageable = parsePageable(page, size, sort);
            Page<FuncionarioCadastroDTO> result =
                    service.listar(emailAutenticado(), q, idEmpresa, ativo, pageable);
            return ResponseEntity.ok(result);
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
    public ResponseEntity<?> criar(@Valid @RequestBody FuncionarioCadastroRequest body) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(service.criar(emailAutenticado(), body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<?> atualizar(
            @PathVariable Long id,
            @Valid @RequestBody FuncionarioCadastroRequest body
    ) {
        try {
            return ResponseEntity.ok(service.atualizar(emailAutenticado(), id, body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", e.getMessage()));
        }
    }

    @PatchMapping("/{id}/ativo")
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<?> patchAtivo(@PathVariable Long id, @RequestBody AtivoBody body) {
        try {
            if (body == null || body.getAtivo() == null) {
                return ResponseEntity.badRequest().body(Map.of("mensagem", "Informe ativo (true/false)"));
            }
            return ResponseEntity.ok(service.alterarAtivo(emailAutenticado(), id, body.getAtivo()));
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

    private static Pageable parsePageable(int page, int size, String sort) {
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        Sort srt = Sort.by(Sort.Direction.ASC, "nomeCompleto");
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            if (parts.length >= 1) {
                String prop = parts[0].trim();
                if (!prop.isEmpty() && isAllowedSortProperty(prop)) {
                    Sort.Direction dir = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())
                            ? Sort.Direction.DESC
                            : Sort.Direction.ASC;
                    srt = Sort.by(dir, prop);
                }
            }
        }
        return PageRequest.of(p, s, srt);
    }

    private static boolean isAllowedSortProperty(String prop) {
        return "nomeCompleto".equals(prop)
                || "cpf".equals(prop)
                || "cargo".equals(prop)
                || "departamento".equals(prop)
                || "email".equals(prop)
                || "dataCriacao".equals(prop)
                || "ativo".equals(prop);
    }

    @Data
    public static class AtivoBody {
        private Boolean ativo;
    }

    private static String emailAutenticado() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new IllegalArgumentException("Nao autenticado");
        }
        return auth.getName();
    }
}
