package com.finnza.repository;

import com.finnza.domain.entity.MovimentacaoFinanceira;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface MovimentacaoFinanceiraRepository extends JpaRepository<MovimentacaoFinanceira, String> {

    Optional<MovimentacaoFinanceira> findByIdMovimentacaoAndIdEmpresa(String idMovimentacao, Integer idEmpresa);

    // ── Por vencimento ────────────────────────────────────────────────────────

    @Query("""
            SELECT m FROM MovimentacaoFinanceira m
            WHERE m.idEmpresa = :idEmpresa
              AND m.dataVencimento BETWEEN :dataInicio AND :dataTermino
              AND (m.ofxAprovado IS NULL OR m.ofxAprovado = true)
            """)
    Page<MovimentacaoFinanceira> findByIdEmpresaAndDataVencimentoBetween(
            @Param("idEmpresa") Integer idEmpresa,
            @Param("dataInicio") LocalDate dataInicio,
            @Param("dataTermino") LocalDate dataTermino,
            Pageable pageable);

    @Query("""
            SELECT m FROM MovimentacaoFinanceira m
            WHERE m.idEmpresa = :idEmpresa
              AND m.debito = :debito
              AND m.dataVencimento BETWEEN :dataInicio AND :dataTermino
              AND (m.ofxAprovado IS NULL OR m.ofxAprovado = true)
            """)
    Page<MovimentacaoFinanceira> findByIdEmpresaAndDebitoAndDataVencimentoBetween(
            @Param("idEmpresa") Integer idEmpresa,
            @Param("debito") Boolean debito,
            @Param("dataInicio") LocalDate dataInicio,
            @Param("dataTermino") LocalDate dataTermino,
            Pageable pageable);

    @Query("""
            SELECT m FROM MovimentacaoFinanceira m
            WHERE m.idEmpresa = :idEmpresa
              AND m.debito = :debito
              AND m.statusPagamento = :statusPagamento
              AND m.dataVencimento BETWEEN :dataInicio AND :dataTermino
              AND (m.ofxAprovado IS NULL OR m.ofxAprovado = true)
            """)
    Page<MovimentacaoFinanceira> findByIdEmpresaAndDebitoAndStatusPagamentoAndDataVencimentoBetween(
            @Param("idEmpresa") Integer idEmpresa,
            @Param("debito") Boolean debito,
            @Param("statusPagamento") String statusPagamento,
            @Param("dataInicio") LocalDate dataInicio,
            @Param("dataTermino") LocalDate dataTermino,
            Pageable pageable);

    // ── Por competência ───────────────────────────────────────────────────────

    @Query("""
            SELECT m FROM MovimentacaoFinanceira m
            WHERE m.idEmpresa = :idEmpresa
              AND m.dataCompetencia BETWEEN :dataInicio AND :dataTermino
              AND (m.ofxAprovado IS NULL OR m.ofxAprovado = true)
            """)
    Page<MovimentacaoFinanceira> findByIdEmpresaAndDataCompetenciaBetween(
            @Param("idEmpresa") Integer idEmpresa,
            @Param("dataInicio") LocalDate dataInicio,
            @Param("dataTermino") LocalDate dataTermino,
            Pageable pageable);

    @Query("""
            SELECT m FROM MovimentacaoFinanceira m
            WHERE m.idEmpresa = :idEmpresa
              AND m.debito = :debito
              AND m.dataCompetencia BETWEEN :dataInicio AND :dataTermino
              AND (m.ofxAprovado IS NULL OR m.ofxAprovado = true)
            """)
    Page<MovimentacaoFinanceira> findByIdEmpresaAndDebitoAndDataCompetenciaBetween(
            @Param("idEmpresa") Integer idEmpresa,
            @Param("debito") Boolean debito,
            @Param("dataInicio") LocalDate dataInicio,
            @Param("dataTermino") LocalDate dataTermino,
            Pageable pageable);

    // ── Totalizadores para resumo financeiro ──────────────────────────────────

    @Query("""
           SELECT COALESCE(SUM(m.valor), 0)
           FROM MovimentacaoFinanceira m
           WHERE m.idEmpresa = :idEmpresa
             AND m.debito = :debito
             AND m.dataVencimento BETWEEN :dataInicio AND :dataTermino
             AND (m.ofxAprovado IS NULL OR m.ofxAprovado = true)
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
             AND (m.ofxAprovado IS NULL OR m.ofxAprovado = true)
           """)
    BigDecimal sumValorByEmpresaAndDebitoAndStatusAndVencimento(
            @Param("idEmpresa") Integer idEmpresa,
            @Param("debito") Boolean debito,
            @Param("statusPagamento") String statusPagamento,
            @Param("dataInicio") LocalDate dataInicio,
            @Param("dataTermino") LocalDate dataTermino);

    // ── Busca completa (sem paginação) — usada para DFC ──────────────────────

    @Query("""
            SELECT m FROM MovimentacaoFinanceira m
            WHERE m.idEmpresa = :idEmpresa
              AND m.dataVencimento BETWEEN :dataInicio AND :dataTermino
              AND (m.ofxAprovado IS NULL OR m.ofxAprovado = true)
            """)
    List<MovimentacaoFinanceira> findAllByIdEmpresaAndDataVencimentoBetween(
            @Param("idEmpresa") Integer idEmpresa,
            @Param("dataInicio") LocalDate dataInicio,
            @Param("dataTermino") LocalDate dataTermino);

    // ── Verificação de existência de dados sync'd ──────────────────────────────

    @Query("""
            SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END
            FROM MovimentacaoFinanceira m
            WHERE m.idEmpresa = :idEmpresa
              AND m.dataVencimento BETWEEN :dataInicio AND :dataTermino
              AND (m.ofxAprovado IS NULL OR m.ofxAprovado = true)
            """)
    boolean existsByIdEmpresaAndDataVencimentoBetween(
            @Param("idEmpresa") Integer idEmpresa,
            @Param("dataInicio") LocalDate dataInicio,
            @Param("dataTermino") LocalDate dataTermino);

    @Query("""
            SELECT COUNT(m)
            FROM MovimentacaoFinanceira m
            WHERE m.idEmpresa = :idEmpresa
              AND m.dataVencimento BETWEEN :dataInicio AND :dataTermino
              AND (m.ofxAprovado IS NULL OR m.ofxAprovado = true)
            """)
    long countByIdEmpresaAndDataVencimentoBetween(
            @Param("idEmpresa") Integer idEmpresa,
            @Param("dataInicio") LocalDate dataInicio,
            @Param("dataTermino") LocalDate dataTermino);

    // ── Empresas (para telas de acessos/config) ───────────────────────────────

    @Query("""
           SELECT DISTINCT m.idEmpresa, m.nomeEmpresa
           FROM MovimentacaoFinanceira m
           WHERE m.nomeEmpresa IS NOT NULL
           ORDER BY m.nomeEmpresa
           """)
    List<Object[]> listarEmpresasDistinct();

    /** IDs de empresa que já têm movimentação persistida (inclui linhas sem nome na movimentação). */
    @Query("""
           SELECT DISTINCT m.idEmpresa
           FROM MovimentacaoFinanceira m
           WHERE m.idEmpresa IS NOT NULL
           ORDER BY m.idEmpresa
           """)
    List<Integer> findDistinctIdEmpresas();

    /**
     * Fluxo single-tenant: usa o primeiro ID de empresa disponível mesmo quando o nome estiver nulo.
     */
    Optional<MovimentacaoFinanceira> findFirstByIdEmpresaIsNotNullOrderByIdEmpresaAsc();

    List<MovimentacaoFinanceira> findTop200ByIdEmpresaAndIdContaFinanceiraOrderByDataVencimentoDescIdMovimentacaoDesc(
            Integer idEmpresa,
            Integer idContaFinanceira
    );

    long countByIdEmpresaAndOfxImportacaoId(Integer idEmpresa, Long ofxImportacaoId);

    @Modifying
    @Query("""
            UPDATE MovimentacaoFinanceira m
            SET m.ofxAprovado = true
            WHERE m.idEmpresa = :idEmpresa
              AND m.ofxImportacaoId = :ofxImportacaoId
              AND (m.ofxAprovado IS NULL OR m.ofxAprovado = false)
            """)
    int aprovarConciliacaoOfx(
            @Param("idEmpresa") Integer idEmpresa,
            @Param("ofxImportacaoId") Long ofxImportacaoId
    );

    @Modifying
    int deleteByIdEmpresaAndOfxImportacaoId(Integer idEmpresa, Long ofxImportacaoId);

    @Query("""
            SELECT m
            FROM MovimentacaoFinanceira m
            WHERE m.idEmpresa = :idEmpresa
              AND m.ofxImportacaoId IS NOT NULL
              AND (
                   m.nomeClienteFornecedor IS NULL OR m.nomeClienteFornecedor = ''
                OR m.nomeCategoriaFinanceira IS NULL OR m.nomeCategoriaFinanceira = ''
              )
            ORDER BY m.dataVencimento DESC, m.idMovimentacao DESC
            """)
    List<MovimentacaoFinanceira> findOfxComDadosPendentes(
            @Param("idEmpresa") Integer idEmpresa,
            Pageable pageable
    );
}
