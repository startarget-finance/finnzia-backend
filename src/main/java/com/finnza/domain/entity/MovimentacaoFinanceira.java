package com.finnza.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Movimentação financeira persistida no ERP.
 */
@Entity
@Table(
    name = "bc_movimentacoes",
    indexes = {
        @Index(name = "idx_bc_mov_empresa_venc",  columnList = "id_empresa, data_vencimento"),
        @Index(name = "idx_bc_mov_empresa_comp",  columnList = "id_empresa, data_competencia"),
        @Index(name = "idx_bc_mov_debito",        columnList = "debito"),
        @Index(name = "idx_bc_mov_status_pag",    columnList = "status_pagamento"),
        @Index(name = "idx_bc_mov_categoria",     columnList = "nome_categoria_financeira")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MovimentacaoFinanceira {

    /** ID da movimentação (compatível com o frontend atual). */
    @Id
    @Column(name = "id_bom_controle", nullable = false)
    private String idMovimentacao;

    @Column(name = "id_empresa", nullable = false)
    private Integer idEmpresa;

    /** true = despesa, false = receita */
    @Column(name = "debito")
    private Boolean debito;

    @Column(name = "data_vencimento")
    private LocalDate dataVencimento;

    @Column(name = "data_competencia")
    private LocalDate dataCompetencia;

    @Column(name = "data_quitacao")
    private LocalDate dataQuitacao;

    @Column(name = "data_conciliacao")
    private LocalDate dataConciliacao;

    @Column(name = "valor", precision = 15, scale = 2)
    private BigDecimal valor;

    @Column(name = "forma_pagamento")
    private Integer formaPagamento;

    @Column(name = "nome_forma_pagamento")
    private String nomeFormaPagamento;

    @Column(name = "tipo_movimentacao")
    private Integer tipoMovimentacao;

    @Column(name = "nome_tipo_movimentacao")
    private String nomeTipoMovimentacao;

    @Column(name = "nome", length = 500)
    private String nome;

    @Column(name = "observacao", columnDefinition = "TEXT")
    private String observacao;

    @Column(name = "numero_parcela")
    private Integer numeroParcela;

    @Column(name = "quantidade_parcela")
    private Integer quantidadeParcela;

    @Column(name = "id_categoria_financeira")
    private Integer idCategoriaFinanceira;

    @Column(name = "nome_categoria_financeira")
    private String nomeCategoriaFinanceira;

    @Column(name = "id_conta_financeira")
    private Integer idContaFinanceira;

    @Column(name = "nome_conta_financeira")
    private String nomeContaFinanceira;

    @Column(name = "nome_empresa")
    private String nomeEmpresa;

    @Column(name = "id_cliente")
    private Integer idCliente;

    @Column(name = "id_fornecedor")
    private Integer idFornecedor;

    @Column(name = "nome_cliente_fornecedor", length = 500)
    private String nomeClienteFornecedor;

    @Column(name = "departamento", length = 200)
    private String departamento;

    /** JSON: lista de { "categoria": "...", "percentual": 40.5 } para rateio entre categorias. */
    @Column(name = "rateio_json", columnDefinition = "TEXT")
    private String rateioJson;

    /**
     * JSON do cadastro (anexos em Base64, contatos, faturamento, fluxo receita/despesa, etc.).
     * Em séries com várias parcelas, a 1ª parcela pode conter binários; as demais são gravadas sem {@code conteudoBase64} nos anexos.
     */
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "id_funcionario")
    private Long idFuncionario;

    /** "pendente" se ainda não quitado, "pago" se DataQuitacao preenchida */
    @Column(name = "status_pagamento", length = 20)
    private String statusPagamento;

    /** JSON original completo retornado pelo Bom Controle */
    @Column(name = "dados_raw", columnDefinition = "TEXT")
    private String dadosRaw;

    @Column(name = "sincronizado_em")
    private LocalDateTime sincronizadoEm;

    /** Identifica o lote OFX de origem para fluxo de pré-aprovação. */
    @Column(name = "ofx_importacao_id")
    private Long ofxImportacaoId;

    /**
     * false = importado e ainda não aprovado no fluxo OFX.
     * true/null = visível normalmente em movimentações.
     */
    @Column(name = "ofx_aprovado")
    private Boolean ofxAprovado;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
