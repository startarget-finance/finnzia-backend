package com.finnza.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * DTO consolidado com os principais indicadores financeiros trazidos do Bom Controle.
 * Mantemos o cálculo no backend para minimizar requisições em cascata no frontend
 * e respeitar os limites de rate limit impostos pela API externa.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ResumoFinanceiroDTO {

    private PeriodoResumo periodo;
    private BlocoResumo contasReceber;
    private BlocoResumo contasPagar;
    private double saldoDisponivel;
    private double saldoProjetado;
    private long totalMovimentacoes;
    private boolean usandoCache;
    private String fonteDados;
    private String atualizadoEm;
    private boolean fallbackAtivo;
    private Map<String, Object> fallbackMetadata;

    // =========================
    // KPIs para o dashboard
    // =========================
    /**
     * Média mensal de faturamento de novos contratos nos últimos 3 meses.
     */
    private Double mediaNovosContratosReais3m;

    /**
     * Média mensal de quantidade de novos contratos nos últimos 3 meses.
     */
    private Double mediaNovosContratosUnidades3m;

    /**
     * Custo financeiro + investimentos no período selecionado (consolidado).
     */
    private Double custoFinanceiroInvestimento;

    /**
     * Médias mensais nos últimos 6 meses.
     */
    private Double mediaCustoFixo;
    private Double mediaCustoVariavel;
    private Double mediaCustoEstrategico;

    /**
     * KPIs de contratos/clientes.
     */
    private Double totalClientesAtivos;
    private Double churnPercent;
    private Double ltvMeses;

    /**
     * Inadimplência no contexto do período (referência = dataTermino).
     */
    private Double inadimplenciaValor;
    private Double inadimplenciaTaxa;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PeriodoResumo {
        private String dataInicio;
        private String dataTermino;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BlocoResumo {
        private double totalGeral;
        private double totalLiquidado;
        private double totalPendente;
        private long totalContas;
        private long contasPendentes;
    }
}
