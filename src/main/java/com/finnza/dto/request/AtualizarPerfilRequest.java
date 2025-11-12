package com.finnza.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO para atualização do próprio perfil
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AtualizarPerfilRequest {

    @Size(min = 3, max = 100, message = "Nome deve ter entre 3 e 100 caracteres")
    private String nome;
}

