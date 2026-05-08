package com.finnza.repository;

import com.finnza.domain.entity.CategoriaFinanceiraEmpresa;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoriaFinanceiraEmpresaRepository extends JpaRepository<CategoriaFinanceiraEmpresa, Long> {

    List<CategoriaFinanceiraEmpresa> findAllByDeletedFalseAndIdEmpresaOrderByTipoAscParentIdAscOrdemAscNomeAsc(Integer idEmpresa);

    Optional<CategoriaFinanceiraEmpresa> findByIdAndDeletedFalse(Long id);

    Optional<CategoriaFinanceiraEmpresa> findFirstByDeletedFalseAndIdEmpresaAndTipoAndParentIdIsNullAndNomeIgnoreCase(
            Integer idEmpresa,
            CategoriaFinanceiraEmpresa.TipoCategoria tipo,
            String nome
    );

    Optional<CategoriaFinanceiraEmpresa> findFirstByDeletedFalseAndIdEmpresaAndTipoAndParentIdAndNomeIgnoreCase(
            Integer idEmpresa,
            CategoriaFinanceiraEmpresa.TipoCategoria tipo,
            Long parentId,
            String nome
    );

    @Query("""
            SELECT COALESCE(MAX(c.ordem), -1)
            FROM CategoriaFinanceiraEmpresa c
            WHERE c.deleted = false
              AND c.idEmpresa = :idEmpresa
              AND c.tipo = :tipo
              AND c.parentId IS NULL
            """)
    Integer findMaxOrdemRaiz(
            @Param("idEmpresa") Integer idEmpresa,
            @Param("tipo") CategoriaFinanceiraEmpresa.TipoCategoria tipo
    );

    @Query("""
            SELECT COALESCE(MAX(c.ordem), -1)
            FROM CategoriaFinanceiraEmpresa c
            WHERE c.deleted = false
              AND c.idEmpresa = :idEmpresa
              AND c.tipo = :tipo
              AND c.parentId = :parentId
            """)
    Integer findMaxOrdemFilho(
            @Param("idEmpresa") Integer idEmpresa,
            @Param("tipo") CategoriaFinanceiraEmpresa.TipoCategoria tipo,
            @Param("parentId") Long parentId
    );

    @Query("""
            SELECT c FROM CategoriaFinanceiraEmpresa c
            WHERE c.deleted = false
              AND c.idEmpresa = :idEmpresa
              AND c.tipo = :tipo
              AND c.parentId IS NULL
              AND LOWER(c.nome) = LOWER(:nome)
            """)
    Optional<CategoriaFinanceiraEmpresa> findRootByNome(
            @Param("idEmpresa") Integer idEmpresa,
            @Param("tipo") CategoriaFinanceiraEmpresa.TipoCategoria tipo,
            @Param("nome") String nome
    );
}
