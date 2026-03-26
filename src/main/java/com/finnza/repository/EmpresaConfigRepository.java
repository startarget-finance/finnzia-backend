package com.finnza.repository;

import com.finnza.domain.entity.EmpresaConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmpresaConfigRepository extends JpaRepository<EmpresaConfig, Long> {

    Optional<EmpresaConfig> findByIdEmpresa(Integer idEmpresa);
}
