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

/**
 * Entidade Cliente
 * Representa um cliente que pode ter contratos
 */
@Entity
@Table(name = "clientes",
       indexes = {
           @Index(name = "idx_cliente_cpf_cnpj", columnList = "cpfCnpj"),
           @Index(name = "idx_cliente_email", columnList = "emailFinanceiro"),
           @Index(name = "idx_cliente_asaas_id", columnList = "asaasCustomerId"),
           @Index(name = "idx_cliente_deleted", columnList = "deleted")
       })
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class Cliente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String razaoSocial;

    @Column(length = 200)
    private String nomeFantasia;

    @Column(nullable = false, length = 20)
    private String cpfCnpj;

    @Column(length = 500)
    private String enderecoCompleto;

    @Column(length = 10)
    private String cep;

    @Column(length = 20)
    private String celularFinanceiro;

    @Column(length = 100)
    private String emailFinanceiro;

    @Column(length = 100)
    private String responsavel;

    @Column(length = 20)
    private String cpf;

    // ID do cliente no Asaas (após criação)
    @Column(name = "asaas_customer_id", length = 50)
    private String asaasCustomerId;

    @CreatedDate
    @Column(name = "data_criacao", nullable = false, updatable = false)
    private LocalDateTime dataCriacao;

    @LastModifiedDate
    @Column(name = "data_atualizacao")
    private LocalDateTime dataAtualizacao;

    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private Boolean deleted = false;

    /**
     * Realiza soft delete do cliente
     */
    public void softDelete() {
        this.deleted = true;
    }

    /**
     * Restaura um cliente deletado
     */
    public void restaurar() {
        this.deleted = false;
    }
}

