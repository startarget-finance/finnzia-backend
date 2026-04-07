package com.finnza.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanoContasGerencialDTO {

    private Long id;
    private String nome;
    private Boolean padrao;
    @Builder.Default
    private Set<Integer> idEmpresas = new HashSet<>();
    @Builder.Default
    private Set<PlanoContasEmpresaNomeDTO> empresas = new HashSet<>();
    private LocalDateTime dataCriacao;
    private LocalDateTime dataAtualizacao;
}
