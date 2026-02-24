package com.finnza.dto.response;

import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Data;

@Data
@Builder(toBuilder = true)
public class DfcResponseDTO {
    private Periodo periodo;
    private List<String> meses;
    private List<Linha> linhas;
    private Indicadores indicadores;
    private String fonteDados;
    private boolean fallbackAtivo;
    private Map<String, Object> fallbackMetadata;
    private long totalMovimentacoesProcessadas;
    private long totalMovimentacoesDisponiveis;
    private long paginasProcessadas;
    private long paginasEstimadas;
    private double tempoProcessamentoMs;
    private boolean usandoCache;
    private String atualizadoEm;

    @Data
    @Builder(toBuilder = true)
    public static class Periodo {
        private String dataInicio;
        private String dataTermino;
    }

    @Data
    @Builder(toBuilder = true)
    public static class Linha {
        private String nome;
        private String tipo;
        private int nivel;
        private String grupo;
        private List<Double> valores;
        private double total;
        private double media;
    }

    @Data
    @Builder(toBuilder = true)
    public static class Indicadores {
        private double faturamentoNovosContratos;
        private double receitasOperacionais;
        private double outrasEntradas;
        private double custosOperacionais;
        private double despesasOperacionais;
        private double atividadesEstrategicas;
        private double investimentos;
        private double financiamentos;
        private double totalReceitas;
        private double totalDespesas;
        private double resultado;
        private double margemPercentual;
        private double ticketMedio;
        private double burnRateMensal;
    }
}
