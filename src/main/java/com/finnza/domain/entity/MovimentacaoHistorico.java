package com.finnza.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "movimentacao_historico",
        indexes = {
                @Index(name = "idx_mov_hist_empresa_evento", columnList = "id_empresa, data_evento"),
                @Index(name = "idx_mov_hist_origem", columnList = "origem_movimentacao_id")
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MovimentacaoHistorico {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_empresa", nullable = false)
    private Integer idEmpresa;

    @Column(name = "acao", length = 20, nullable = false)
    private String acao; // CRIACAO | EDICAO

    @Column(name = "origem_movimentacao_id", length = 150)
    private String origemMovimentacaoId;

    @CreationTimestamp
    @Column(name = "data_evento", nullable = false, updatable = false)
    private LocalDateTime dataEvento;

    @Column(name = "descricao", length = 500)
    private String descricao;

    @Column(name = "restaurado_em")
    private LocalDateTime restauradoEm;

    @Column(name = "debito")
    private Boolean debito;

    @Column(name = "data_vencimento")
    private LocalDate dataVencimento;

    @Column(name = "data_competencia")
    private LocalDate dataCompetencia;

    @Column(name = "data_quitacao")
    private LocalDate dataQuitacao;

    @Column(name = "valor", precision = 15, scale = 2)
    private BigDecimal valor;

    @Column(name = "nome", length = 500)
    private String nome;

    @Column(name = "observacao", columnDefinition = "TEXT")
    private String observacao;

    @Column(name = "nome_categoria_financeira", length = 500)
    private String nomeCategoriaFinanceira;

    @Column(name = "nome_conta_financeira", length = 500)
    private String nomeContaFinanceira;

    @Column(name = "nome_cliente_fornecedor", length = 500)
    private String nomeClienteFornecedor;
}
