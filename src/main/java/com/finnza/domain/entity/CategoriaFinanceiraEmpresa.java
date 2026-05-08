package com.finnza.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "categoria_financeira_empresa", indexes = {
        @Index(name = "idx_cat_fin_emp_empresa_tipo", columnList = "id_empresa,tipo"),
        @Index(name = "idx_cat_fin_emp_deleted", columnList = "deleted"),
        @Index(name = "idx_cat_fin_parent_empresa", columnList = "id_empresa,parent_id"),
        @Index(name = "idx_cat_fin_empresa_tipo_parent", columnList = "id_empresa,tipo,parent_id")
})
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoriaFinanceiraEmpresa {

    public enum TipoCategoria {
        RECEITA,
        DESPESA
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_empresa", nullable = false)
    private Integer idEmpresa;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TipoCategoria tipo;

    /** Nome deste nó (qualquer nível: categoria, subcategoria, etc.). */
    @Column(name = "nome", nullable = false, length = 120)
    private String nome;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "ordem", nullable = false)
    @Builder.Default
    private Integer ordem = 0;

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
