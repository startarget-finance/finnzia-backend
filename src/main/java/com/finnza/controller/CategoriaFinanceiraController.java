package com.finnza.controller;

import com.finnza.dto.request.CategoriaFinanceiraRequest;
import com.finnza.dto.request.RenomearCategoriaFinanceiraRequest;
import com.finnza.dto.response.CategoriaFinanceiraDTO;
import com.finnza.service.CategoriaFinanceiraService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
        } catch (Exception e) {
            return tratarErroInesperado("listar", null, idEmpresa, e);
        }
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> salvar(@Valid @RequestBody CategoriaFinanceiraRequest body) {
        try {
            return ResponseEntity.ok(service.salvar(emailAutenticado(), body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", e.getMessage()));
        } catch (Exception e) {
            return tratarErroInesperado("salvar", null, body != null ? body.getIdEmpresa() : null, e);
        }
    }

    @PatchMapping("/nos/{id}")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> renomearNo(
            @PathVariable Long id,
            @RequestParam(value = "idEmpresa", required = false) Integer idEmpresa,
            @Valid @RequestBody RenomearCategoriaFinanceiraRequest body) {
        try {
            return ResponseEntity.ok(service.renomearNo(emailAutenticado(), idEmpresa, id, body.getNome()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", e.getMessage()));
        } catch (Exception e) {
            return tratarErroInesperado("renomear", id, idEmpresa, e);
        }
    }

    /**
     * Exclui um nó (raiz ou filho) e toda a subárvore.
     */
    @DeleteMapping("/nos/{id}")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> excluirNo(
            @PathVariable Long id,
            @RequestParam(value = "idEmpresa", required = false) Integer idEmpresa
    ) {
        try {
            return ResponseEntity.ok(service.excluirNo(emailAutenticado(), idEmpresa, id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", e.getMessage()));
        } catch (Exception e) {
            return tratarErroInesperado("excluir", id, idEmpresa, e);
        }
    }

    private ResponseEntity<Map<String, String>> tratarErroInesperado(
            String operacao, Long nodeId, Integer idEmpresa, Exception e
    ) {
        String detalhe = e.getMessage() != null && !e.getMessage().isBlank()
                ? e.getMessage()
                : e.getClass().getSimpleName();
        log.error("Erro inesperado em categorias-financeiras op={} nodeId={} idEmpresa={}",
                operacao, nodeId, idEmpresa, e);
        return ResponseEntity.badRequest().body(Map.of("mensagem", "Erro em " + operacao + ": " + detalhe));
    }

    private static String emailAutenticado() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new IllegalArgumentException("Não autenticado");
        }
        return auth.getName();
    }
}
