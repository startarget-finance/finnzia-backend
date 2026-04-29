package com.finnza.controller;

import com.finnza.domain.entity.Cliente;
import com.finnza.dto.request.FornecedorCadastroRequest;
import com.finnza.dto.response.FornecedorCadastroDTO;
import com.finnza.service.FornecedorCadastroService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/cadastro/fornecedores")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class FornecedorCadastroController {

    private final FornecedorCadastroService service;
    private final ObjectMapper objectMapper;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<?> listar(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "idEmpresa", required = false) Integer idEmpresa,
            @RequestParam(value = "tipoPessoa", required = false) Cliente.TipoPessoa tipoPessoa,
            @RequestParam(value = "ativo", required = false) Boolean ativo,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "sort", defaultValue = "razaoSocial,asc") String sort
    ) {
        try {
            Pageable pageable = parsePageable(page, size, sort);
            Page<FornecedorCadastroDTO> result =
                    service.listar(emailAutenticado(), q, idEmpresa, tipoPessoa, ativo, pageable);
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
    public ResponseEntity<?> criar(@Valid @RequestBody FornecedorCadastroRequest body) {
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
            @Valid @RequestBody FornecedorCadastroRequest body
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

    @GetMapping("/consultar-cnpj/{cnpj}")
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<?> consultarCnpj(@PathVariable String cnpj) {
        String digits = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        if (digits.length() != 14) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", "CNPJ inválido"));
        }

        try {
            Map<String, String> brasilApi = consultarBrasilApi(digits);
            if (brasilApi != null) {
                return ResponseEntity.ok(brasilApi);
            }
            Map<String, String> receitaWs = consultarReceitaWs(digits);
            if (receitaWs != null) {
                return ResponseEntity.ok(receitaWs);
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("mensagem", "Não foi possível consultar o CNPJ no momento"));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("mensagem", "Não foi possível consultar o CNPJ no momento"));
        }
    }

    private Map<String, String> consultarBrasilApi(String cnpj) {
        try {
            String response = httpGet("https://brasilapi.com.br/api/cnpj/v1/" + cnpj, 8000);
            if (response == null) {
                return null;
            }
            JsonNode node = objectMapper.readTree(response);
            String razao = trimToNull(node.path("razao_social").asText(null));
            String fantasia = trimToNull(node.path("nome_fantasia").asText(null));
            if (razao == null && fantasia == null) {
                return null;
            }
            return Map.of(
                    "cnpj", cnpj,
                    "razaoSocial", razao == null ? "" : razao,
                    "nomeFantasia", fantasia == null ? "" : fantasia
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, String> consultarReceitaWs(String cnpj) {
        try {
            String encoded = URLEncoder.encode(cnpj, StandardCharsets.UTF_8);
            String response = httpGet("https://www.receitaws.com.br/v1/cnpj/" + encoded, 10000);
            if (response == null) {
                return null;
            }
            JsonNode node = objectMapper.readTree(response);
            if ("ERROR".equalsIgnoreCase(node.path("status").asText())) {
                return null;
            }
            String razao = trimToNull(node.path("nome").asText(null));
            String fantasia = trimToNull(node.path("fantasia").asText(null));
            if (razao == null && fantasia == null) {
                return null;
            }
            return Map.of(
                    "cnpj", cnpj,
                    "razaoSocial", razao == null ? "" : razao,
                    "nomeFantasia", fantasia == null ? "" : fantasia
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private String httpGet(String url, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL endpoint = new URL(url);
            conn = (HttpURLConnection) endpoint.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        } catch (Exception ex) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
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
                || "email".equals(prop)
                || "dataCriacao".equals(prop)
                || "ativo".equals(prop)
                || "tipoPessoa".equals(prop);
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
