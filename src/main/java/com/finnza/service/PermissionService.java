package com.finnza.service;

import com.finnza.domain.entity.Permissao;
import com.finnza.domain.entity.Usuario;
import com.finnza.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Serviço para verificação de permissões
 */
@Service
public class PermissionService {

    @Autowired
    private UsuarioRepository usuarioRepository;

    /**
     * Verifica se o usuário autenticado tem uma permissão específica
     */
    public boolean hasPermission(Permissao.Modulo modulo) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }

        String email = authentication.getName();
        return usuarioRepository.findByEmailWithPermissoes(email)
                .map(usuario -> {
                    // Admin tem acesso a tudo
                    if (usuario.getRole() == Usuario.Role.ADMIN) {
                        return true;
                    }
                    // Verificar permissão específica
                    return usuario.getPermissoes().stream()
                            .anyMatch(p -> p.getModulo() == modulo && p.isHabilitada());
                })
                .orElse(false);
    }

    /**
     * Verifica se o usuário autenticado tem permissão de ADMIN
     */
    public boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }

        String email = authentication.getName();
        return usuarioRepository.findByEmail(email)
                .map(usuario -> usuario.getRole() == Usuario.Role.ADMIN)
                .orElse(false);
    }

    /**
     * Obtém o usuário autenticado
     */
    public Usuario getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }

        String email = authentication.getName();
        return usuarioRepository.findByEmailWithPermissoes(email)
                .orElse(null);
    }
}

