package com.finnza.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PlanoContasPadraoResponse {
    JsonNode arvore;
    LocalDateTime dataAtualizacao;
    String atualizadoPorEmail;
    boolean usandoPadraoEmbutido;
}
