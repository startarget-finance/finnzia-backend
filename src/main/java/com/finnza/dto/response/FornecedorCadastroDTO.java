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
public class FornecedorCadastroDTO {
    private Long id;
    private String razaoSocial;
    private String nomeFantasia;
    private String cpfCnpj;
    private Cliente.TipoPessoa tipoPessoa;
    private String email;
    private String telefone;
    private Boolean ativo;
    private Set<Integer> idEmpresas;
    private Set<PlanoContasEmpresaNomeDTO> empresas;
    private LocalDateTime dataCriacao;
    private LocalDateTime dataAtualizacao;
}
