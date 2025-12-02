package com.finnza.security;

import com.finnza.domain.entity.Permissao;
import com.finnza.domain.entity.Usuario;
import com.finnza.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.Optional;

/**
 * Avaliador customizado de permissões para Spring Security
 * Permite usar @PreAuthorize("hasPermission(null, 'DASHBOARD')")
 */
@Component("customPermissionEvaluator")
public class CustomPermissionEvaluator implements PermissionEvaluator {

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        if (authentication == null || permission == null) {
            return false;
        }

        String email = authentication.getName();
        Optional<Usuario> usuarioOpt = usuarioRepository.findByEmailWithPermissoes(email);

        if (usuarioOpt.isEmpty()) {
            return false;
        }

        Usuario usuario = usuarioOpt.get();

        // Admin tem acesso a tudo
        if (usuario.getRole() == Usuario.Role.ADMIN) {
            return true;
        }

        // Verificar se o usuário tem a permissão específica
        String moduloStr = permission.toString();
        try {
            Permissao.Modulo modulo = Permissao.Modulo.valueOf(moduloStr);
            return usuario.getPermissoes().stream()
                    .anyMatch(p -> p.getModulo() == modulo && p.isHabilitada());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        return hasPermission(authentication, null, permission);
    }
}

