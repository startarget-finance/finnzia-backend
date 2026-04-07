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
 * Cadastro próprio Finnzia — plano de contas gerencial (modelo de plano), não integra Bom Controle.
 */
@Entity
@Table(name = "plano_contas_gerencial",
        indexes = {
                @Index(name = "idx_pc_ger_deleted", columnList = "deleted"),
                @Index(name = "idx_pc_ger_padrao", columnList = "padrao")
        })
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = "idEmpresas")
public class PlanoContasGerencial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String nome;

    @Column(nullable = false)
    @Builder.Default
    private Boolean padrao = false;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "plano_contas_gerencial_empresa",
            joinColumns = @JoinColumn(name = "plano_id", nullable = false))
    @Column(name = "id_empresa", nullable = false)
    @Builder.Default
    private Set<Integer> idEmpresas = new HashSet<>();

    @CreatedDate
    @Column(name = "data_criacao", nullable = false, updatable = false)
    private LocalDateTime dataCriacao;

    @LastModifiedDate
    @Column(name = "data_atualizacao")
    private LocalDateTime dataAtualizacao;

    @Column(name = "data_exclusao")
    private LocalDateTime dataExclusao;

    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private Boolean deleted = false;

    public void marcarExcluido() {
        this.deleted = true;
        this.dataExclusao = LocalDateTime.now();
    }
}
