package com.finnza.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.finnza.domain.entity.Permissao;
import com.finnza.domain.entity.Usuario;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DTO para resposta de usuário
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsuarioDTO {

    private Long id;
    
    @JsonProperty("name")
    private String nome;
    
    private String email;
    private String role;
    private String status;
    
    @JsonProperty("loginTime")
    private LocalDateTime ultimoAcesso;
    
    private LocalDateTime dataCriacao;
    private Map<String, Boolean> permissions;
    
    @JsonProperty("omieAppKey")
    private String omieAppKey;
    
    // Nota: omieAppSecret não é incluído no DTO por segurança

    /**
     * Converte entidade Usuario para DTO
     */
    public static UsuarioDTO fromEntity(Usuario usuario) {
        Map<String, Boolean> permissionsMap = new HashMap<>();
        
        if (usuario.getPermissoes() != null && !usuario.getPermissoes().isEmpty()) {
            permissionsMap = usuario.getPermissoes().stream()
                    .collect(Collectors.toMap(
                            p -> converterModuloParaChave(p.getModulo()),
                            Permissao::isHabilitada
                    ));
        } else {
            // Se não houver permissões específicas, criar permissões padrão baseadas no role
            permissionsMap = criarPermissoesPadrao(usuario.getRole());
        }

        return UsuarioDTO.builder()
                .id(usuario.getId())
                .nome(usuario.getNome())
                .email(usuario.getEmail())
                .role(usuario.getRole().name().toLowerCase())
                .status(usuario.getStatus().name().toLowerCase())
                .ultimoAcesso(usuario.getUltimoAcesso())
                .dataCriacao(usuario.getDataCriacao())
                .permissions(permissionsMap)
                .omieAppKey(usuario.getOmieAppKey())
                .build();
    }

    /**
     * Cria permissões padrão baseadas no role
     */
    private static Map<String, Boolean> criarPermissoesPadrao(Usuario.Role role) {
        Map<String, Boolean> permissions = new HashMap<>();
        
        if (role == Usuario.Role.ADMIN) {
            // Admin tem todas as permissões
            permissions.put("dashboard", true);
            permissions.put("relatorio", true);
            permissions.put("movimentacoes", true);
            permissions.put("fluxoCaixa", true);
            permissions.put("contratos", true);
            permissions.put("chat", true);
            permissions.put("assinatura", true);
            permissions.put("gerenciarAcessos", true);
        } else {
            // Cliente tem permissões limitadas
            permissions.put("dashboard", true);
            permissions.put("relatorio", true);
            permissions.put("movimentacoes", true);
            permissions.put("fluxoCaixa", true);
            permissions.put("contratos", true);
            permissions.put("chat", true);
            permissions.put("assinatura", true);
            permissions.put("gerenciarAcessos", false);
        }
        
        return permissions;
    }

    /**
     * Converte o módulo para a chave esperada pelo frontend (camelCase)
     */
    private static String converterModuloParaChave(Permissao.Modulo modulo) {
        switch (modulo) {
            case FLUXO_CAIXA:
                return "fluxoCaixa";
            case GERENCIAR_ACESSOS:
                return "gerenciarAcessos";
            default:
                return modulo.name().toLowerCase();
        }
    }
}

