package com.finnza.controller;

import com.finnza.domain.entity.Cliente;
import com.finnza.dto.request.ClienteCadastroRequest;
import com.finnza.dto.response.ClienteCadastroDTO;
import com.finnza.service.ClienteCadastroService;
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

/**
 * Cadastro interno Finnzia — clientes (parametrização), sem integração externa.
 */
@RestController
@RequestMapping("/api/cadastro/clientes")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ClienteCadastroController {

    private final ClienteCadastroService service;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<?> listar(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "idEmpresa", required = false) Integer idEmpresa,
            @RequestParam(value = "classificacao", required = false) Integer classificacao,
            @RequestParam(value = "tipoPessoa", required = false) Cliente.TipoPessoa tipoPessoa,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "sort", defaultValue = "razaoSocial,asc") String sort
    ) {
        try {
            Pageable pageable = parsePageable(page, size, sort);
            Page<ClienteCadastroDTO> result = service.listar(
                    emailAutenticado(), q, idEmpresa, classificacao, tipoPessoa, pageable);
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
    public ResponseEntity<?> criar(@Valid @RequestBody ClienteCadastroRequest body) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(service.criar(emailAutenticado(), body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<?> atualizar(@PathVariable Long id, @Valid @RequestBody ClienteCadastroRequest body) {
        try {
            return ResponseEntity.ok(service.atualizar(emailAutenticado(), id, body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", e.getMessage()));
        }
    }

    @PatchMapping("/{id}/bloqueado")
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<?> patchBloqueado(@PathVariable Long id, @RequestBody BloqueadoBody body) {
        try {
            if (body == null || body.getBloqueado() == null) {
                return ResponseEntity.badRequest().body(Map.of("mensagem", "Informe bloqueado (true/false)"));
            }
            return ResponseEntity.ok(service.alterarBloqueio(emailAutenticado(), id, body.getBloqueado()));
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
        Sort srt = Sort.by(Sort.Direction.ASC, "razaoSocial");
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
        return "razaoSocial".equals(prop)
                || "nomeFantasia".equals(prop)
                || "cpfCnpj".equals(prop)
                || "classificacao".equals(prop)
                || "dataCriacao".equals(prop)
                || "tipoPessoa".equals(prop);
    }

    private static String emailAutenticado() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new IllegalArgumentException("Não autenticado");
        }
        return auth.getName();
    }

    @Data
    public static class BloqueadoBody {
        private Boolean bloqueado;
    }
}
