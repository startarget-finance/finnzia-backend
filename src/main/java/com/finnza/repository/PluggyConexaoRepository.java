package com.finnza.repository;

import com.finnza.domain.entity.PluggyConexao;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PluggyConexaoRepository extends JpaRepository<PluggyConexao, Long> {

    List<PluggyConexao> findByUsuario_IdOrderByDataCriacaoDesc(Long usuarioId);

    Optional<PluggyConexao> findByPluggyItemId(String pluggyItemId);

    Optional<PluggyConexao> findByIdAndUsuario_Id(Long id, Long usuarioId);
}
