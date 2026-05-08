package com.finnza.controller;

import com.finnza.service.CnpjLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/cadastro/cnpj")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class CnpjLookupController {

    private final CnpjLookupService cnpjLookupService;

    @GetMapping("/{cnpj}")
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<?> consultar(@PathVariable String cnpj) {
        try {
            return ResponseEntity.ok(cnpjLookupService.consultar(cnpj));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("mensagem", "Não foi possível consultar o CNPJ no momento"));
        }
    }
}
