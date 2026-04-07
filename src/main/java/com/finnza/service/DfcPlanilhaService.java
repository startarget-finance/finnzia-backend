package com.finnza.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finnza.domain.entity.DfcPlanilha;
import com.finnza.dto.dfc.DfcPlanilhaLinhaDTO;
import com.finnza.dto.dfc.DfcPlanilhaPayloadDTO;
import com.finnza.dto.dfc.DfcPlanilhaResponseDTO;
import com.finnza.repository.DfcPlanilhaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DfcPlanilhaService {

    private final DfcPlanilhaRepository repository;
    private final ObjectMapper objectMapper;

    private static final TypeReference<List<String>> LIST_STRING = new TypeReference<>() {};
    private static final TypeReference<List<DfcPlanilhaLinhaDTO>> LIST_LINHA = new TypeReference<>() {};

    public Optional<DfcPlanilhaResponseDTO> buscar(Integer idEmpresa) {
        return repository.findByIdEmpresa(idEmpresa).map(this::toResponse);
    }

    @Transactional
    public DfcPlanilhaResponseDTO salvarOuAtualizar(Integer idEmpresa, DfcPlanilhaPayloadDTO body) {
        if (body.getMonths() == null || body.getRows() == null) {
            throw new IllegalArgumentException("months e rows são obrigatórios");
        }
        try {
            String monthsJson = objectMapper.writeValueAsString(body.getMonths());
            String rowsJson = objectMapper.writeValueAsString(body.getRows());

            DfcPlanilha entity = repository.findByIdEmpresa(idEmpresa)
                    .orElse(DfcPlanilha.builder().idEmpresa(idEmpresa).build());
            entity.setMonthsJson(monthsJson);
            entity.setRowsJson(rowsJson);
            entity = repository.save(entity);
            return toResponse(entity);
        } catch (Exception e) {
            log.error("Erro ao salvar DFC planilha empresa={}", idEmpresa, e);
            throw new IllegalArgumentException("Payload inválido: " + e.getMessage());
        }
    }

    private DfcPlanilhaResponseDTO toResponse(DfcPlanilha entity) {
        try {
            List<String> months = objectMapper.readValue(entity.getMonthsJson(), LIST_STRING);
            List<DfcPlanilhaLinhaDTO> rows = objectMapper.readValue(entity.getRowsJson(), LIST_LINHA);
            return DfcPlanilhaResponseDTO.builder()
                    .id(entity.getId())
                    .idEmpresa(entity.getIdEmpresa())
                    .months(months)
                    .rows(rows)
                    .build();
        } catch (Exception e) {
            log.error("Erro ao ler JSON da planilha DFC id={}", entity.getId(), e);
            return DfcPlanilhaResponseDTO.builder()
                    .id(entity.getId())
                    .idEmpresa(entity.getIdEmpresa())
                    .months(List.of())
                    .rows(List.of())
                    .build();
        }
    }
}
