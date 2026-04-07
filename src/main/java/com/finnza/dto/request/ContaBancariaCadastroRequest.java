package com.finnza.dto.request;

import com.finnza.domain.entity.ContaBancariaParam;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContaBancariaCadastroRequest {

    @Size(max = 200)
    private String nomeConta;

    private ContaBancariaParam.CategoriaConta categoria;

    @Size(max = 200)
    private String instituicao;

    @NotBlank
    @Size(max = 120)
    private String banco;

    @Size(max = 20)
    private String agencia;

    @Size(max = 30)
    private String conta;

    private ContaBancariaParam.TipoConta tipo;

    private BigDecimal saldoInicial;

    private Boolean ativo;

    private Set<Integer> idEmpresas;
}
