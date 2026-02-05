package com.finnza.repository;

import com.finnza.domain.entity.Contrato;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository para Contrato
 */
@Repository
public interface ContratoRepository extends JpaRepository<Contrato, Long> {

    /**
     * Lista contratos não deletados com paginação
     */
    @Query("SELECT c FROM Contrato c WHERE c.deleted = false")
    Page<Contrato> findAllNaoDeletados(Pageable pageable);
    
    /**
     * Lista todos os contratos não deletados (sem paginação)
     */
    @Query("SELECT c FROM Contrato c WHERE c.deleted = false")
    List<Contrato> findAllNaoDeletados();

    /**
     * Busca contratos por cliente (não deletados)
     */
    @Query("SELECT c FROM Contrato c WHERE c.cliente.id = :clienteId AND c.deleted = false")
    Page<Contrato> findByClienteId(@Param("clienteId") Long clienteId, Pageable pageable);

    /**
     * Busca contratos por status (não deletados)
     */
    @Query("SELECT c FROM Contrato c WHERE c.status = :status AND c.deleted = false")
    Page<Contrato> findByStatus(@Param("status") Contrato.StatusContrato status, Pageable pageable);

    /**
     * Busca contratos vencidos
     */
    @Query("SELECT c FROM Contrato c WHERE c.dataVencimento < :data AND c.status NOT IN ('PAGO', 'CANCELADO') AND c.deleted = false")
    List<Contrato> findContratosVencidos(@Param("data") LocalDate data);

    /**
     * Busca contrato por ID do Asaas (subscription)
     */
    @Query("SELECT c FROM Contrato c WHERE c.asaasSubscriptionId = :asaasSubscriptionId AND c.deleted = false")
    Optional<Contrato> findByAsaasSubscriptionId(@Param("asaasSubscriptionId") String asaasSubscriptionId);

    /**
     * Busca contratos com filtros básicos (título, cliente, termo)
     */
    @Query("SELECT c FROM Contrato c JOIN c.cliente cl WHERE " +
           "(:clienteId IS NULL OR c.cliente.id = :clienteId) AND " +
           "(:termo IS NULL OR :termo = '' OR " +
           "LOWER(c.titulo) LIKE LOWER(CONCAT('%', :termo, '%')) OR " +
           "LOWER(cl.razaoSocial) LIKE LOWER(CONCAT('%', :termo, '%')) OR " +
           "(cl.nomeFantasia IS NOT NULL AND LOWER(cl.nomeFantasia) LIKE LOWER(CONCAT('%', :termo, '%')))) AND " +
           "c.deleted = false")
    Page<Contrato> buscarComFiltros(
            @Param("clienteId") Long clienteId,
            @Param("termo") String termo,
            Pageable pageable);
}

