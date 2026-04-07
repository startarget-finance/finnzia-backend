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
 * Funcionário cadastrado na parametrização Finnzia (dados próprios).
 */
@Entity
@Table(
        name = "funcionario_param",
        indexes = {
                @Index(name = "idx_funp_deleted", columnList = "deleted"),
                @Index(name = "idx_funp_cpf", columnList = "cpf"),
                @Index(name = "idx_funp_ativo", columnList = "ativo")
        })
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = "idEmpresas")
public class FuncionarioParam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nome_completo", nullable = false, length = 200)
    private String nomeCompleto;

    @Column(length = 14)
    private String cpf;

    @Column(length = 120)
    private String cargo;

    @Column(length = 120)
    private String departamento;

    @Column(length = 100)
    private String email;

    @Column(length = 20)
    private String telefone;

    @Column(nullable = false)
    @Builder.Default
    private Boolean ativo = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "funcionario_param_empresa",
            joinColumns = @JoinColumn(name = "funcionario_id", nullable = false))
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
