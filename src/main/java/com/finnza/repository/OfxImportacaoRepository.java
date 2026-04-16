package com.finnza.repository;

import com.finnza.domain.entity.OfxImportacao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OfxImportacaoRepository extends JpaRepository<OfxImportacao, Long> {

    List<OfxImportacao> findByIdEmpresaAndDataImportacaoBetweenOrderByDataImportacaoDesc(
            Integer idEmpresa,
            LocalDateTime dataInicio,
            LocalDateTime dataFim
    );
}

