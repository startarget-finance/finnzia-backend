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

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoints financeiros do ERP (substitui integração Bom Controle).
 *
 * Fluxo atual: single-tenant por login.
 * O header X-Empresa-Id continua suportado, mas quando ausente o sistema tenta:
 * 1) empresa padrão do usuário, 2) primeira empresa disponível nas movimentações.
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
            // Se o usuário não tem vínculos em empresa_usuario (modo single-tenant),
            // não bloquear o acesso por ausência de mapeamento legado.
            if (!usuarioEmpresaService.usuarioTemEmpresasAtivasPorEmail(email)) {
                return true;
            }
            return usuarioEmpresaService.validarAcessoUsuarioEmpresa(email, empresaId);
        } catch (Exception e) {
            log.error("❌ Erro ao validar acesso à empresa {} para usuário {}:", empresaId, email, e);
            return false;
        }
    }

    private Integer resolverEmpresaId(String headerEmpresaId) {
        Integer idEmpresa = extrairEmpresaDoHeader(headerEmpresaId);
        if (idEmpresa != null) {
            return idEmpresa;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            String email = auth.getName();
            Integer empresaPadrao = usuarioEmpresaService.obterIdEmpresaPadraoPorEmail(email).orElse(null);
            if (empresaPadrao != null && empresaPadrao > 0) {
                return empresaPadrao;
            }
        }
        return erpFinanceiroService.obterPrimeiraEmpresaDisponivelId().orElse(null);
    }

    private ResumoFinanceiroDTO resumoVazio(LocalDate inicio, LocalDate fim) {
        return ResumoFinanceiroDTO.builder()
                .periodo(ResumoFinanceiroDTO.PeriodoResumo.builder()
                        .dataInicio(inicio.toString())
                        .dataTermino(fim.toString())
                        .build())
                .contasReceber(ResumoFinanceiroDTO.BlocoResumo.builder()
                        .totalGeral(0)
                        .totalLiquidado(0)
                        .totalPendente(0)
                        .totalContas(0)
                        .contasPendentes(0)
                        .build())
                .contasPagar(ResumoFinanceiroDTO.BlocoResumo.builder()
                        .totalGeral(0)
                        .totalLiquidado(0)
                        .totalPendente(0)
                        .totalContas(0)
                        .contasPendentes(0)
                        .build())
                .saldoDisponivel(0)
                .saldoProjetado(0)
                .totalMovimentacoes(0)
                .usandoCache(false)
                .fonteDados("erp-db")
                .atualizadoEm(LocalDateTime.now().toString())
                .fallbackAtivo(true)
                .fallbackMetadata(Map.of("mensagem", "Sem movimentações para a empresa no período"))
                .build();
    }

    private DfcResponseDTO dfcVazio(LocalDate inicio, LocalDate fim) {
        return DfcResponseDTO.builder()
                .periodo(DfcResponseDTO.Periodo.builder()
                        .dataInicio(inicio.toString())
                        .dataTermino(fim.toString())
                        .build())
                .meses(List.of())
                .linhas(List.of())
                .indicadores(DfcResponseDTO.Indicadores.builder()
                        .faturamentoNovosContratos(0)
                        .receitasOperacionais(0)
                        .outrasEntradas(0)
                        .custosOperacionais(0)
                        .despesasOperacionais(0)
                        .atividadesEstrategicas(0)
                        .investimentos(0)
                        .financiamentos(0)
                        .totalReceitas(0)
                        .totalDespesas(0)
                        .resultado(0)
                        .margemPercentual(0)
                        .ticketMedio(0)
                        .burnRateMensal(0)
                        .build())
                .fonteDados("erp-db")
                .fallbackAtivo(true)
                .fallbackMetadata(Map.of("mensagem", "Sem movimentações para a empresa no período"))
                .totalMovimentacoesProcessadas(0)
                .totalMovimentacoesDisponiveis(0)
                .paginasProcessadas(0)
                .paginasEstimadas(0)
                .tempoProcessamentoMs(0)
                .usandoCache(false)
                .atualizadoEm(LocalDateTime.now().toString())
                .build();
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
        LocalDate inicio = dataInicio;
        LocalDate fim = dataTermino;
        if (inicio == null || fim == null) {
            LocalDate hoje = LocalDate.now();
            if (inicio == null) inicio = hoje.withDayOfMonth(1);
            if (fim == null) fim = hoje.withDayOfMonth(hoje.lengthOfMonth());
        }

        Integer idEmpresa = resolverEmpresaId(headerEmpresaId);

        if (idEmpresa == null) {
            Map<String, Object> resultado = new LinkedHashMap<>();
            resultado.put("movimentacoes", List.of());
            resultado.put("total", 0);
            resultado.put("totalReceitas", 0.0);
            resultado.put("totalDespesas", 0.0);
            resultado.put("saldoLiquido", 0.0);
            resultado.put("dataInicio", inicio.toString());
            resultado.put("dataTermino", fim.toString());
            resultado.put("tipoData", tipoData != null ? tipoData : "DataVencimento");
            resultado.put("endpointUsado", "erp-db");
            resultado.put("fonteDados", "erp-db");
            resultado.put("usandoCache", false);
            resultado.put("atualizadoEm", LocalDateTime.now().toString());
            resultado.put("paginacao", Map.of(
                    "itensPorPagina", itensPorPagina,
                    "numeroDaPagina", numeroDaPagina,
                    "totalItens", 0
            ));
            return ResponseEntity.ok(resultado);
        } else if (!validarAcessoEmpresa(idEmpresa)) {
            return ResponseEntity.status(403).body(Map.of(
                    "erro", true,
                    "mensagem", "Você não tem permissão de acessar esta empresa"
            ));
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
        Integer idEmpresa = resolverEmpresaId(headerEmpresaId);
        if (idEmpresa != null && !validarAcessoEmpresa(idEmpresa)) {
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

        if (idEmpresa == null) {
            return ResponseEntity.ok(resumoVazio(inicio, fim));
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
        Integer idEmpresa = resolverEmpresaId(headerEmpresaId);
        if (idEmpresa == null) {
            return ResponseEntity.ok(dfcVazio(dataInicio, dataTermino));
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
        Integer idEmpresa = resolverEmpresaId(headerEmpresaId);
        if (idEmpresa == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Não foi possível identificar a empresa para esta requisição"
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
        Integer idEmpresa = resolverEmpresaId(headerEmpresaId);
        if (idEmpresa == null) {
            return ResponseEntity.ok(Map.of(
                    "erro", false,
                    "itens", List.of()
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
        Integer idEmpresa = resolverEmpresaId(headerEmpresaId);
        if (idEmpresa == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Não foi possível identificar a empresa para esta requisição"
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

