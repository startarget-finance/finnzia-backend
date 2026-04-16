package com.finnza.controller;

import com.finnza.dto.response.DfcResponseDTO;
import com.finnza.dto.response.ResumoFinanceiroDTO;
import com.finnza.service.ErpFinanceiroService;
import com.finnza.service.OfxImportService;
import com.finnza.service.UsuarioEmpresaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.Map;

/**
 * Endpoints financeiros do ERP (substitui integração Bom Controle).
 *
 * Requer header X-Empresa-Id para usuários não-admin.
 */
@Slf4j
@RestController
@RequestMapping("/api/erp")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ErpFinanceiroController {

    private final ErpFinanceiroService erpFinanceiroService;
    private final UsuarioEmpresaService usuarioEmpresaService;
    private final OfxImportService ofxImportService;

    private Integer extrairEmpresaDoHeader(String headerEmpresaId) {
        if (headerEmpresaId != null && !headerEmpresaId.isBlank()) {
            try {
                Integer empresaId = Integer.parseInt(headerEmpresaId.trim());
                return empresaId > 0 ? empresaId : null;
            } catch (NumberFormatException e) {
                log.warn("⚠️ X-Empresa-Id inválido: {}", headerEmpresaId);
            }
        }
        return null;
    }

    private boolean validarAcessoEmpresa(Integer empresaId) {
        if (empresaId == null || empresaId <= 0) {
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        String email = auth.getName();
        try {
            return usuarioEmpresaService.validarAcessoUsuarioEmpresa(email, empresaId);
        } catch (Exception e) {
            log.error("❌ Erro ao validar acesso à empresa {} para usuário {}:", empresaId, email, e);
            return false;
        }
    }

    @GetMapping("/movimentacoes")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> buscarMovimentacoes(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataTermino,
            @RequestParam(required = false) String tipoData,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String statusPagamento,
            @RequestParam(required = false, defaultValue = "data") String orderBy,
            @RequestParam(required = false, defaultValue = "asc") String orderDirection,
            @RequestParam(required = false, defaultValue = "50") Integer itensPorPagina,
            @RequestParam(required = false, defaultValue = "1") Integer numeroDaPagina
    ) {
        Integer idEmpresa = extrairEmpresaDoHeader(headerEmpresaId);

        if (idEmpresa == null) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) {
                String email = auth.getName();
                if (!usuarioEmpresaService.isAdmin(email)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "erro", true,
                            "mensagem", "X-Empresa-Id header é obrigatório para esta requisição"
                    ));
                }
            }
        } else if (!validarAcessoEmpresa(idEmpresa)) {
            return ResponseEntity.status(403).body(Map.of(
                    "erro", true,
                    "mensagem", "Você não tem permissão de acessar esta empresa"
            ));
        }

        LocalDate inicio = dataInicio;
        LocalDate fim = dataTermino;
        if (inicio == null || fim == null) {
            LocalDate hoje = LocalDate.now();
            if (inicio == null) inicio = hoje.withDayOfMonth(1);
            if (fim == null) fim = hoje.withDayOfMonth(hoje.lengthOfMonth());
        }

        Boolean debitoFiltro = null;
        if ("despesa".equalsIgnoreCase(tipo)) debitoFiltro = true;
        if ("receita".equalsIgnoreCase(tipo)) debitoFiltro = false;

        return ResponseEntity.ok(
                erpFinanceiroService.buscarMovimentacoes(
                        inicio,
                        fim,
                        tipoData,
                        idEmpresa,
                        debitoFiltro,
                        statusPagamento,
                        orderBy,
                        orderDirection,
                        itensPorPagina,
                        numeroDaPagina
                )
        );
    }

    @GetMapping("/resumo-financeiro")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> obterResumoFinanceiro(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataTermino
    ) {
        Integer idEmpresa = extrairEmpresaDoHeader(headerEmpresaId);
        if (idEmpresa == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "X-Empresa-Id header é obrigatório para esta requisição"
            ));
        }
        if (!validarAcessoEmpresa(idEmpresa)) {
            return ResponseEntity.status(403).body(Map.of(
                    "erro", true,
                    "mensagem", "Você não tem permissão de acessar esta empresa"
            ));
        }

        LocalDate inicio = dataInicio;
        LocalDate fim = dataTermino;
        if (inicio == null || fim == null) {
            LocalDate hoje = LocalDate.now();
            if (inicio == null) inicio = hoje.withDayOfMonth(1);
            if (fim == null) fim = hoje.withDayOfMonth(hoje.lengthOfMonth());
        }

        ResumoFinanceiroDTO resumo = erpFinanceiroService.gerarResumo(inicio, fim, idEmpresa);
        return ResponseEntity.ok(resumo);
    }

    @GetMapping("/empresas")
    @PreAuthorize("hasPermission(null, 'GERENCIAR_ACESSOS')")
    public ResponseEntity<?> listarEmpresas() {
        return ResponseEntity.ok(erpFinanceiroService.listarEmpresas());
    }

    @GetMapping("/dfc")
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<?> gerarDfc(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataTermino
    ) {
        Integer idEmpresa = extrairEmpresaDoHeader(headerEmpresaId);
        if (idEmpresa == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "X-Empresa-Id header é obrigatório para esta requisição"
            ));
        }
        if (!validarAcessoEmpresa(idEmpresa)) {
            return ResponseEntity.status(403).body(Map.of(
                    "erro", true,
                    "mensagem", "Você não tem permissão de acessar esta empresa"
            ));
        }
        DfcResponseDTO dfc = erpFinanceiroService.gerarDfc(dataInicio, dataTermino, idEmpresa);
        return ResponseEntity.ok(dfc);
    }

    @PostMapping(value = "/import/ofx", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> importarOfx(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam(value = "tipo", required = false, defaultValue = "MANUAL") String tipo,
            @RequestPart("file") MultipartFile file
    ) {
        Integer idEmpresa = extrairEmpresaDoHeader(headerEmpresaId);
        if (idEmpresa == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "X-Empresa-Id header é obrigatório para esta requisição"
            ));
        }
        if (!validarAcessoEmpresa(idEmpresa)) {
            return ResponseEntity.status(403).body(Map.of(
                    "erro", true,
                    "mensagem", "Você não tem permissão de acessar esta empresa"
            ));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Arquivo OFX não informado"
            ));
        }
        try (var is = file.getInputStream()) {
            var resumo = ofxImportService.importar(is, idEmpresa, file.getOriginalFilename(), tipo);
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("erro", false);
            body.put("importacaoId", resumo.importacaoId());
            body.put("totalTransacoes", resumo.totalTransacoes());
            body.put("importadas", resumo.importadas());
            body.put("ignoradasDuplicadas", resumo.ignoradasDuplicadas());
            body.put("conta", resumo.conta());
            body.put("dataInicio", resumo.dataInicio() != null ? resumo.dataInicio().toString() : null);
            body.put("dataFim", resumo.dataFim() != null ? resumo.dataFim().toString() : null);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("Falha ao importar OFX", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Falha ao importar OFX: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/conciliacoes-ofx")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> listarConciliacoesOfx(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String conta
    ) {
        Integer idEmpresa = extrairEmpresaDoHeader(headerEmpresaId);
        if (idEmpresa == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "X-Empresa-Id header é obrigatório para esta requisição"
            ));
        }
        if (!validarAcessoEmpresa(idEmpresa)) {
            return ResponseEntity.status(403).body(Map.of(
                    "erro", true,
                    "mensagem", "Você não tem permissão de acessar esta empresa"
            ));
        }
        return ResponseEntity.ok(Map.of(
                "erro", false,
                "itens", ofxImportService.listarImportacoes(idEmpresa, dataInicio, dataFim, status, tipo, conta)
        ));
    }

    @DeleteMapping("/conciliacoes-ofx/{id}")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> excluirConciliacaoOfx(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @PathVariable("id") Long id
    ) {
        Integer idEmpresa = extrairEmpresaDoHeader(headerEmpresaId);
        if (idEmpresa == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "X-Empresa-Id header é obrigatório para esta requisição"
            ));
        }
        if (!validarAcessoEmpresa(idEmpresa)) {
            return ResponseEntity.status(403).body(Map.of(
                    "erro", true,
                    "mensagem", "Você não tem permissão de acessar esta empresa"
            ));
        }
        boolean removed = ofxImportService.excluirImportacao(idEmpresa, id);
        return ResponseEntity.ok(Map.of(
                "erro", false,
                "removido", removed
        ));
    }
}

