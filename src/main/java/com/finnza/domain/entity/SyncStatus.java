package com.finnza.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Controla o status de sincronização por período (mês) e empresa.
 * Permite saber quais períodos já foram carregados do Bom Controle para o banco local,
 * evitando re-sincronizações desnecessárias e dando visibilidade ao usuário.
 */
@Entity
@Table(name = "bc_sync_status")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Período no formato "yyyy-MM", ex: "2024-01" */
    @Column(name = "periodo", nullable = false, length = 10)
    private String periodo;

    @Column(name = "id_empresa", nullable = false)
    private Integer idEmpresa;

    /**
     * Chave única: periodo + "_" + idEmpresa.
     * Ex: "2024-01_6"
     */
    @Column(name = "periodo_empresa_key", unique = true, nullable = false, length = 50)
    private String periodoEmpresaKey;

    @Column(name = "ultima_sync")
    private LocalDateTime ultimaSync;

    /**
     * Status atual: "pendente" | "sincronizando" | "completo" | "erro"
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "total_registros")
    private Integer totalRegistros;

    @Column(name = "mensagem_erro", columnDefinition = "TEXT")
    private String mensagemErro;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
