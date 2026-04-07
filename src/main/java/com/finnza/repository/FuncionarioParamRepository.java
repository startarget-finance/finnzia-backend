package com.finnza.repository;

import com.finnza.domain.entity.FuncionarioParam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FuncionarioParamRepository
        extends JpaRepository<FuncionarioParam, Long>, JpaSpecificationExecutor<FuncionarioParam> {

    @Query("SELECT f FROM FuncionarioParam f WHERE f.id = :id AND f.deleted = false")
    Optional<FuncionarioParam> findByIdNaoDeletado(@Param("id") Long id);

    @Query("SELECT f FROM FuncionarioParam f WHERE f.cpf = :cpf AND f.deleted = false AND f.cpf IS NOT NULL AND (:id IS NULL OR f.id <> :id)")
    Optional<FuncionarioParam> findOutroPorCpf(@Param("cpf") String cpf, @Param("id") Long id);
}
