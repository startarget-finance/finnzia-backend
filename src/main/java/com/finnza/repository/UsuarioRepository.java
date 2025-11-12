package com.finnza.repository;

import com.finnza.domain.entity.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository para entidade Usuario
 */
@Repository
public interface UsuarioRepository extends JpaRepository<Usuario, Long>, JpaSpecificationExecutor<Usuario> {

    /**
     * Busca usuário por email (apenas não deletados)
     */
    @Query("SELECT u FROM Usuario u WHERE u.email = :email AND (u.deleted = false OR u.deleted IS NULL)")
    Optional<Usuario> findByEmail(@Param("email") String email);

    /**
     * Verifica se existe usuário com o email (apenas não deletados)
     */
    @Query("SELECT COUNT(u) > 0 FROM Usuario u WHERE u.email = :email AND (u.deleted = false OR u.deleted IS NULL)")
    boolean existsByEmail(@Param("email") String email);

    /**
     * Busca usuário por email com permissões carregadas (apenas não deletados)
     */
    @Query("SELECT u FROM Usuario u LEFT JOIN FETCH u.permissoes WHERE u.email = :email AND (u.deleted = false OR u.deleted IS NULL)")
    Optional<Usuario> findByEmailWithPermissoes(@Param("email") String email);

    /**
     * Busca usuário por ID com permissões carregadas (apenas não deletados)
     */
    @Query("SELECT u FROM Usuario u LEFT JOIN FETCH u.permissoes WHERE u.id = :id AND (u.deleted = false OR u.deleted IS NULL)")
    Optional<Usuario> findByIdWithPermissoes(@Param("id") Long id);

    /**
     * Busca usuário por token de reset de senha
     */
    @Query("SELECT u FROM Usuario u WHERE u.tokenResetSenha = :token AND (u.deleted = false OR u.deleted IS NULL)")
    Optional<Usuario> findByTokenResetSenha(@Param("token") String token);
}

