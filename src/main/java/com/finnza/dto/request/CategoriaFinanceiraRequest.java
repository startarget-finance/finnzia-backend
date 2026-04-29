package com.finnza.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoriaFinanceiraRequest {

    @NotNull
    private Integer idEmpresa;

    @NotBlank
    @Size(max = 16)
    private String tipo; // receita | despesa

    @NotBlank
    @Size(max = 120)
    private String nomeCategoria;

    @Size(max = 120)
    private String nomeSubcategoria;
}
