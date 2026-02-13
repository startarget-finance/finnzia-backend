package com.finnza.dto.request;

import com.finnza.domain.entity.Contrato;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DTO para criação de contrato
 */
@Data
public class CriarContratoRequest {

    @NotBlank(message = "Título é obrigatório")
    @Size(max = 200, message = "Título deve ter no máximo 200 caracteres")
    private String titulo;

    @Size(max = 1000, message = "Descrição deve ter no máximo 1000 caracteres")
    private String descricao;

    private String conteudo;

    @NotNull(message = "Dados do cliente são obrigatórios")
    @Valid
    private DadosClienteRequest dadosCliente;

    @NotNull(message = "Valor do contrato é obrigatório")
    @DecimalMin(value = "0.01", message = "Valor deve ser maior que zero")
    @Digits(integer = 13, fraction = 2, message = "Valor inválido")
    private BigDecimal valorContrato;

    @DecimalMin(value = "0.01", message = "Valor de recorrência deve ser maior que zero")
    @Digits(integer = 13, fraction = 2, message = "Valor de recorrência inválido")
    private BigDecimal valorRecorrencia;

    @NotNull(message = "Data de vencimento é obrigatória")
    @Future(message = "Data de vencimento deve ser futura")
    private LocalDate dataVencimento;

    @NotNull(message = "Tipo de pagamento é obrigatório")
    private Contrato.TipoPagamento tipoPagamento;

    @Size(max = 50, message = "Serviço deve ter no máximo 50 caracteres")
    private String servico;

    private LocalDate inicioContrato;

    private LocalDate inicioRecorrencia;

    @Pattern(regexp = "^\\+?[1-9]\\d{1,14}$", message = "WhatsApp inválido")
    private String whatsapp;

    // Novos campos adicionais
    private LocalDate dataVenda;

    private LocalDate dataEncerramento;

    @Size(max = 500, message = "Link contrato deve ter no máximo 500 caracteres")
    private String linkContrato;

    private Contrato.StatusAssinatura statusAssinatura;

    @Size(max = 100, message = "Projeto deve ter no máximo 100 caracteres")
    private String projeto;

    @DecimalMin(value = "0.0", message = "Valor entrada não pode ser negativo")
    @Digits(integer = 13, fraction = 2, message = "Valor entrada inválido")
    private BigDecimal valorEntrada;

    // Configurações de pagamento do Asaas
    private String formaPagamento; // BOLETO, PIX, CREDIT_CARD, DEBIT_CARD
    
    // Juros e multa
    @DecimalMin(value = "0.0", message = "Juros não pode ser negativo")
    @Digits(integer = 5, fraction = 2, message = "Juros inválido")
    private BigDecimal jurosAoMes; // Percentual de juros ao mês
    
    @DecimalMin(value = "0.0", message = "Multa não pode ser negativa")
    @Digits(integer = 5, fraction = 2, message = "Multa inválida")
    private BigDecimal multaPorAtraso; // Percentual de multa por atraso
    
    // Desconto
    @DecimalMin(value = "0.0", message = "Desconto não pode ser negativo")
    @Digits(integer = 5, fraction = 2, message = "Desconto inválido")
    private BigDecimal descontoPercentual; // Percentual de desconto
    
    @DecimalMin(value = "0.0", message = "Desconto não pode ser negativo")
    @Digits(integer = 13, fraction = 2, message = "Desconto inválido")
    private BigDecimal descontoValorFixo; // Valor fixo de desconto
    
    private Integer prazoMaximoDesconto; // Dias para aplicar desconto
    
    // Parcelas (apenas para contratos únicos)
    @Min(value = 1, message = "Número de parcelas deve ser pelo menos 1")
    @Max(value = 12, message = "Número de parcelas não pode ser maior que 12")
    private Integer numeroParcelas; // Número de parcelas (1 = à vista)

    /**
     * Dados do cliente (pode ser novo ou existente)
     */
    @Data
    public static class DadosClienteRequest {
        private Long clienteId; // Se já existir

        @NotBlank(message = "Razão social é obrigatória")
        @Size(max = 200, message = "Razão social deve ter no máximo 200 caracteres")
        private String razaoSocial;

        @Size(max = 200, message = "Nome fantasia deve ter no máximo 200 caracteres")
        private String nomeFantasia;

        @NotBlank(message = "CPF/CNPJ é obrigatório")
        @Size(max = 20, message = "CPF/CNPJ deve ter no máximo 20 caracteres")
        private String cpfCnpj;

        @Size(max = 500, message = "Endereço deve ter no máximo 500 caracteres")
        private String enderecoCompleto;

        @Size(max = 10, message = "CEP deve ter no máximo 10 caracteres")
        private String cep;

        @Size(max = 20, message = "Celular deve ter no máximo 20 caracteres")
        private String celularFinanceiro;

        @Email(message = "Email inválido")
        @Size(max = 100, message = "Email deve ter no máximo 100 caracteres")
        private String emailFinanceiro;

        @Size(max = 100, message = "Responsável deve ter no máximo 100 caracteres")
        private String responsavel;

        @Size(max = 20, message = "CPF deve ter no máximo 20 caracteres")
        private String cpf;
    }
}

