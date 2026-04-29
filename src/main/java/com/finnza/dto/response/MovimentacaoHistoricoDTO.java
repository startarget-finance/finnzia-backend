package com.finnza.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MovimentacaoHistoricoDTO {
    private Long id;
    private Integer idEmpresa;
    private String acao;
    private String origemMovimentacaoId;
    private LocalDateTime dataEvento;
    private String descricao;
    private LocalDateTime restauradoEm;

    private Boolean debito;
    private LocalDate dataVencimento;
    private LocalDate dataCompetencia;
    private LocalDate dataQuitacao;
    private BigDecimal valor;
    private String nome;
    private String observacao;
    private String nomeCategoriaFinanceira;
    private String nomeContaFinanceira;
    private String nomeClienteFornecedor;
}
