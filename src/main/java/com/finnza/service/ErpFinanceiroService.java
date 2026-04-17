package com.finnza.service;

import com.finnza.domain.entity.MovimentacaoFinanceira;
import com.finnza.dto.response.DfcResponseDTO;
import com.finnza.dto.response.ResumoFinanceiroDTO;
import com.finnza.repository.MovimentacaoFinanceiraRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Camada de leitura financeira do ERP (sem dependência de integrações externas).
 *
 * Observação: atualmente usa a tabela persistida de movimentações no banco
 * (a mesma usada anteriormente como cache), mas a fonte é o seu ERP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ErpFinanceiroService {

    private final MovimentacaoFinanceiraRepository movimentacaoRepo;
    private final DashboardKpiService dashboardKpiService;

    /**
     * Busca movimentações no banco local no formato esperado pelo frontend.
     * orderBy: "data" | "valor" | "status" | "tipo" (campo da entidade: dataVencimento, valor, statusPagamento, debito)
     * orderDirection: "asc" | "desc"
     */
    public Map<String, Object> buscarMovimentacoes(
            LocalDate dataInicio, LocalDate dataTermino,
            String tipoData,
            Integer idEmpresa,
            Boolean debito,           // null = todos; true = despesas; false = receitas
            String statusPagamento,   // null | "pendente" | "pago"
            String orderBy,
            String orderDirection,
            int itensPorPagina,
            int numeroDaPagina
    ) {
        String sortField = "dataVencimento";
        if (orderBy != null && !orderBy.isBlank()) {
            switch (orderBy.trim().toLowerCase()) {
                case "valor" -> sortField = "valor";
                case "status" -> sortField = "statusPagamento";
                case "tipo" -> sortField = "debito";
                case "data" -> sortField = "dataVencimento";
                default -> {
                }
            }
        }
        Sort.Direction direction =
                (orderDirection != null && orderDirection.trim().equalsIgnoreCase("desc"))
                        ? Sort.Direction.DESC
                        : Sort.Direction.ASC;
        Sort sort = Sort.by(direction, sortField);
        PageRequest pageable = PageRequest.of(Math.max(0, numeroDaPagina - 1), Math.max(1, itensPorPagina), sort);

        Page<MovimentacaoFinanceira> page;
        boolean useCompetencia = "DataCompetencia".equalsIgnoreCase(tipoData);

        if (debito != null && statusPagamento != null) {
            // debito + statusPagamento — suportado por vencimento
            page = movimentacaoRepo.findByIdEmpresaAndDebitoAndStatusPagamentoAndDataVencimentoBetween(
                    idEmpresa, debito, statusPagamento, dataInicio, dataTermino, pageable);
        } else if (debito != null) {
            if (useCompetencia) {
                page = movimentacaoRepo.findByIdEmpresaAndDebitoAndDataCompetenciaBetween(
                        idEmpresa, debito, dataInicio, dataTermino, pageable);
            } else {
                page = movimentacaoRepo.findByIdEmpresaAndDebitoAndDataVencimentoBetween(
                        idEmpresa, debito, dataInicio, dataTermino, pageable);
            }
        } else {
            if (useCompetencia) {
                page = movimentacaoRepo.findByIdEmpresaAndDataCompetenciaBetween(
                        idEmpresa, dataInicio, dataTermino, pageable);
            } else {
                page = movimentacaoRepo.findByIdEmpresaAndDataVencimentoBetween(
                        idEmpresa, dataInicio, dataTermino, pageable);
            }
        }

        BigDecimal somaReceitas = movimentacaoRepo.sumValorByEmpresaAndDebitoAndVencimento(
                idEmpresa, false, dataInicio, dataTermino);
        BigDecimal somaDespesas = movimentacaoRepo.sumValorByEmpresaAndDebitoAndVencimento(
                idEmpresa, true, dataInicio, dataTermino);

        List<Map<String, Object>> itens = page.getContent().stream()
                .map(this::entityToMap)
                .collect(Collectors.toList());

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("movimentacoes", itens);
        resultado.put("total", page.getTotalElements());
        resultado.put("totalReceitas", somaReceitas != null ? somaReceitas.doubleValue() : 0.0);
        resultado.put("totalDespesas", somaDespesas != null ? somaDespesas.doubleValue() : 0.0);
        resultado.put("saldoLiquido", (somaReceitas != null ? somaReceitas : BigDecimal.ZERO)
                .subtract(somaDespesas != null ? somaDespesas : BigDecimal.ZERO).doubleValue());
        resultado.put("dataInicio", dataInicio.toString());
        resultado.put("dataTermino", dataTermino.toString());
        resultado.put("tipoData", tipoData != null ? tipoData : "DataVencimento");
        resultado.put("endpointUsado", "erp-db");
        resultado.put("fonteDados", "erp-db");
        resultado.put("usandoCache", false);
        resultado.put("atualizadoEm", LocalDateTime.now().toString());
        Map<String, Object> paginacao = new LinkedHashMap<>();
        paginacao.put("itensPorPagina", itensPorPagina);
        paginacao.put("numeroDaPagina", numeroDaPagina);
        paginacao.put("totalItens", page.getTotalElements());
        resultado.put("paginacao", paginacao);
        return resultado;
    }

    /**
     * Gera ResumoFinanceiroDTO a partir dos dados do banco.
     */
    public ResumoFinanceiroDTO gerarResumo(LocalDate dataInicio, LocalDate dataTermino, Integer idEmpresa) {
        List<MovimentacaoFinanceira> all =
                movimentacaoRepo.findAllByIdEmpresaAndDataVencimentoBetween(idEmpresa, dataInicio, dataTermino);

        ResumoFinanceiroDTO.BlocoResumo blocoReceber = calcularBlocoResumo(
                all.stream().filter(m -> Boolean.FALSE.equals(m.getDebito())).collect(Collectors.toList()));
        ResumoFinanceiroDTO.BlocoResumo blocoPagar = calcularBlocoResumo(
                all.stream().filter(m -> Boolean.TRUE.equals(m.getDebito())).collect(Collectors.toList()));

        double saldoDisponivel = blocoReceber.getTotalLiquidado() - blocoPagar.getTotalLiquidado();
        double saldoProjetado = blocoReceber.getTotalGeral() - blocoPagar.getTotalGeral();

        ResumoFinanceiroDTO resumo = ResumoFinanceiroDTO.builder()
                .periodo(ResumoFinanceiroDTO.PeriodoResumo.builder()
                        .dataInicio(dataInicio.toString())
                        .dataTermino(dataTermino.toString())
                        .build())
                .contasReceber(blocoReceber)
                .contasPagar(blocoPagar)
                .saldoDisponivel(saldoDisponivel)
                .saldoProjetado(saldoProjetado)
                .totalMovimentacoes(all.size())
                .usandoCache(false)
                .fonteDados("erp-db")
                .atualizadoEm(LocalDateTime.now().toString())
                .fallbackAtivo(false)
                .build();

        // Enriquecimento de KPIs para cards do dashboard (sempre preenche com valor numérico, inclusive 0).
        dashboardKpiService.preencherKPIs(resumo, dataInicio, dataTermino, idEmpresa);
        return resumo;
    }

    /**
     * Lista empresas disponíveis no ERP (derivadas das movimentações persistidas).
     * Formato compatível com o frontend existente: [{ "Id": 1, "Nome": "Empresa" }, ...]
     */
    public Map<String, Object> listarEmpresas() {
        List<Object[]> rows = movimentacaoRepo.listarEmpresasDistinct();

        List<Map<String, Object>> empresas = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> emp = new LinkedHashMap<>();
            emp.put("Id", (Integer) r[0]);
            emp.put("Nome", (String) r[1]);
            empresas.add(emp);
        }

        Map<String, Object> resposta = new LinkedHashMap<>();
        resposta.put("empresas", empresas);
        return resposta;
    }

    /**
     * Fluxo single-tenant: retorna o primeiro ID de empresa disponível nas movimentações.
     */
    public Optional<Integer> obterPrimeiraEmpresaDisponivelId() {
        return movimentacaoRepo.findFirstByIdEmpresaIsNotNullOrderByIdEmpresaAsc()
                .map(MovimentacaoFinanceira::getIdEmpresa)
                .filter(id -> id != null && id > 0);
    }

    /**
     * Gera um DFC simplificado a partir das movimentações do ERP no período.
     * (Receitas, Despesas, Resultado por mês).
     */
    public DfcResponseDTO gerarDfc(LocalDate dataInicio, LocalDate dataTermino, Integer idEmpresa) {
        long t0 = System.currentTimeMillis();

        List<MovimentacaoFinanceira> all =
                movimentacaoRepo.findAllByIdEmpresaAndDataVencimentoBetween(idEmpresa, dataInicio, dataTermino);

        List<YearMonth> meses = new ArrayList<>();
        YearMonth cur = YearMonth.from(dataInicio);
        YearMonth end = YearMonth.from(dataTermino);
        while (!cur.isAfter(end)) {
            meses.add(cur);
            cur = cur.plusMonths(1);
        }

        List<String> mesesLabel = meses.stream().map(this::formatarMesPt).collect(Collectors.toList());

        List<Double> receitasPorMes = new ArrayList<>();
        List<Double> despesasPorMes = new ArrayList<>();
        List<Double> resultadoPorMes = new ArrayList<>();

        double totalReceitas = 0;
        double totalDespesas = 0;

        for (YearMonth ym : meses) {
            LocalDate ini = ym.atDay(1);
            LocalDate fim = ym.atEndOfMonth();
            double rec = all.stream()
                    .filter(m -> Boolean.FALSE.equals(m.getDebito()))
                    .filter(m -> m.getDataVencimento() != null && !m.getDataVencimento().isBefore(ini) && !m.getDataVencimento().isAfter(fim))
                    .mapToDouble(m -> m.getValor() != null ? m.getValor().doubleValue() : 0.0)
                    .sum();
            double desp = all.stream()
                    .filter(m -> Boolean.TRUE.equals(m.getDebito()))
                    .filter(m -> m.getDataVencimento() != null && !m.getDataVencimento().isBefore(ini) && !m.getDataVencimento().isAfter(fim))
                    .mapToDouble(m -> m.getValor() != null ? m.getValor().doubleValue() : 0.0)
                    .sum();
            double res = rec - desp;

            receitasPorMes.add(rec);
            despesasPorMes.add(desp);
            resultadoPorMes.add(res);

            totalReceitas += rec;
            totalDespesas += desp;
        }

        List<DfcResponseDTO.Linha> linhas = List.of(
                DfcResponseDTO.Linha.builder()
                        .nome("RECEITAS")
                        .tipo("SECAO")
                        .nivel(0)
                        .valores(meses.stream().map(m -> 0.0).collect(Collectors.toList()))
                        .total(0.0)
                        .media(0.0)
                        .build(),
                DfcResponseDTO.Linha.builder()
                        .nome("Receitas")
                        .tipo("RECEITA")
                        .nivel(0)
                        .valores(receitasPorMes)
                        .total(totalReceitas)
                        .media(meses.isEmpty() ? 0.0 : totalReceitas / meses.size())
                        .build(),
                DfcResponseDTO.Linha.builder()
                        .nome("DESPESAS")
                        .tipo("SECAO")
                        .nivel(0)
                        .valores(meses.stream().map(m -> 0.0).collect(Collectors.toList()))
                        .total(0.0)
                        .media(0.0)
                        .build(),
                DfcResponseDTO.Linha.builder()
                        .nome("Despesas")
                        .tipo("DESPESA")
                        .nivel(0)
                        .valores(despesasPorMes)
                        .total(totalDespesas)
                        .media(meses.isEmpty() ? 0.0 : totalDespesas / meses.size())
                        .build(),
                DfcResponseDTO.Linha.builder()
                        .nome("Resultado")
                        .tipo("RESULTADO")
                        .nivel(0)
                        .valores(resultadoPorMes)
                        .total(totalReceitas - totalDespesas)
                        .media(meses.isEmpty() ? 0.0 : (totalReceitas - totalDespesas) / meses.size())
                        .build()
        );

        DfcResponseDTO.Indicadores ind = DfcResponseDTO.Indicadores.builder()
                .totalReceitas(totalReceitas)
                .totalDespesas(totalDespesas)
                .resultado(totalReceitas - totalDespesas)
                .margemPercentual(totalReceitas == 0 ? 0.0 : ((totalReceitas - totalDespesas) / totalReceitas) * 100.0)
                .ticketMedio(meses.isEmpty() ? 0.0 : totalReceitas / meses.size())
                .burnRateMensal(meses.isEmpty() ? 0.0 : totalDespesas / meses.size())
                // campos não usados no frontend atual: mantém 0
                .faturamentoNovosContratos(0.0)
                .receitasOperacionais(0.0)
                .outrasEntradas(0.0)
                .custosOperacionais(0.0)
                .despesasOperacionais(0.0)
                .atividadesEstrategicas(0.0)
                .investimentos(0.0)
                .financiamentos(0.0)
                .build();

        double elapsed = System.currentTimeMillis() - t0;
        return DfcResponseDTO.builder()
                .periodo(DfcResponseDTO.Periodo.builder()
                        .dataInicio(dataInicio.toString())
                        .dataTermino(dataTermino.toString())
                        .build())
                .meses(mesesLabel)
                .linhas(linhas)
                .indicadores(ind)
                .fonteDados("erp-db")
                .fallbackAtivo(false)
                .fallbackMetadata(null)
                .totalMovimentacoesProcessadas(all.size())
                .totalMovimentacoesDisponiveis(all.size())
                .paginasProcessadas(1)
                .paginasEstimadas(1)
                .tempoProcessamentoMs(elapsed)
                .usandoCache(false)
                .atualizadoEm(LocalDateTime.now().toString())
                .build();
    }

    private String formatarMesPt(YearMonth ym) {
        final String[] mesesPt = {"Jan","Fev","Mar","Abr","Mai","Jun","Jul","Ago","Set","Out","Nov","Dez"};
        String mm = mesesPt[ym.getMonthValue() - 1];
        String yy = String.valueOf(ym.getYear()).substring(2);
        return mm + "/" + yy;
    }

    private ResumoFinanceiroDTO.BlocoResumo calcularBlocoResumo(List<MovimentacaoFinanceira> movs) {
        double totalGeral = movs.stream()
                .mapToDouble(m -> m.getValor() != null ? m.getValor().doubleValue() : 0.0)
                .sum();
        List<MovimentacaoFinanceira> pagos = movs.stream()
                .filter(m -> "pago".equalsIgnoreCase(m.getStatusPagamento()))
                .collect(Collectors.toList());
        List<MovimentacaoFinanceira> pendentes = movs.stream()
                .filter(m -> !"pago".equalsIgnoreCase(m.getStatusPagamento()))
                .collect(Collectors.toList());
        double totalLiquidado = pagos.stream()
                .mapToDouble(m -> m.getValor() != null ? m.getValor().doubleValue() : 0.0).sum();
        double totalPendente = pendentes.stream()
                .mapToDouble(m -> m.getValor() != null ? m.getValor().doubleValue() : 0.0).sum();
        return ResumoFinanceiroDTO.BlocoResumo.builder()
                .totalGeral(totalGeral)
                .totalLiquidado(totalLiquidado)
                .totalPendente(totalPendente)
                .totalContas(movs.size())
                .contasPendentes(pendentes.size())
                .build();
    }

    private Map<String, Object> entityToMap(MovimentacaoFinanceira m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("IdMovimentacaoFinanceiraParcela", m.getIdMovimentacao());
        map.put("Debito", m.getDebito());
        map.put("DataVencimento", m.getDataVencimento() != null ? m.getDataVencimento().toString() : null);
        map.put("DataCompetencia", m.getDataCompetencia() != null ? m.getDataCompetencia().toString() : null);
        map.put("DataQuitacao", m.getDataQuitacao() != null ? m.getDataQuitacao().toString() : null);
        map.put("DataConciliacao", m.getDataConciliacao() != null ? m.getDataConciliacao().toString() : null);
        map.put("Valor", m.getValor() != null ? m.getValor().doubleValue() : 0.0);
        map.put("FormaPagamento", m.getFormaPagamento());
        map.put("NomeFormaPagamento", m.getNomeFormaPagamento());
        map.put("TipoMovimentacao", m.getTipoMovimentacao());
        map.put("NomeTipoMovimentacao", m.getNomeTipoMovimentacao());
        map.put("Nome", m.getNome());
        map.put("Observacao", m.getObservacao());
        map.put("NumeroParcela", m.getNumeroParcela());
        map.put("QuantidadeParcela", m.getQuantidadeParcela());
        map.put("IdCategoriaFinanceira", m.getIdCategoriaFinanceira());
        map.put("NomeCategoriaFinanceira", m.getNomeCategoriaFinanceira());
        map.put("IdContaFinanceira", m.getIdContaFinanceira());
        map.put("NomeContaFinanceira", m.getNomeContaFinanceira());
        map.put("NomeEmpresa", m.getNomeEmpresa());
        map.put("IdCliente", m.getIdCliente());
        map.put("IdFornecedor", m.getIdFornecedor());
        map.put("NomeClienteFornecedor", m.getNomeClienteFornecedor());
        return map;
    }
}

