package com.finnza.repository;

import com.finnza.domain.entity.MovimentacaoHistorico;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface MovimentacaoHistoricoRepository extends JpaRepository<MovimentacaoHistorico, Long> {
    Page<MovimentacaoHistorico> findByIdEmpresa(Integer idEmpresa, Pageable pageable);
    Page<MovimentacaoHistorico> findByIdEmpresaAndAcao(Integer idEmpresa, String acao, Pageable pageable);
    Page<MovimentacaoHistorico> findByIdEmpresaAndDataEventoBetween(
            Integer idEmpresa,
            LocalDateTime inicio,
            LocalDateTime fim,
            Pageable pageable
    );
    Page<MovimentacaoHistorico> findByIdEmpresaAndAcaoAndDataEventoBetween(
            Integer idEmpresa,
            String acao,
            LocalDateTime inicio,
            LocalDateTime fim,
            Pageable pageable
    );

    Optional<MovimentacaoHistorico> findByIdAndIdEmpresa(Long id, Integer idEmpresa);
}
