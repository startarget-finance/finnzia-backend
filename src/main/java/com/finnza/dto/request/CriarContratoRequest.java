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

