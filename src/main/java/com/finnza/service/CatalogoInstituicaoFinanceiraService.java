package com.finnza.service;

import com.finnza.dto.response.InstituicaoFinanceiraDTO;
import com.finnza.repository.CatalogoInstituicaoFinanceiraRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogoInstituicaoFinanceiraService {

    private final CatalogoInstituicaoFinanceiraRepository repository;

    public List<InstituicaoFinanceiraDTO> listar(String q, Integer limit) {
        int lim = limit == null ? 200 : Math.min(Math.max(limit, 1), 2000);
        List<InstituicaoFinanceiraDTO> all = repository.buscarAtivas(q).stream()
                .map(c -> InstituicaoFinanceiraDTO.builder()
                        .id(c.getId())
                        .codigo(c.getCodigo())
                        .banco(c.getBanco())
                        .instituicao(c.getInstituicao())
                        .grupo(c.getGrupo())
                        .popular(Boolean.TRUE.equals(c.getPopular()))
                        .build())
                .toList();
        if (all.size() <= lim) {
            return all;
        }
        return all.subList(0, lim);
    }
}
