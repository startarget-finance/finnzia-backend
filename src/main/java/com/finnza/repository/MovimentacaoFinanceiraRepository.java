package com.finnza.repository;

import com.finnza.domain.entity.MovimentacaoFinanceira;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface MovimentacaoFinanceiraRepository extends JpaRepository<MovimentacaoFinanceira, String> {

    // ── Por vencimento ────────────────────────────────────────────────────────

    Page<MovimentacaoFinanceira> findByIdEmpresaAndDataVencimentoBetween(
            Integer idEmpresa, LocalDate dataInicio, LocalDate dataTermino, Pageable pageable);

    Page<MovimentacaoFinanceira> findByIdEmpresaAndDebitoAndDataVencimentoBetween(
            Integer idEmpresa, Boolean debito, LocalDate dataInicio, LocalDate dataTermino, Pageable pageable);

    Page<MovimentacaoFinanceira> findByIdEmpresaAndDebitoAndStatusPagamentoAndDataVencimentoBetween(
            Integer idEmpresa, Boolean debito, String statusPagamento,
            LocalDate dataInicio, LocalDate dataTermino, Pageable pageable);

    // ── Por competência ───────────────────────────────────────────────────────

    Page<MovimentacaoFinanceira> findByIdEmpresaAndDataCompetenciaBetween(
            Integer idEmpresa, LocalDate dataInicio, LocalDate dataTermino, Pageable pageable);

    Page<MovimentacaoFinanceira> findByIdEmpresaAndDebitoAndDataCompetenciaBetween(
            Integer idEmpresa, Boolean debito, LocalDate dataInicio, LocalDate dataTermino, Pageable pageable);

    // ── Totalizadores para resumo financeiro ──────────────────────────────────

    @Query("""
           SELECT COALESCE(SUM(m.valor), 0)
           FROM MovimentacaoFinanceira m
           WHERE m.idEmpresa = :idEmpresa
             AND m.debito = :debito
             AND m.dataVencimento BETWEEN :dataInicio AND :dataTermino
           """)
    BigDecimal sumValorByEmpresaAndDebitoAndVencimento(
            @Param("idEmpresa") Integer idEmpresa,
            @Param("debito") Boolean debito,
            @Param("dataInicio") LocalDate dataInicio,
            @Param("dataTermino") LocalDate dataTermino);

    @Query("""
           SELECT COALESCE(SUM(m.valor), 0)
           FROM MovimentacaoFinanceira m
           WHERE m.idEmpresa = :idEmpresa
             AND m.debito = :debito
             AND m.statusPagamento = :statusPagamento
             AND m.dataVencimento BETWEEN :dataInicio AND :dataTermino
           """)
    BigDecimal sumValorByEmpresaAndDebitoAndStatusAndVencimento(
            @Param("idEmpresa") Integer idEmpresa,
            @Param("debito") Boolean debito,
            @Param("statusPagamento") String statusPagamento,
            @Param("dataInicio") LocalDate dataInicio,
            @Param("dataTermino") LocalDate dataTermino);

    // ── Busca completa (sem paginação) — usada para DFC ──────────────────────

    List<MovimentacaoFinanceira> findAllByIdEmpresaAndDataVencimentoBetween(
            Integer idEmpresa, LocalDate dataInicio, LocalDate dataTermino);

    // ── Verificação de existência de dados sync'd ──────────────────────────────

    boolean existsByIdEmpresaAndDataVencimentoBetween(
            Integer idEmpresa, LocalDate dataInicio, LocalDate dataTermino);

    long countByIdEmpresaAndDataVencimentoBetween(
            Integer idEmpresa, LocalDate dataInicio, LocalDate dataTermino);

    // ── Empresas (para telas de acessos/config) ───────────────────────────────

    @Query("""
           SELECT DISTINCT m.idEmpresa, m.nomeEmpresa
           FROM MovimentacaoFinanceira m
           WHERE m.nomeEmpresa IS NOT NULL
           ORDER BY m.nomeEmpresa
           """)
    List<Object[]> listarEmpresasDistinct();

    /**
     * Fluxo single-tenant: usa o primeiro ID de empresa disponível mesmo quando o nome estiver nulo.
     */
    Optional<MovimentacaoFinanceira> findFirstByIdEmpresaIsNotNullOrderByIdEmpresaAsc();
}
