package com.finnza.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AtualizarPlanoContasPadraoRequest {
    @NotNull(message = "arvore é obrigatória")
    private JsonNode arvore;
}
