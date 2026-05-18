package com.finnza.controller;

import com.finnza.dto.response.PlanoContasPadraoResponse;
import com.finnza.service.PlanoContasPadraoSistemaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Modelo global de categorias (plano de contas) usado ao preencher empresa vazia.
 */
@RestController
@RequestMapping("/api/plano-contas-padrao")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class PlanoContasPadraoController {

    private final PlanoContasPadraoSistemaService service;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<PlanoContasPadraoResponse> obter() {
        return ResponseEntity.ok(service.obter());
    }
}
