package com.finnza.repository;

import com.finnza.domain.entity.DfcPlanilha;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DfcPlanilhaRepository extends JpaRepository<DfcPlanilha, Long> {

    Optional<DfcPlanilha> findByIdEmpresa(Integer idEmpresa);
}
