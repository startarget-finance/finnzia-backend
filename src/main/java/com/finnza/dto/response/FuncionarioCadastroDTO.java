package com.finnza.dto.response;

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
public class FuncionarioCadastroDTO {
    private Long id;
    private String nomeCompleto;
    private String cpf;
    private String cargo;
    private String departamento;
    private String email;
    private String telefone;
    private Boolean ativo;
    private Set<Integer> idEmpresas;
    private Set<PlanoContasEmpresaNomeDTO> empresas;
    private LocalDateTime dataCriacao;
    private LocalDateTime dataAtualizacao;
}
