package com.finnza.repository;

import com.finnza.domain.entity.PlanoContasGerencial;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlanoContasGerencialRepository extends JpaRepository<PlanoContasGerencial, Long> {

    List<PlanoContasGerencial> findAllByDeletedFalseOrderByNomeAsc();

    Optional<PlanoContasGerencial> findByIdAndDeletedFalse(Long id);
}
