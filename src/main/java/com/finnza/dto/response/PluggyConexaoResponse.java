package com.finnza.dto.response;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class PluggyConexaoResponse {
    Long id;
    String pluggyItemId;
    String connectorId;
    String connectorName;
    String status;
    LocalDateTime dataCriacao;
    LocalDateTime dataAtualizacao;
}
