package com.finnza.repository;

import com.finnza.domain.entity.Cobranca;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository para Cobranca
 */
@Repository
public interface CobrancaRepository extends JpaRepository<Cobranca, Long> {

    /**
     * Busca cobrança por ID do Asaas
     */
    @Query("SELECT c FROM Cobranca c WHERE c.asaasPaymentId = :asaasPaymentId")
    Optional<Cobranca> findByAsaasPaymentId(@Param("asaasPaymentId") String asaasPaymentId);

    /**
     * Busca cobranças por contrato
     */
    @Query("SELECT c FROM Cobranca c WHERE c.contrato.id = :contratoId ORDER BY c.dataVencimento ASC")
    java.util.List<Cobranca> findByContratoId(@Param("contratoId") Long contratoId);
}

