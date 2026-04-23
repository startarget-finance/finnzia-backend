package com.finnza.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "cartoes_credito_empresa")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartaoCreditoEmpresa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_empresa", nullable = false)
    private Integer idEmpresa;

    @Column(name = "nome", nullable = false, length = 120)
    private String nome;

    @Column(name = "bandeira", length = 40)
    private String bandeira;

    @Column(name = "final_cartao", length = 4)
    private String finalCartao;

    @Column(name = "limite", precision = 15, scale = 2)
    private BigDecimal limite;

    @Column(name = "dia_fechamento")
    private Integer diaFechamento;

    @Column(name = "dia_vencimento")
    private Integer diaVencimento;

    @Column(name = "conta_referencia", length = 120)
    private String contaReferencia;

    @Column(name = "ativo", nullable = false)
    private Boolean ativo;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
