package com.finnza.dto.request;

import com.finnza.domain.entity.Usuario;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO para atualização de usuário
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AtualizarUsuarioRequest {

    @Size(min = 3, max = 100, message = "Nome deve ter entre 3 e 100 caracteres")
    private String nome;

    @Email(message = "Email deve ser válido")
    private String email;

    private Usuario.Role role;
    
    private Usuario.StatusUsuario status;

    @Size(max = 100, message = "OMIE App Key deve ter no máximo 100 caracteres")
    private String omieAppKey;

    @Size(max = 200, message = "OMIE App Secret deve ter no máximo 200 caracteres")
    private String omieAppSecret;
}

