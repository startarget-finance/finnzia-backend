package com.finnza.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "ofx_importacoes")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfxImportacao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_empresa", nullable = false)
    private Integer idEmpresa;

    @Column(name = "nome_empresa")
    private String nomeEmpresa;

    @Column(name = "arquivo_nome")
    private String arquivoNome;

    @Column(name = "tipo", length = 20)
    private String tipo; // MANUAL | AUTOMATICO

    @Column(name = "status", length = 20)
    private String status; // CONCILIADO | PARCIAL | PENDENTE

    @Column(name = "data_importacao")
    private LocalDateTime dataImportacao;

    @Column(name = "banco")
    private String banco;

    @Column(name = "conta")
    private String conta;

    @Column(name = "periodo_inicio")
    private LocalDate periodoInicio;

    @Column(name = "periodo_fim")
    private LocalDate periodoFim;

    @Column(name = "total_conciliadas")
    private Integer totalConciliadas;

    @Column(name = "total_ignoradas")
    private Integer totalIgnoradas;

    @Column(name = "total_pendentes")
    private Integer totalPendentes;

    @Column(name = "total")
    private Integer total;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}

