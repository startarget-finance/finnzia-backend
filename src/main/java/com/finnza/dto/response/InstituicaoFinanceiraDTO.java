package com.finnza.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstituicaoFinanceiraDTO {
    private Long id;
    private String codigo;
    private String banco;
    private String instituicao;
    private String grupo;
    private Boolean popular;
}
