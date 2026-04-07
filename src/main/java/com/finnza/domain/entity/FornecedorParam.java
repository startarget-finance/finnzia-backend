package com.finnza.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Fornecedor cadastrado na parametrização Finnzia (dados próprios).
 */
@Entity
@Table(
        name = "fornecedor_param",
        indexes = {
                @Index(name = "idx_fp_deleted", columnList = "deleted"),
                @Index(name = "idx_fp_cpf_cnpj", columnList = "cpfCnpj"),
                @Index(name = "idx_fp_ativo", columnList = "ativo"),
                @Index(name = "idx_fp_tipo_pessoa", columnList = "tipoPessoa")
        })
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = "idEmpresas")
public class FornecedorParam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String razaoSocial;

    @Column(length = 200)
    private String nomeFantasia;

    @Column(length = 20)
    private String cpfCnpj;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_pessoa", length = 2)
    @Builder.Default
    private Cliente.TipoPessoa tipoPessoa = Cliente.TipoPessoa.PJ;

    @Column(length = 100)
    private String email;

    @Column(length = 20)
    private String telefone;

    @Column(nullable = false)
    @Builder.Default
    private Boolean ativo = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "fornecedor_param_empresa",
            joinColumns = @JoinColumn(name = "fornecedor_id", nullable = false))
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
