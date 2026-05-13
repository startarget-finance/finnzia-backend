package com.finnza.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO para alteração de senha
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlterarSenhaRequest {

    @NotBlank(message = "Senha atual é obrigatória")
    private String senhaAtual;

    @NotBlank(message = "Nova senha é obrigatória")
    @Size(min = 6, message = "Nova senha deve ter no mínimo 6 caracteres")
    private String novaSenha;

    /** Código de 6 dígitos enviado por e-mail após "Enviar código" em Meu perfil. */
    @NotBlank(message = "Código de verificação é obrigatório")
    @Pattern(regexp = "^\\d{6}$", message = "Código deve ter exatamente 6 dígitos")
    private String codigo;
}

