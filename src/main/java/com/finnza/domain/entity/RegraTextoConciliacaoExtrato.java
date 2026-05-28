package com.finnza.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "regra_texto_conciliacao_extrato",
        indexes = {
                @Index(name = "idx_regra_texto_empresa", columnList = "id_empresa"),
                @Index(name = "idx_regra_texto_empresa_cartao", columnList = "id_empresa,cartao_id")
        }
)
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegraTextoConciliacaoExtrato {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_empresa", nullable = false)
    private Integer idEmpresa;

    /** null = vale para todos os cartões da empresa */
    @Column(name = "cartao_id")
    private Long cartaoId;

    @Column(name = "texto_contem", nullable = false, length = 255)
    private String textoContem;

    @Column(name = "categoria", nullable = false, length = 120)
    private String categoria;

    /** debito | credito | null = ambos */
    @Column(name = "tipo_movimento", length = 12)
    private String tipoMovimento;

    @Column(name = "ativo", nullable = false)
    @Builder.Default
    private Boolean ativo = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
