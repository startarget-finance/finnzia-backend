package com.finnza.controller;

import com.finnza.dto.response.DfcResponseDTO;
import com.finnza.dto.response.ResumoFinanceiroDTO;
import com.finnza.service.MovimentacaoHistoricoService;
import com.finnza.service.ErpFinanceiroService;
import com.finnza.service.MovimentacaoLancamentoImportService;
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
import java.math.BigDecimal;
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

    public record CriarMovimentacaoRequest(
            Boolean debito,
            String dataVencimento,
            String dataCompetencia,
            String dataQuitacao,
            BigDecimal valor,
            String nome,
            String observacao,
            String nomeCategoriaFinanceira,
            String nomeContaFinanceira,
            String nomeClienteFornecedor,
            /** Ex.: SEMANAL, MENSAL, TRIMESTRAL. Null ou NENHUMA = lançamento único. */
            String recorrenciaFrequencia,
            /** Total de parcelas da série (inclui a primeira). Null ou 1 = um único lançamento. */
            Integer recorrenciaQuantidade,
            /** Ex.: Dinheiro, PIX. Opcional. */
            String nomeFormaPagamento,
            /** FORNECEDOR | FUNCIONARIO | IMPOSTOS | TRANSFERENCIA — só despesa; opcional. */
            String tipoMovimentoDespesa,
            String departamento,
            /** JSON array de rateio, ex.: [{"categoria":"X","percentual":50}]. */
            String rateioJson,
            Long idFuncionario,
            /** JSON com anexos (Base64), contatos, faturamento, fluxo, etc. */
            String metadataJson
    ) {}

    private final ErpFinanceiroService erpFinanceiroService;
    private final MovimentacaoHistoricoService movimentacaoHistoricoService;
    private final MovimentacaoLancamentoImportService movimentacaoLancamentoImportService;
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
            Integer empresaContexto = usuarioEmpresaService.obterIdEmpresaContextoPorEmail(email).orElse(null);
            if (empresaContexto != null && empresaContexto > 0) {
                return empresaContexto;
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

    @GetMapping("/movimentacoes/{id}")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> obterMovimentacao(
            @PathVariable("id") String idMovimentacao,
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId
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
        if (idMovimentacao == null || idMovimentacao.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Identificador da movimentação é obrigatório"
            ));
        }
        return erpFinanceiroService.buscarMovimentacaoMap(idEmpresa, idMovimentacao)
                .map(mov -> ResponseEntity.ok(Map.of("erro", false, "movimentacao", mov)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                        "erro", true,
                        "mensagem", "Movimentação não encontrada"
                )));
    }

    @PostMapping("/movimentacoes")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> criarMovimentacao(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestBody CriarMovimentacaoRequest request
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
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Dados da movimentação não informados"
            ));
        }
        if (request.valor() == null || request.valor().compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Valor deve ser maior que zero"
            ));
        }
        if (request.nome() == null || request.nome().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Descrição é obrigatória"
            ));
        }
        if (request.nomeCategoriaFinanceira() == null || request.nomeCategoriaFinanceira().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Categoria é obrigatória"
            ));
        }
        if (request.dataVencimento() == null || request.dataVencimento().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Data de vencimento é obrigatória"
            ));
        }
        if (request.metadataJson() != null && request.metadataJson().length() > 4_000_000) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Campo metadataJson excede o tamanho máximo permitido."
            ));
        }

        try {
            LocalDate dataVencimento = LocalDate.parse(request.dataVencimento());
            LocalDate dataCompetencia = request.dataCompetencia() == null || request.dataCompetencia().isBlank()
                    ? dataVencimento
                    : LocalDate.parse(request.dataCompetencia());
            LocalDate dataQuitacao = request.dataQuitacao() == null || request.dataQuitacao().isBlank()
                    ? null
                    : LocalDate.parse(request.dataQuitacao());

            String freqNorm = ErpFinanceiroService.normalizarFrequenciaRecorrencia(request.recorrenciaFrequencia());
            int parcelas = request.recorrenciaQuantidade() == null ? 1 : request.recorrenciaQuantidade();
            if (parcelas < 1) {
                parcelas = 1;
            }
            if (parcelas >= 2 && "NENHUMA".equals(freqNorm)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "erro", true,
                        "mensagem", "Para criar várias parcelas, informe a frequência da recorrência."
                ));
            }
            if (parcelas >= 2 && dataQuitacao != null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "erro", true,
                        "mensagem", "Recorrência não pode ser usada com lançamento já quitado no cadastro."
                ));
            }

            boolean serie = parcelas >= 2 && !"NENHUMA".equals(freqNorm);

            if (serie) {
                List<Map<String, Object>> lista = erpFinanceiroService.criarMovimentacoesRecorrentes(
                        idEmpresa,
                        request.debito(),
                        dataVencimento,
                        dataCompetencia,
                        request.valor(),
                        request.nome().trim(),
                        request.observacao(),
                        request.nomeCategoriaFinanceira().trim(),
                        request.nomeContaFinanceira(),
                        request.nomeClienteFornecedor(),
                        request.nomeFormaPagamento(),
                        request.tipoMovimentoDespesa(),
                        request.departamento(),
                        request.rateioJson(),
                        request.idFuncionario(),
                        request.metadataJson(),
                        freqNorm,
                        parcelas
                );
                for (Map<String, Object> row : lista) {
                    String idOrigem = String.valueOf(row.getOrDefault("IdMovimentacaoFinanceiraParcela", ""));
                    LocalDate dv = LocalDate.parse(String.valueOf(row.get("DataVencimento")));
                    LocalDate dc = row.get("DataCompetencia") != null
                            ? LocalDate.parse(String.valueOf(row.get("DataCompetencia")))
                            : dv;
                    movimentacaoHistoricoService.registrarCriacao(
                            idEmpresa,
                            idOrigem,
                            request.debito(),
                            dv,
                            dc,
                            null,
                            request.valor(),
                            request.nome().trim(),
                            request.observacao(),
                            request.nomeCategoriaFinanceira().trim(),
                            request.nomeContaFinanceira(),
                            request.nomeClienteFornecedor()
                    );
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("erro", false);
                body.put("mensagem", lista.size() + " parcelas cadastradas com sucesso.");
                body.put("movimentacoes", lista);
                body.put("movimentacao", lista.isEmpty() ? null : lista.get(0));
                body.put("totalCadastrados", lista.size());
                return ResponseEntity.ok(body);
            }

            Map<String, Object> novaMov = erpFinanceiroService.criarMovimentacaoManual(
                    idEmpresa,
                    request.debito(),
                    dataVencimento,
                    dataCompetencia,
                    dataQuitacao,
                    request.valor(),
                    request.nome().trim(),
                    request.observacao(),
                    request.nomeCategoriaFinanceira().trim(),
                    request.nomeContaFinanceira(),
                    request.nomeClienteFornecedor(),
                    request.nomeFormaPagamento(),
                    request.tipoMovimentoDespesa(),
                    request.departamento(),
                    request.rateioJson(),
                    request.idFuncionario(),
                    request.metadataJson()
            );
            String idOrigem = String.valueOf(novaMov.getOrDefault("IdMovimentacaoFinanceiraParcela", ""));
            movimentacaoHistoricoService.registrarCriacao(
                    idEmpresa,
                    idOrigem,
                    request.debito(),
                    dataVencimento,
                    dataCompetencia,
                    dataQuitacao,
                    request.valor(),
                    request.nome().trim(),
                    request.observacao(),
                    request.nomeCategoriaFinanceira().trim(),
                    request.nomeContaFinanceira(),
                    request.nomeClienteFornecedor()
            );

            return ResponseEntity.ok(Map.of(
                    "erro", false,
                    "mensagem", "Movimentação cadastrada com sucesso",
                    "movimentacao", novaMov
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Não foi possível cadastrar a movimentação: " + e.getMessage()
            ));
        }
    }

    @PutMapping("/movimentacoes/{id}")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> atualizarMovimentacao(
            @PathVariable("id") String idMovimentacao,
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestBody CriarMovimentacaoRequest request
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
        if (idMovimentacao == null || idMovimentacao.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Identificador da movimentação é obrigatório"
            ));
        }
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Dados da movimentação não informados"
            ));
        }
        if (request.valor() == null || request.valor().compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Valor deve ser maior que zero"
            ));
        }
        if (request.nome() == null || request.nome().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Descrição é obrigatória"
            ));
        }
        if (request.nomeCategoriaFinanceira() == null || request.nomeCategoriaFinanceira().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Categoria é obrigatória"
            ));
        }
        if (request.dataVencimento() == null || request.dataVencimento().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Data de vencimento é obrigatória"
            ));
        }
        if (request.metadataJson() != null && request.metadataJson().length() > 4_000_000) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Campo metadataJson excede o tamanho máximo permitido."
            ));
        }

        try {
            var movAntes = erpFinanceiroService.buscarMovimentacao(idEmpresa, idMovimentacao).orElse(null);
            Boolean movAntesDebito = movAntes != null ? movAntes.getDebito() : null;
            LocalDate movAntesVencimento = movAntes != null ? movAntes.getDataVencimento() : null;
            LocalDate movAntesCompetencia = movAntes != null ? movAntes.getDataCompetencia() : null;
            LocalDate movAntesQuitacao = movAntes != null ? movAntes.getDataQuitacao() : null;
            BigDecimal movAntesValor = movAntes != null ? movAntes.getValor() : null;
            String movAntesNome = movAntes != null ? movAntes.getNome() : null;
            String movAntesObs = movAntes != null ? movAntes.getObservacao() : null;
            String movAntesCategoria = movAntes != null ? movAntes.getNomeCategoriaFinanceira() : null;
            String movAntesConta = movAntes != null ? movAntes.getNomeContaFinanceira() : null;
            String movAntesClienteFornecedor = movAntes != null ? movAntes.getNomeClienteFornecedor() : null;
            LocalDate dataVencimento = LocalDate.parse(request.dataVencimento());
            LocalDate dataCompetencia = request.dataCompetencia() == null || request.dataCompetencia().isBlank()
                    ? dataVencimento
                    : LocalDate.parse(request.dataCompetencia());
            LocalDate dataQuitacao = request.dataQuitacao() == null || request.dataQuitacao().isBlank()
                    ? null
                    : LocalDate.parse(request.dataQuitacao());

            Map<String, Object> atualizada = erpFinanceiroService.atualizarMovimentacaoManual(
                    idEmpresa,
                    idMovimentacao,
                    request.debito(),
                    dataVencimento,
                    dataCompetencia,
                    dataQuitacao,
                    request.valor(),
                    request.nome().trim(),
                    request.observacao(),
                    request.nomeCategoriaFinanceira().trim(),
                    request.nomeContaFinanceira(),
                    request.nomeClienteFornecedor(),
                    request.nomeFormaPagamento(),
                    request.tipoMovimentoDespesa(),
                    request.departamento(),
                    request.rateioJson(),
                    request.idFuncionario(),
                    request.metadataJson()
            );
            if (movAntes != null) {
                movimentacaoHistoricoService.registrarEdicao(
                        idEmpresa,
                        idMovimentacao,
                        movAntesDebito,
                        movAntesVencimento,
                        movAntesCompetencia,
                        movAntesQuitacao,
                        movAntesValor,
                        movAntesNome,
                        movAntesObs,
                        movAntesCategoria,
                        movAntesConta,
                        movAntesClienteFornecedor
                );
            }

            return ResponseEntity.ok(Map.of(
                    "erro", false,
                    "mensagem", "Movimentação atualizada com sucesso",
                    "movimentacao", atualizada
            ));
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Não foi possível atualizar";
            if (msg.contains("não encontrada")) {
                return ResponseEntity.status(404).body(Map.of(
                        "erro", true,
                        "mensagem", msg
                ));
            }
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", msg
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Não foi possível atualizar a movimentação: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/movimentacoes/historico")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> listarHistoricoMovimentacoes(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam(required = false) String acao,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,
            @RequestParam(required = false, defaultValue = "20") Integer itensPorPagina,
            @RequestParam(required = false, defaultValue = "1") Integer numeroDaPagina
    ) {
        Integer idEmpresa = resolverEmpresaId(headerEmpresaId);
        if (idEmpresa == null) {
            return ResponseEntity.ok(Map.of(
                    "itens", List.of(),
                    "paginacao", Map.of(
                            "itensPorPagina", itensPorPagina,
                            "numeroDaPagina", numeroDaPagina,
                            "totalItens", 0,
                            "totalPaginas", 0
                    )
            ));
        }
        if (!validarAcessoEmpresa(idEmpresa)) {
            return ResponseEntity.status(403).body(Map.of(
                    "erro", true,
                    "mensagem", "Você não tem permissão de acessar esta empresa"
            ));
        }
        return ResponseEntity.ok(
                movimentacaoHistoricoService.listar(
                        idEmpresa,
                        acao,
                        dataInicio,
                        dataFim,
                        itensPorPagina,
                        numeroDaPagina
                )
        );
    }

    @PostMapping("/movimentacoes/historico/{id}/restaurar")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> restaurarMovimentacaoHistorico(
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
        try {
            Map<String, Object> mov = movimentacaoHistoricoService.restaurar(idEmpresa, id);
            return ResponseEntity.ok(Map.of(
                    "erro", false,
                    "mensagem", "Movimentação restaurada com sucesso",
                    "movimentacao", mov
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(404).body(Map.of(
                    "erro", true,
                    "mensagem", ex.getMessage()
            ));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Não foi possível restaurar a movimentação: " + ex.getMessage()
            ));
        }
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

    public record ImportLancamentosPreviewRequest(
            String csvContent,
            /** receita | despesa */
            String tipo
    ) {}

    public record ImportLancamentosConfirmRequest(
            String tipo,
            List<Map<String, Object>> linhas,
            String categoriaPadrao,
            String contaPadrao,
            String formaPagamentoPadrao,
            String nomeArquivo
    ) {}

    @PostMapping("/import/lancamentos/preview")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> previewImportLancamentos(
            @RequestBody ImportLancamentosPreviewRequest request
    ) {
        if (request == null || request.csvContent() == null || request.csvContent().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Conteúdo CSV vazio."
            ));
        }
        String tipo = request.tipo() == null || request.tipo().isBlank() ? "receita" : request.tipo().trim();
        try {
            return ResponseEntity.ok(movimentacaoLancamentoImportService.previewCsv(request.csvContent(), tipo));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Não foi possível ler a planilha: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/import/lancamentos")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> confirmarImportLancamentos(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestBody ImportLancamentosConfirmRequest request
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
        if (request == null || request.linhas() == null || request.linhas().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Nenhuma linha válida para importar."
            ));
        }
        String tipo = request.tipo() == null || request.tipo().isBlank() ? "receita" : request.tipo().trim();
        try {
            Map<String, Object> resultado = movimentacaoLancamentoImportService.importarLinhas(
                    idEmpresa,
                    tipo,
                    request.linhas(),
                    request.categoriaPadrao(),
                    request.contaPadrao(),
                    request.formaPagamentoPadrao(),
                    request.nomeArquivo()
            );
            return ResponseEntity.ok(resultado);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Falha ao importar lançamentos", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Falha ao importar lançamentos: " + e.getMessage()
            ));
        }
    }

    @PostMapping(value = "/import/ofx", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> importarOfx(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam(value = "tipo", required = false, defaultValue = "MANUAL") String tipo,
            @RequestParam(value = "idContaBancaria", required = false) Integer idContaBancaria,
            @RequestParam(value = "nomeContaExibicao", required = false) String nomeContaExibicao,
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
            String nomeExibicao = nomeContaExibicao != null ? nomeContaExibicao.trim() : null;
            if (nomeExibicao != null && nomeExibicao.length() > 500) {
                nomeExibicao = nomeExibicao.substring(0, 500);
            }
            var resumo = ofxImportService.importar(
                    is,
                    idEmpresa,
                    file.getOriginalFilename(),
                    tipo,
                    idContaBancaria,
                    nomeExibicao);
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

    @PostMapping("/conciliacoes-ofx/{id}/aprovar")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> aprovarConciliacaoOfx(
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
        try {
            var resumo = ofxImportService.aprovarImportacao(idEmpresa, id);
            return ResponseEntity.ok(Map.of(
                    "erro", false,
                    "importacaoId", resumo.importacaoId(),
                    "status", resumo.status(),
                    "aprovadasAgora", resumo.aprovadasAgora(),
                    "conciliadasTotal", resumo.conciliadasTotal(),
                    "pendentesTotal", resumo.pendentesTotal(),
                    "totalMovimentacoes", resumo.totalMovimentacoes()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", e.getMessage()
            ));
        }
    }

    @PostMapping("/conciliacoes-ofx/backfill")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<?> backfillConciliacaoOfx(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam(value = "limite", required = false, defaultValue = "1000") Integer limite
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
        int lim = (limite == null || limite <= 0) ? 1000 : limite;
        var resumo = ofxImportService.backfillDadosOfx(idEmpresa, lim);
        return ResponseEntity.ok(Map.of(
                "erro", false,
                "processadas", resumo.processadas(),
                "categoriaPreenchida", resumo.categoriaPreenchida(),
                "parceiroPreenchido", resumo.parceiroPreenchido()
        ));
    }
}

