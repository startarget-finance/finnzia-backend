package com.finnza.dto.response;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDate;

@Value
@Builder
public class PluggySyncResponse {
    long totalPluggy;
    int importadas;
    int ignoradasDuplicadas;
    Long importacaoId;
    String conta;
    LocalDate periodoInicio;
    LocalDate periodoFim;
}
