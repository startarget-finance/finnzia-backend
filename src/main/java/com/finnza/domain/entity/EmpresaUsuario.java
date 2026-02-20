package com.finnza.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Entidade EmpresaUsuario
 * Relação many-to-many entre Usuario e empresas do BOMControle
 * 
 * Permite que cada usuário tenha acesso a múltiplas empresas
 * com controle fino de permissões
 */
@Entity
@Table(name = "empresa_usuario",
       uniqueConstraints = {
           @UniqueConstraint(
               name = "uk_empresa_usuario",
               columnNames = {"usuario_id", "id_empresa"})
       },
       indexes = {
           @Index(name = "idx_empresa_usuario_usuario", columnList = "usuario_id"),
           @Index(name = "idx_empresa_usuario_empresa", columnList = "id_empresa"),
           @Index(name = "idx_empresa_usuario_padrao", columnList = "usuario_id,padrao")
       })
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmpresaUsuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false, foreignKey = @ForeignKey(name = "fk_empresa_usuario_usuario"))
    private Usuario usuario;

    /**
     * ID da empresa no BOMControle (não é PK da tabela, mas referência à API)
     */
    @Column(name = "id_empresa", nullable = false)
    private Integer idEmpresa;

    /**
     * Nome da empresa (cache do BOMControle para performance)
     */
    @Column(name = "nome_empresa", length = 255)
    private String nomeEmpresa;

    /**
     * Define se esta é a empresa padrão do usuário
     * (apenas uma empresa por usuário pode ser padrão)
     */
    @Column(name = "padrao", nullable = false)
    @Builder.Default
    private Boolean padrao = false;

    /**
     * Indica se o acesso a esta empresa está ativo
     */
    @Column(name = "ativo", nullable = false)
    @Builder.Default
    private Boolean ativo = true;

    @CreatedDate
    @Column(name = "data_criacao", nullable = false, updatable = false)
    private LocalDateTime dataCriacao;

    @LastModifiedDate
    @Column(name = "data_atualizacao")
    private LocalDateTime dataAtualizacao;

    /**
     * Metadados: informações de auditoria quando removido
     */
    @Column(name = "removido_por")
    private String removidoPor;

    @Column(name = "motivo_remocao", length = 500)
    private String motivoRemocao;

    @Column(name = "data_remocao")
    private LocalDateTime dataRemocao;

    /**
     * Validações
     */
    @PrePersist
    private void prePersist() {
        if (this.idEmpresa == null || this.idEmpresa <= 0) {
            throw new IllegalArgumentException("ID da empresa deve ser válido (> 0)");
        }
        if (this.usuario == null) {
            throw new IllegalArgumentException("Usuário não pode ser nulo");
        }
    }

    /**
     * Soft delete: marca como removido sem deletar fisicamente
     */
    public void remover(String removidoPor, String motivo) {
        this.ativo = false;
        this.removidoPor = removidoPor;
        this.motivoRemocao = motivo;
        this.dataRemocao = LocalDateTime.now();
    }

    /**
     * Restaura acesso a uma empresa removida
     */
    public void restaurar() {
        this.ativo = true;
        this.removidoPor = null;
        this.motivoRemocao = null;
        this.dataRemocao = null;
    }

    /**
     * Define se é empresa padrão (desativa outras se houver)
     */
    public void definirComoPadrao() {
        this.padrao = true;
    }

    public void removerDePadrao() {
        this.padrao = false;
    }
}
