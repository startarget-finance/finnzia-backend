package com.finnza.repository;

import com.finnza.domain.entity.RegraTextoConciliacaoExtrato;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RegraTextoConciliacaoExtratoRepository extends JpaRepository<RegraTextoConciliacaoExtrato, Long> {

    List<RegraTextoConciliacaoExtrato> findByIdEmpresaAndAtivoTrueOrderByTextoContemAsc(Integer idEmpresa);

    List<RegraTextoConciliacaoExtrato> findByIdEmpresaAndCartaoIdAndAtivoTrueOrderByTextoContemAsc(
            Integer idEmpresa,
            Long cartaoId
    );
}
