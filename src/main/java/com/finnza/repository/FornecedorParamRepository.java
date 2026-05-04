package com.finnza.repository;

import com.finnza.domain.entity.FornecedorParam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FornecedorParamRepository
        extends JpaRepository<FornecedorParam, Long>, JpaSpecificationExecutor<FornecedorParam> {

    @Query("SELECT f FROM FornecedorParam f WHERE f.id = :id AND f.deleted = false")
    Optional<FornecedorParam> findByIdNaoDeletado(@Param("id") Long id);

    @Query("SELECT f FROM FornecedorParam f WHERE f.cpfCnpj = :cpf AND f.deleted = false AND f.cpfCnpj IS NOT NULL AND (:id IS NULL OR f.id <> :id)")
    Optional<FornecedorParam> findOutroPorCpfCnpj(@Param("cpf") String cpf, @Param("id") Long id);

    @Query("""
            SELECT f
            FROM FornecedorParam f
            JOIN f.idEmpresas e
            WHERE f.deleted = false
              AND e = :idEmpresa
              AND (
                    LOWER(f.razaoSocial) = LOWER(:nome)
                 OR LOWER(f.nomeFantasia) = LOWER(:nome)
              )
            ORDER BY f.id ASC
            """)
    List<FornecedorParam> findByNomeNaEmpresa(@Param("idEmpresa") Integer idEmpresa, @Param("nome") String nome);
}
