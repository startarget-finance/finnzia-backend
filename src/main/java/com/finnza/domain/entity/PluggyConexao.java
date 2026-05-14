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

@Entity
@Table(
        name = "pluggy_conexao",
        indexes = {
                @Index(name = "idx_pluggy_conexao_usuario", columnList = "usuario_id")
        },
        uniqueConstraints = @UniqueConstraint(name = "uk_pluggy_conexao_item", columnNames = "pluggy_item_id"))
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluggyConexao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(name = "pluggy_item_id", nullable = false, length = 64)
    private String pluggyItemId;

    @Column(name = "connector_id", length = 64)
    private String connectorId;

    @Column(name = "connector_name", length = 255)
    private String connectorName;

    @Column(name = "status", length = 64)
    private String status;

    @Column(name = "ultimo_evento", columnDefinition = "TEXT")
    private String ultimoEvento;

    @CreatedDate
    @Column(name = "data_criacao", nullable = false, updatable = false)
    private LocalDateTime dataCriacao;

    @LastModifiedDate
    @Column(name = "data_atualizacao")
    private LocalDateTime dataAtualizacao;
}
