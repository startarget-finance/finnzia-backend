package com.finnza.controller;

import com.finnza.service.CatalogoInstituicaoFinanceiraService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/catalogo/instituicoes-financeiras")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class CatalogoInstituicaoFinanceiraController {

    private final CatalogoInstituicaoFinanceiraService service;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<?> listar(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        var itens = service.listar(q, limit);
        return ResponseEntity.ok(Map.of(
                "itens", itens,
                "total", itens.size()
        ));
    }
}
