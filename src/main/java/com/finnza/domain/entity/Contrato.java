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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entidade Contrato
 * Representa um contrato com um cliente
 */
@Entity
@Table(name = "contratos",
       indexes = {
           @Index(name = "idx_contrato_cliente", columnList = "cliente_id"),
           @Index(name = "idx_contrato_status", columnList = "status"),
           @Index(name = "idx_contrato_data_vencimento", columnList = "dataVencimento"),
           @Index(name = "idx_contrato_deleted", columnList = "deleted"),
           @Index(name = "idx_contrato_cliente_status", columnList = "cliente_id,status")
       })
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = {"cliente", "cobrancas"})
public class Contrato {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String titulo;

    @Column(length = 1000)
    private String descricao;

    @Column(columnDefinition = "TEXT")
    private String conteudo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cliente_id", nullable = false)
    private Cliente cliente;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal valorContrato;

    @Column(precision = 15, scale = 2)
    private BigDecimal valorRecorrencia;

    @Column(nullable = false)
    private LocalDate dataVencimento;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StatusContrato status = StatusContrato.PENDENTE;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TipoPagamento tipoPagamento;

    @Column(length = 50)
    private String servico;

    @Column(name = "inicio_contrato")
    private LocalDate inicioContrato;

    @Column(name = "inicio_recorrencia")
    private LocalDate inicioRecorrencia;

    @Column(name = "whatsapp", length = 20)
    private String whatsapp;

    // ID da assinatura no Asaas (se for recorrente)
    @Column(name = "asaas_subscription_id", length = 50)
    private String asaasSubscriptionId;

    @OneToMany(mappedBy = "contrato", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    @org.hibernate.annotations.BatchSize(size = 20)
    private List<Cobranca> cobrancas = new ArrayList<>();

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
     * Enum para status do contrato
     */
    public enum StatusContrato {
        PENDENTE,
        EM_DIA,
        VENCIDO,
        PAGO,
        CANCELADO
    }

    /**
     * Enum para tipo de pagamento
     */
    public enum TipoPagamento {
        UNICO,
        RECORRENTE
    }

    /**
     * Adiciona uma cobrança ao contrato
     */
    public void adicionarCobranca(Cobranca cobranca) {
        this.cobrancas.add(cobranca);
        cobranca.setContrato(this);
    }

    /**
     * Calcula e atualiza o status do contrato baseado nas cobranças do Asaas
     * Segue a mesma lógica do frontend para manter consistência
     */
    public void calcularStatusBaseadoNasCobrancas() {
        if (this.cobrancas == null || this.cobrancas.isEmpty()) {
            // Se não tem cobranças, mantém o status atual ou define como PENDENTE
            if (this.status == null) {
                this.status = StatusContrato.PENDENTE;
            }
            return;
        }

        LocalDate hoje = LocalDate.now();
        
        // Verificar se todas as cobranças foram pagas
        boolean todasPagas = this.cobrancas.stream().allMatch(c -> 
            c.getStatus() == Cobranca.StatusCobranca.RECEIVED ||
            c.getStatus() == Cobranca.StatusCobranca.RECEIVED_IN_CASH_UNDONE ||
            c.getStatus() == Cobranca.StatusCobranca.DUNNING_RECEIVED
        );
        
        if (todasPagas) {
            this.status = StatusContrato.PAGO;
            return;
        }
        
        // Verificar se há cobranças vencidas (OVERDUE)
        boolean temVencidas = this.cobrancas.stream().anyMatch(c -> 
            c.getStatus() == Cobranca.StatusCobranca.OVERDUE ||
            c.getStatus() == Cobranca.StatusCobranca.DUNNING_REQUESTED ||
            c.getStatus() == Cobranca.StatusCobranca.CHARGEBACK_REQUESTED
        );
        
        if (temVencidas) {
            this.status = StatusContrato.VENCIDO;
            return;
        }
        
        // Verificar se há cobranças pendentes vencidas (qualquer dia vencido)
        boolean temPendentesVencidas = this.cobrancas.stream().anyMatch(c -> {
            if (c.getStatus() == Cobranca.StatusCobranca.PENDING && c.getDataVencimento() != null) {
                return c.getDataVencimento().isBefore(hoje);
            }
            return false;
        });
        
        if (temPendentesVencidas) {
            this.status = StatusContrato.VENCIDO;
            return;
        }
        
        // Se tem cobranças pendentes com data futura (e nenhuma vencida), está EM_DIA
        boolean temPendentesFuturas = this.cobrancas.stream().anyMatch(c -> 
            c.getStatus() == Cobranca.StatusCobranca.PENDING && 
            c.getDataVencimento() != null && 
            !c.getDataVencimento().isBefore(hoje)
        );
        
        if (temPendentesFuturas) {
            this.status = StatusContrato.EM_DIA;
            return;
        }
        
        // Se tem cobranças pendentes (sem data ou data passada já tratada acima como vencida)
        boolean temPendentes = this.cobrancas.stream().anyMatch(c -> 
            c.getStatus() == Cobranca.StatusCobranca.PENDING
        );
        
        if (temPendentes) {
            this.status = StatusContrato.EM_DIA;
            return;
        }
        
        // Se data de vencimento passou e não está pago, está VENCIDO
        if (this.dataVencimento != null && this.dataVencimento.isBefore(hoje) && 
            this.status != StatusContrato.PAGO) {
            this.status = StatusContrato.VENCIDO;
        }
    }

    /**
     * Realiza soft delete do contrato
     */
    public void softDelete() {
        this.deleted = true;
    }

    /**
     * Restaura um contrato deletado
     */
    public void restaurar() {
        this.deleted = false;
    }
}

