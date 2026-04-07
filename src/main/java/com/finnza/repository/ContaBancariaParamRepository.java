package com.finnza.repository;

import com.finnza.domain.entity.ContaBancariaParam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ContaBancariaParamRepository
        extends JpaRepository<ContaBancariaParam, Long>, JpaSpecificationExecutor<ContaBancariaParam> {

    @Query("SELECT c FROM ContaBancariaParam c WHERE c.id = :id AND c.deleted = false")
    Optional<ContaBancariaParam> findByIdNaoDeletado(@Param("id") Long id);
}
