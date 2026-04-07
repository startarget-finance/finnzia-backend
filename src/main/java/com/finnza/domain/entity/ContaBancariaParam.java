package com.finnza.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Conta bancária cadastrada na parametrização Finnzia (dados próprios, sem Bom Controle).
 */
@Entity
@Table(
        name = "conta_bancaria_param",
        indexes = {
                @Index(name = "idx_cbp_deleted", columnList = "deleted"),
                @Index(name = "idx_cbp_ativo", columnList = "ativo")
        })
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = "idEmpresas")
public class ContaBancariaParam {

    public enum TipoConta {
        CORRENTE,
        POUPANCA
    }

    /** Bancaria (conta em instituição) ou Dinheiro (caixa), no estilo telas tipo Bom Controle. */
    public enum CategoriaConta {
        BANCARIA,
        DINHEIRO
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nome amigável exibido na lista (ex.: "Conta Nubank matriz"). Se vazio, usa {@link #banco}. */
    @Column(name = "nome_conta", length = 200)
    private String nomeConta;

    @Enumerated(EnumType.STRING)
    @Column(name = "categoria", nullable = false, length = 16)
    @Builder.Default
    private CategoriaConta categoria = CategoriaConta.BANCARIA;

    /** Instituição (subtítulo em TIPO), ex.: nome do banco completo. */
    @Column(name = "instituicao", length = 200)
    private String instituicao;

    @Column(nullable = false, length = 120)
    private String banco;

    @Column(nullable = false, length = 20)
    private String agencia;

    @Column(nullable = false, length = 30)
    private String conta;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 16)
    @Builder.Default
    private TipoConta tipo = TipoConta.CORRENTE;

    @Column(name = "saldo_inicial", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal saldoInicial = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private Boolean ativo = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "conta_bancaria_param_empresa",
            joinColumns = @JoinColumn(name = "conta_id", nullable = false))
    @Column(name = "id_empresa", nullable = false)
    @Builder.Default
    private Set<Integer> idEmpresas = new HashSet<>();

    @CreatedDate
    @Column(name = "data_criacao", nullable = false, updatable = false)
    private LocalDateTime dataCriacao;

    @LastModifiedDate
    @Column(name = "data_atualizacao")
    private LocalDateTime dataAtualizacao;

    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private Boolean deleted = false;

    public void softDelete() {
        this.deleted = true;
    }
}
