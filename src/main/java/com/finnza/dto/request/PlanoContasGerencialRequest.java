package com.finnza.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanoContasGerencialRequest {

    @NotBlank
    @Size(max = 160)
    private String nome;

    @Builder.Default
    private Set<Integer> idEmpresas = new HashSet<>();

    private Boolean padrao;
}
