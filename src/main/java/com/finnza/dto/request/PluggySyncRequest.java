package com.finnza.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;

@Data
public class PluggySyncRequest {

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dataInicio;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dataFim;

    /** Id da conta bancária cadastrada no Finnza (opcional). */
    private Integer idContaBancaria;

    /** Nome exibido na movimentação (opcional). */
    private String nomeContaExibicao;
}
