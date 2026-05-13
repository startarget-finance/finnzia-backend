package com.finnza.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Entidade Usuario
 * Representa um usuário do sistema financeiro
 */
@Entity
@Table(name = "usuarios", 
       uniqueConstraints = {
           @UniqueConstraint(columnNames = "email")
       },
       indexes = {
           @Index(name = "idx_usuario_email", columnList = "email"),
           @Index(name = "idx_usuario_status", columnList = "status"),
           @Index(name = "idx_usuario_deleted", columnList = "deleted"),
           @Index(name = "idx_usuario_email_deleted", columnList = "email,deleted"),
           @Index(name = "idx_usuario_status_deleted", columnList = "status,deleted"),
           @Index(name = "idx_usuario_role_status", columnList = "role,status")
       })
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = {"permissoes"})
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String nome;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false)
    private String senha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.CLIENTE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StatusUsuario status = StatusUsuario.ATIVO;

    @Column(name = "ultimo_acesso")
    private LocalDateTime ultimoAcesso;

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

    @Column(name = "token_reset_senha")
    private String tokenResetSenha;

    @Column(name = "token_reset_senha_expiracao")
    private LocalDateTime tokenResetSenhaExpiracao;

    /** Subject do Google OAuth; unicidade garantida por índice no Flyway (V016). */
    @Column(name = "google_sub", length = 255)
    private String googleSub;

    /** BCrypt do código numérico (6 dígitos) enviado por e-mail na recuperação de senha. */
    @Column(name = "reset_senha_codigo_hash", length = 255)
    private String resetSenhaCodigoHash;

    /** BCrypt do código de 6 dígitos para alterar senha estando logado (Meu perfil). */
    @Column(name = "alteracao_senha_codigo_hash", length = 255)
    private String alteracaoSenhaCodigoHash;

    @Column(name = "alteracao_senha_codigo_expiracao")
    private LocalDateTime alteracaoSenhaCodigoExpiracao;

    @Column(name = "omie_app_key", length = 100)
    private String omieAppKey;

    @Column(name = "omie_app_secret", length = 200)
    private String omieAppSecret;

    @OneToMany(mappedBy = "usuario", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    @org.hibernate.annotations.BatchSize(size = 20)
    private Set<Permissao> permissoes = new HashSet<>();

    /**
     * Relação many-to-one: um usuário tem acesso a múltiplas empresas do BOMControle
     */
    @OneToMany(mappedBy = "usuario", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    @org.hibernate.annotations.BatchSize(size = 30)
    private Set<EmpresaUsuario> empresas = new HashSet<>();

    /**
     * Enum para roles do usuário
     */
    public enum Role {
        ADMIN,
        CLIENTE
    }

    /**
     * Enum para status do usuário
     */
    public enum StatusUsuario {
        ATIVO,
        INATIVO
    }

    /**
     * Adiciona uma permissão ao usuário
     */
    public void adicionarPermissao(Permissao permissao) {
        this.permissoes.add(permissao);
        permissao.setUsuario(this);
    }

    /**
     * Remove uma permissão do usuário
     */
    public void removerPermissao(Permissao permissao) {
        this.permissoes.remove(permissao);
        permissao.setUsuario(null);
    }

    /**
     * Adiciona acesso a uma empresa
     */
    public void adicionarEmpresa(EmpresaUsuario empresa) {
        if (empresa != null) {
            this.empresas.add(empresa);
            empresa.setUsuario(this);
        }
    }

    /**
     * Remove acesso a uma empresa
     */
    public void removerEmpresa(EmpresaUsuario empresa) {
        if (empresa != null) {
            this.empresas.remove(empresa);
            empresa.setUsuario(null);
        }
    }

    /**
     * Verifica se o usuário tem acesso a uma empresa específica
     */
    public boolean temAcesso(Integer idEmpresa) {
        if (idEmpresa == null || idEmpresa <= 0) {
            return false;
        }
        return this.empresas.stream()
                .anyMatch(eu -> eu.getIdEmpresa().equals(idEmpresa) && eu.getAtivo());
    }

    /**
     * Obtém a empresa padrão do usuário
     */
    public EmpresaUsuario obterEmpresaPadrao() {
        return this.empresas.stream()
                .filter(eu -> eu.getPadrao() && eu.getAtivo())
                .findFirst()
                .orElse(null);
    }

    /**
     * Verifica se o usuário tem ao menos uma empresa ativa
     */
    public boolean temEmpresasAtivas() {
        return this.empresas.stream()
                .anyMatch(EmpresaUsuario::getAtivo);
    }

    /**
     * Verifica se o usuário está ativo
     */
    public boolean isAtivo() {
        return this.status == StatusUsuario.ATIVO;
    }

    /**
     * Verifica se o usuário é admin
     */
    public boolean isAdmin() {
        return this.role == Role.ADMIN;
    }

    /**
     * Atualiza o último acesso
     */
    public void atualizarUltimoAcesso() {
        this.ultimoAcesso = LocalDateTime.now();
    }

    /**
     * Realiza soft delete do usuário
     */
    public void softDelete() {
        this.deleted = true;
        this.dataExclusao = LocalDateTime.now();
    }

    /**
     * Restaura um usuário deletado
     */
    public void restaurar() {
        this.deleted = false;
        this.dataExclusao = null;
    }

    /**
     * Verifica se o usuário foi deletado
     */
    public boolean isDeleted() {
        return Boolean.TRUE.equals(this.deleted);
    }
}

