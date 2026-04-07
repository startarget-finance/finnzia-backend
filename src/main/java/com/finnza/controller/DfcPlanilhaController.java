package com.finnza.controller;

import com.finnza.dto.dfc.DfcPlanilhaPayloadDTO;
import com.finnza.dto.dfc.DfcPlanilhaResponseDTO;
import com.finnza.service.DfcPlanilhaService;
import com.finnza.service.UsuarioEmpresaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Planilha manual de DFC persistida por empresa (header {@code X-Empresa-Id}).
 */
@Slf4j
@RestController
@RequestMapping("/api/dfc/planilha")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class DfcPlanilhaController {

    private final DfcPlanilhaService dfcPlanilhaService;
    private final UsuarioEmpresaService usuarioEmpresaService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<?> get(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId) {
        Integer idEmpresa = parseEmpresa(headerEmpresaId);
        if (idEmpresa == null) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", "Informe o header X-Empresa-Id"));
        }
        if (!validarAcesso(idEmpresa)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("mensagem", "Sem acesso a esta empresa"));
        }
        return dfcPlanilhaService.buscar(idEmpresa)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(DfcPlanilhaResponseDTO.builder()
                        .id(null)
                        .idEmpresa(idEmpresa)
                        .months(java.util.Collections.emptyList())
                        .rows(java.util.Collections.emptyList())
                        .build()));
    }

    @PutMapping
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<?> put(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @Valid @RequestBody DfcPlanilhaPayloadDTO body) {
        Integer idEmpresa = parseEmpresa(headerEmpresaId);
        if (idEmpresa == null) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", "Informe o header X-Empresa-Id"));
        }
        if (!validarAcesso(idEmpresa)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("mensagem", "Sem acesso a esta empresa"));
        }
        try {
            DfcPlanilhaResponseDTO salvo = dfcPlanilhaService.salvarOuAtualizar(idEmpresa, body);
            return ResponseEntity.ok(salvo);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", e.getMessage()));
        }
    }

    private Integer parseEmpresa(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            int v = Integer.parseInt(header.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean validarAcesso(Integer idEmpresa) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return usuarioEmpresaService.validarAcessoUsuarioEmpresa(auth.getName(), idEmpresa);
    }
}
