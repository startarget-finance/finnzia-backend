package com.finnza.dto.request;

import com.finnza.domain.entity.Cliente;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClienteCadastroRequest {

    @NotBlank
    @Size(max = 200)
    private String razaoSocial;

    @Size(max = 200)
    private String nomeFantasia;

    @Size(max = 20)
    private String cpfCnpj;

    private Cliente.TipoPessoa tipoPessoa;

    @Min(1)
    @Max(5)
    private Integer classificacao;

    private Set<Integer> idEmpresas;

    @Size(max = 500)
    private String enderecoCompleto;

    @Size(max = 10)
    private String cep;

    @Size(max = 20)
    private String celularFinanceiro;

    @Size(max = 100)
    private String emailFinanceiro;

    @Size(max = 100)
    private String responsavel;

    @Size(max = 20)
    private String cpf;

    private Boolean bloqueado;
}
