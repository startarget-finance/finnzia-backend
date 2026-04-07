package com.finnza.dto.response;

import com.finnza.domain.entity.Cliente;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClienteCadastroDTO {
    private Long id;
    private String razaoSocial;
    private String nomeFantasia;
    private String cpfCnpj;
    private Cliente.TipoPessoa tipoPessoa;
    private Integer classificacao;
    private Boolean bloqueado;
    private String celularFinanceiro;
    private String emailFinanceiro;
    private String enderecoCompleto;
    private String cep;
    private String responsavel;
    private String cpf;
    private Set<Integer> idEmpresas;
    private Set<PlanoContasEmpresaNomeDTO> empresas;
    private LocalDateTime dataCriacao;
    private LocalDateTime dataAtualizacao;
}
