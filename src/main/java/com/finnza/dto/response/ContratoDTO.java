package com.finnza.dto.response;

import com.finnza.domain.entity.Contrato;
import com.finnza.domain.entity.Cobranca;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO para resposta de contrato
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContratoDTO {

    private Long id;
    private String titulo;
    private String descricao;
    private String conteudo;
    private ClienteDTO cliente;
    private BigDecimal valorContrato;
    private BigDecimal valorRecorrencia;
    private LocalDate dataVencimento;
    private Contrato.StatusContrato status;
    private Contrato.TipoPagamento tipoPagamento;
    private String servico;
    private LocalDate inicioContrato;
    private LocalDate inicioRecorrencia;
    private String whatsapp;
    private String asaasSubscriptionId;
    private List<CobrancaDTO> cobrancas;
    private CategoriaContrato categoria;
    private LocalDateTime dataCriacao;
    private LocalDateTime dataAtualizacao;
    
    /**
     * Enum para categoria do contrato (mutuamente exclusivo)
     * Em Dia = sem parcelas em atraso (inclui contratos com cobranças pendentes/futuras)
     * Em Atraso = exatamente 1 parcela em atraso
     * Inadimplente = 2+ parcelas em atraso
     */
    public enum CategoriaContrato {
        EM_DIA,
        EM_ATRASO,
        INADIMPLENTE
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClienteDTO {
        private Long id;
        private String razaoSocial;
        private String nomeFantasia;
        private String cpfCnpj;
        private String emailFinanceiro;
        private String celularFinanceiro;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CobrancaDTO {
        private Long id;
        private BigDecimal valor;
        private LocalDate dataVencimento;
        private LocalDate dataPagamento;
        private Cobranca.StatusCobranca status;
        private String linkPagamento;
        private String codigoBarras;
        private Integer numeroParcela;
        private String asaasPaymentId;
    }
}

