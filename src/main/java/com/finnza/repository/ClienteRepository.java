package com.finnza.repository;

import com.finnza.domain.entity.Cliente;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository para Cliente
 */
@Repository
public interface ClienteRepository extends JpaRepository<Cliente, Long>, JpaSpecificationExecutor<Cliente> {

    /**
     * Busca cliente por CPF/CNPJ (não deletado)
     */
    @Query("SELECT c FROM Cliente c WHERE c.cpfCnpj = :cpfCnpj AND c.deleted = false AND c.cpfCnpj IS NOT NULL")
    Optional<Cliente> findByCpfCnpj(@Param("cpfCnpj") String cpfCnpj);

    /**
     * Busca cliente por ID do Asaas
     */
    @Query("SELECT c FROM Cliente c WHERE c.asaasCustomerId = :asaasCustomerId AND c.deleted = false")
    Optional<Cliente> findByAsaasCustomerId(@Param("asaasCustomerId") String asaasCustomerId);

    /**
     * Lista clientes não deletados com paginação
     */
    @Query("SELECT c FROM Cliente c WHERE c.deleted = false")
    Page<Cliente> findAllNaoDeletados(Pageable pageable);

    /**
     * Busca clientes por nome/razão social (não deletados)
     */
    @Query("SELECT c FROM Cliente c WHERE " +
           "(LOWER(c.razaoSocial) LIKE LOWER(CONCAT('%', :termo, '%')) OR " +
           "LOWER(c.nomeFantasia) LIKE LOWER(CONCAT('%', :termo, '%'))) " +
           "AND c.deleted = false")
    Page<Cliente> buscarPorTermo(@Param("termo") String termo, Pageable pageable);

    @Query("SELECT c FROM Cliente c WHERE c.id = :id AND c.deleted = false")
    Optional<Cliente> findByIdNaoDeletado(@Param("id") Long id);

    @Query("SELECT c FROM Cliente c WHERE c.cpfCnpj = :cpf AND c.deleted = false AND c.cpfCnpj IS NOT NULL AND (:id IS NULL OR c.id <> :id)")
    Optional<Cliente> findOutroPorCpfCnpj(@Param("cpf") String cpf, @Param("id") Long id);

    @Query("""
            SELECT c
            FROM Cliente c
            JOIN c.idEmpresas e
            WHERE c.deleted = false
              AND e = :idEmpresa
              AND (
                    LOWER(c.razaoSocial) = LOWER(:nome)
                 OR LOWER(c.nomeFantasia) = LOWER(:nome)
              )
            ORDER BY c.id ASC
            """)
    List<Cliente> findByNomeNaEmpresa(@Param("idEmpresa") Integer idEmpresa, @Param("nome") String nome);
}

