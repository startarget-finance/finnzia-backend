package com.finnza.repository;

import com.finnza.domain.entity.CategoriaFinanceiraEmpresa;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoriaFinanceiraEmpresaRepository extends JpaRepository<CategoriaFinanceiraEmpresa, Long> {

    List<CategoriaFinanceiraEmpresa> findAllByDeletedFalseAndIdEmpresaOrderByTipoAscNomeCategoriaAscNomeSubcategoriaAsc(Integer idEmpresa);

    Optional<CategoriaFinanceiraEmpresa> findByIdAndDeletedFalse(Long id);

    Optional<CategoriaFinanceiraEmpresa> findFirstByDeletedFalseAndIdEmpresaAndTipoAndNomeCategoriaIgnoreCaseAndNomeSubcategoriaIgnoreCase(
            Integer idEmpresa,
            CategoriaFinanceiraEmpresa.TipoCategoria tipo,
            String nomeCategoria,
            String nomeSubcategoria
    );

    Optional<CategoriaFinanceiraEmpresa> findFirstByDeletedFalseAndIdEmpresaAndTipoAndNomeCategoriaIgnoreCaseAndNomeSubcategoriaIsNull(
            Integer idEmpresa,
            CategoriaFinanceiraEmpresa.TipoCategoria tipo,
            String nomeCategoria
    );
}
