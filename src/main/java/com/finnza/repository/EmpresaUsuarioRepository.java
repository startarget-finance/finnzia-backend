package com.finnza.repository;

import com.finnza.domain.entity.EmpresaUsuario;
import com.finnza.domain.entity.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository para gerenciamento de permissões de empresa por usuário
 */
@Repository
public interface EmpresaUsuarioRepository extends JpaRepository<EmpresaUsuario, Long> {

    /**
     * Encontra todas as empresas de um usuário (ativas)
     */
    @Query("SELECT eu FROM EmpresaUsuario eu WHERE eu.usuario.id = :usuarioId AND eu.ativo = true ORDER BY eu.padrao DESC, eu.nomeEmpresa ASC")
    List<EmpresaUsuario> findAllByUsuarioId(@Param("usuarioId") Long usuarioId);

    /**
     * Encontra todas as empresas (incluindo inativas) para auditoria
     */
    @Query("SELECT eu FROM EmpresaUsuario eu WHERE eu.usuario.id = :usuarioId ORDER BY eu.ativo DESC, eu.padrao DESC")
    List<EmpresaUsuario> findAllByUsuarioIdIncludingInactive(@Param("usuarioId") Long usuarioId);

    /**
     * Verifica se usuário tem acesso a uma empresa específica
     */
    @Query("SELECT CASE WHEN COUNT(eu) > 0 THEN true ELSE false END FROM EmpresaUsuario eu " +
           "WHERE eu.usuario.id = :usuarioId AND eu.idEmpresa = :idEmpresa AND eu.ativo = true")
    boolean temAcesso(@Param("usuarioId") Long usuarioId, @Param("idEmpresa") Integer idEmpresa);

    /**
     * Encontra empresa específica de um usuário (ativa)
     */
    @Query("SELECT eu FROM EmpresaUsuario eu WHERE eu.usuario.id = :usuarioId AND eu.idEmpresa = :idEmpresa AND eu.ativo = true")
    Optional<EmpresaUsuario> findByUsuarioIdAndIdEmpresa(@Param("usuarioId") Long usuarioId, @Param("idEmpresa") Integer idEmpresa);

    /**
     * Encontra a empresa padrão do usuário
     */
    @Query("SELECT eu FROM EmpresaUsuario eu WHERE eu.usuario.id = :usuarioId AND eu.padrao = true AND eu.ativo = true")
    Optional<EmpresaUsuario> findEmpresaPadraoByUsuarioId(@Param("usuarioId") Long usuarioId);

    /**
     * Encontra todas as empresas padrão de um usuário (não deve haver mais de 1, mas query safe)
     */
    @Query("SELECT eu FROM EmpresaUsuario eu WHERE eu.usuario.id = :usuarioId AND eu.padrao = true")
    List<EmpresaUsuario> findAllEmpresasPadraoByUsuarioId(@Param("usuarioId") Long usuarioId);

    /**
     * Conta quantas empresas ativas um usuário tem
     */
    @Query("SELECT COUNT(eu) FROM EmpresaUsuario eu WHERE eu.usuario.id = :usuarioId AND eu.ativo = true")
    long countAtivasByUsuarioId(@Param("usuarioId") Long usuarioId);

    /**
     * Remove suavemente todas as empresas de um usuário (soft delete)
     */
    @Modifying
    @Query("UPDATE EmpresaUsuario eu SET eu.ativo = false, eu.removidoPor = :removidoPor, " +
           "eu.motivoRemocao = :motivo, eu.dataRemocao = CURRENT_TIMESTAMP " +
           "WHERE eu.usuario.id = :usuarioId AND eu.ativo = true")
    int desativarTodasEmpresasDoUsuario(@Param("usuarioId") Long usuarioId, 
                                         @Param("removidoPor") String removidoPor,
                                         @Param("motivo") String motivo);

    /**
     * Remove acesso a uma empresa específica (soft delete)
     */
    @Modifying
    @Query("UPDATE EmpresaUsuario eu SET eu.ativo = false, eu.removidoPor = :removidoPor, " +
           "eu.motivoRemocao = :motivo, eu.dataRemocao = CURRENT_TIMESTAMP, " +
           "eu.padrao = false " +
           "WHERE eu.usuario.id = :usuarioId AND eu.idEmpresa = :idEmpresa")
    int removerAcessoEmpresa(@Param("usuarioId") Long usuarioId,
                            @Param("idEmpresa") Integer idEmpresa,
                            @Param("removidoPor") String removidoPor,
                            @Param("motivo") String motivo);

    /**
     * Remove hard delete (CUIDADO: use apenas em casos específicos)
     */
    @Modifying
    @Query("DELETE FROM EmpresaUsuario eu WHERE eu.usuario.id = :usuarioId AND eu.idEmpresa = :idEmpresa")
    int deletarAcessoEmpresa(@Param("usuarioId") Long usuarioId, @Param("idEmpresa") Integer idEmpresa);

    /**
     * Encontra um usuário e sua empresa específica (loading completo)
     */
    @Query("SELECT eu FROM EmpresaUsuario eu JOIN FETCH eu.usuario WHERE eu.usuario.id = :usuarioId AND eu.idEmpresa = :idEmpresa")
    Optional<EmpresaUsuario> findWithUsuarioByUsuarioIdAndIdEmpresa(@Param("usuarioId") Long usuarioId, @Param("idEmpresa") Integer idEmpresa);

    /**
     * Encontra empresa específica de um usuário (ativa ou inativa)
     * Usado para restaurar acesso a empresas previamente removidas
     */
    @Query("SELECT eu FROM EmpresaUsuario eu WHERE eu.usuario.id = :usuarioId AND eu.idEmpresa = :idEmpresa")
    Optional<EmpresaUsuario> findByUsuarioIdAndIdEmpresaIncludingInactive(@Param("usuarioId") Long usuarioId, @Param("idEmpresa") Integer idEmpresa);

    /**
     * Verifica se existe alguma empresa ativa para determinado usuário
     */
    @Query("SELECT CASE WHEN COUNT(eu) > 0 THEN true ELSE false END FROM EmpresaUsuario eu WHERE eu.usuario.id = :usuarioId AND eu.ativo = true")
    boolean usuarioTemEmpresasAtivas(@Param("usuarioId") Long usuarioId);

    /**
     * Retorna todos os idEmpresa distintos que têm pelo menos um usuário ativo.
     * Usado pelo sync automático para descobrir quais empresas precisam de dados.
     */
    @Query("SELECT DISTINCT eu.idEmpresa FROM EmpresaUsuario eu WHERE eu.ativo = true ORDER BY eu.idEmpresa ASC")
    List<Integer> findAllActiveEmpresaIds();

    /** Qualquer vínculo ativo com esse idEmpresa (para exibir nome em cadastros internos). */
    Optional<EmpresaUsuario> findFirstByIdEmpresaAndAtivoTrueOrderByIdAsc(Integer idEmpresa);

    /**
     * Nome amigável por {@code id_empresa} vindo da tabela {@code empresa_usuario} (PostgreSQL).
     * Usado para painel admin / listagens onde o nome na movimentação é só placeholder ("Empresa 1").
     */
    @Query("""
           SELECT eu.idEmpresa, MAX(eu.nomeEmpresa)
           FROM EmpresaUsuario eu
           WHERE eu.ativo = true
             AND eu.nomeEmpresa IS NOT NULL
             AND eu.nomeEmpresa <> ''
           GROUP BY eu.idEmpresa
           """)
    List<Object[]> findNomesEmpresaCadastroAtivos();
}
