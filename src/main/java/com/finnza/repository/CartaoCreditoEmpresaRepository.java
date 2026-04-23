package com.finnza.repository;

import com.finnza.domain.entity.CartaoCreditoEmpresa;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CartaoCreditoEmpresaRepository extends JpaRepository<CartaoCreditoEmpresa, Long> {
    List<CartaoCreditoEmpresa> findByIdEmpresaAndAtivoTrueOrderByNomeAsc(Integer idEmpresa);

    Optional<CartaoCreditoEmpresa> findByIdAndIdEmpresa(Long id, Integer idEmpresa);
}
