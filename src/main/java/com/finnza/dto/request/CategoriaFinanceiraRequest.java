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

    /** Nome do novo nó (qualquer nível). */
    @Size(max = 120)
    private String nome;

    /** Pai na árvore; null = raiz (categoria de 1º nível). */
    private Long parentId;

    /** Legado (import / clientes antigos): mapeado para {@code nome} + raiz se {@code nome} vazio. */
    @Size(max = 120)
    private String nomeCategoria;

    @Size(max = 120)
    private String nomeSubcategoria;
}
