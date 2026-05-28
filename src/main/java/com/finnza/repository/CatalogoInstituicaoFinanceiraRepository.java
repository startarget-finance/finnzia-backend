package com.finnza.repository;

import com.finnza.domain.entity.CatalogoInstituicaoFinanceira;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CatalogoInstituicaoFinanceiraRepository extends JpaRepository<CatalogoInstituicaoFinanceira, Long> {

    @Query("""
            SELECT c
            FROM CatalogoInstituicaoFinanceira c
            WHERE c.ativo = true
              AND (
                   :q IS NULL OR :q = ''
                   OR LOWER(c.banco) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(c.instituicao) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(c.codigo, '')) LIKE LOWER(CONCAT('%', :q, '%'))
              )
            ORDER BY c.popular DESC, c.ordem ASC, c.banco ASC
            """)
    List<CatalogoInstituicaoFinanceira> buscarAtivas(@Param("q") String q);
}
