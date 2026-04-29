package com.finnza.controller;

import com.finnza.dto.request.CategoriaFinanceiraRequest;
import com.finnza.dto.response.CategoriaFinanceiraDTO;
import com.finnza.service.CategoriaFinanceiraService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/categorias-financeiras")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class CategoriaFinanceiraController {

    private final CategoriaFinanceiraService service;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> listar(@RequestParam("idEmpresa") Integer idEmpresa) {
        try {
            List<CategoriaFinanceiraDTO> lista = service.listar(emailAutenticado(), idEmpresa);
            return ResponseEntity.ok(lista);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", e.getMessage()));
        }
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> salvar(@Valid @RequestBody CategoriaFinanceiraRequest body) {
        try {
            return ResponseEntity.ok(service.salvar(emailAutenticado(), body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", e.getMessage()));
        }
    }

    @DeleteMapping("/{idCategoria}")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> excluirCategoria(
            @PathVariable String idCategoria,
            @RequestParam("idEmpresa") Integer idEmpresa
    ) {
        try {
            return ResponseEntity.ok(service.excluirCategoria(emailAutenticado(), idEmpresa, idCategoria));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", e.getMessage()));
        }
    }

    @DeleteMapping("/{idCategoria}/subcategorias/{idSubcategoria}")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> excluirSubcategoria(
            @PathVariable String idCategoria,
            @PathVariable Long idSubcategoria,
            @RequestParam("idEmpresa") Integer idEmpresa
    ) {
        try {
            return ResponseEntity.ok(service.excluirSubcategoria(emailAutenticado(), idEmpresa, idCategoria, idSubcategoria));
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
