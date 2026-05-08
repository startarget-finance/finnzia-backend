package com.finnza.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubcategoriaFinanceiraDTO {
    private Long id;
    private String nome;
    @Builder.Default
    private List<SubcategoriaFinanceiraDTO> children = new ArrayList<>();
}
