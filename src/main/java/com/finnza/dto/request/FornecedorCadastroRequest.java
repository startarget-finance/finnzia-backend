package com.finnza.dto.request;

import com.finnza.domain.entity.Cliente;
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
public class FornecedorCadastroRequest {

    @NotBlank
    @Size(max = 200)
    private String razaoSocial;

    @Size(max = 200)
    private String nomeFantasia;

    @Size(max = 20)
    private String cpfCnpj;

    private Cliente.TipoPessoa tipoPessoa;

    @Size(max = 100)
    private String email;

    @Size(max = 20)
    private String telefone;

    private Boolean ativo;

    private Set<Integer> idEmpresas;
}
