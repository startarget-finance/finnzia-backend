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
 * Configuração por empresa (id_empresa do Bom Controle).
 * Permite que cada empresa tenha sua própria API key do Asaas.
 */
@Entity
@Table(name = "empresa_config",
       uniqueConstraints = @UniqueConstraint(name = "uk_empresa_config_id_empresa", columnNames = "id_empresa"),
       indexes = @Index(name = "idx_empresa_config_id_empresa", columnList = "id_empresa"))
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmpresaConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * ID da empresa no Bom Controle (mesmo id usado em empresa_usuario).
     */
    @Column(name = "id_empresa", nullable = false, unique = true)
    private Integer idEmpresa;

    /**
     * API Key do Asaas para esta empresa. Se preenchido, as operações Asaas
     * feitas no contexto desta empresa usam esta chave em vez da global.
     */
    @Column(name = "asaas_api_key", length = 512)
    private String asaasApiKey;

    /**
     * URL base da API Asaas (opcional). Ex: https://sandbox.asaas.com/api/v3 ou https://api.asaas.com/v3
     */
    @Column(name = "asaas_base_url", length = 255)
    private String asaasBaseUrl;

    @Column(name = "cnpj", length = 14)
    private String cnpj;

    @Column(name = "razao_social", length = 255)
    private String razaoSocial;

    @Column(name = "nome_fantasia", length = 255)
    private String nomeFantasia;

    @Column(name = "email_empresa", length = 255)
    private String emailEmpresa;

    @Column(name = "telefone_empresa", length = 40)
    private String telefoneEmpresa;

    @CreatedDate
    @Column(name = "data_criacao", nullable = false, updatable = false)
    private LocalDateTime dataCriacao;

    @LastModifiedDate
    @Column(name = "data_atualizacao")
    private LocalDateTime dataAtualizacao;
}
