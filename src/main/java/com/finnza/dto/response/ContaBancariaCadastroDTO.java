package com.finnza.dto.response;

import com.finnza.domain.entity.ContaBancariaParam;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContaBancariaCadastroDTO {
    private Long id;
    private String nomeConta;
    private ContaBancariaParam.CategoriaConta categoria;
    private String instituicao;
    private String banco;
    private String agencia;
    private String conta;
    private ContaBancariaParam.TipoConta tipo;
    private BigDecimal saldoInicial;
    private Boolean ativo;
    private Set<Integer> idEmpresas;
    private Set<PlanoContasEmpresaNomeDTO> empresas;
    private LocalDateTime dataCriacao;
    private LocalDateTime dataAtualizacao;
}
