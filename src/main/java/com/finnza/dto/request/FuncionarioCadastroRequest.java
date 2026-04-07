package com.finnza.dto.request;

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
public class FuncionarioCadastroRequest {

    @NotBlank
    @Size(max = 200)
    private String nomeCompleto;

    @Size(max = 14)
    private String cpf;

    @Size(max = 120)
    private String cargo;

    @Size(max = 120)
    private String departamento;

    @Size(max = 100)
    private String email;

    @Size(max = 20)
    private String telefone;

    private Boolean ativo;

    private Set<Integer> idEmpresas;
}
