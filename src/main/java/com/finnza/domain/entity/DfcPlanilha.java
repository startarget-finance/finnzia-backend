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
 * Planilha manual de DFC (meses + linhas editáveis) por empresa Bom Controle.
 */
@Entity
@Table(
        name = "dfc_planilha",
        uniqueConstraints = @UniqueConstraint(name = "uk_dfc_planilha_id_empresa", columnNames = "id_empresa"),
        indexes = @Index(name = "idx_dfc_planilha_id_empresa", columnList = "id_empresa")
)
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DfcPlanilha {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_empresa", nullable = false, unique = true)
    private Integer idEmpresa;

    /** JSON array de strings (ex.: ["Jan/25","Fev/25"]) */
    @Column(name = "months_json", columnDefinition = "TEXT", nullable = false)
    private String monthsJson;

    /** JSON array de linhas (id, label, type, sign, values, titleIds) */
    @Column(name = "rows_json", columnDefinition = "TEXT", nullable = false)
    private String rowsJson;

    @CreatedDate
    @Column(name = "data_criacao", nullable = false, updatable = false)
    private LocalDateTime dataCriacao;

    @LastModifiedDate
    @Column(name = "data_atualizacao")
    private LocalDateTime dataAtualizacao;
}
