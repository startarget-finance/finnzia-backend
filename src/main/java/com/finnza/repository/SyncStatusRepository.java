package com.finnza.repository;

import com.finnza.domain.entity.SyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SyncStatusRepository extends JpaRepository<SyncStatus, Long> {

    Optional<SyncStatus> findByPeriodoEmpresaKey(String periodoEmpresaKey);

    List<SyncStatus> findByIdEmpresaOrderByPeriodoDesc(Integer idEmpresa);

    List<SyncStatus> findByIdEmpresaAndStatusOrderByPeriodoDesc(Integer idEmpresa, String status);
}
