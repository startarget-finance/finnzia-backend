package com.finnza.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finnza.domain.entity.PlanoContasPadraoSistema;
import com.finnza.dto.request.AtualizarPlanoContasPadraoRequest;
import com.finnza.dto.response.PlanoContasPadraoResponse;
import com.finnza.repository.PlanoContasPadraoSistemaRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PlanoContasPadraoSistemaService {

    private static final short REGISTRO_ID = 1;

    private final PlanoContasPadraoSistemaRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public PlanoContasPadraoResponse obter() {
        return repository
                .findById(REGISTRO_ID)
                .map(this::toResponse)
                .orElseGet(this::respostaPadraoEmbutido);
    }

    @Transactional
    public PlanoContasPadraoResponse atualizar(String emailAdmin, AtualizarPlanoContasPadraoRequest request) {
        validarArvore(request.getArvore());
        String json = serializar(request.getArvore());
        PlanoContasPadraoSistema reg =
                repository.findById(REGISTRO_ID).orElseGet(PlanoContasPadraoSistema::new);
        reg.setId(REGISTRO_ID);
        reg.setConteudoJson(json);
        reg.setDataAtualizacao(LocalDateTime.now());
        reg.setAtualizadoPorEmail(emailAdmin != null ? emailAdmin.trim() : null);
        repository.save(reg);
        return toResponse(reg);
    }

    @Transactional
    public PlanoContasPadraoResponse restaurarEmbutido(String emailAdmin) {
        String json = carregarJsonEmbutido();
        PlanoContasPadraoSistema reg =
                repository.findById(REGISTRO_ID).orElseGet(PlanoContasPadraoSistema::new);
        reg.setId(REGISTRO_ID);
        reg.setConteudoJson(json);
        reg.setDataAtualizacao(LocalDateTime.now());
        reg.setAtualizadoPorEmail(emailAdmin != null ? emailAdmin.trim() : null);
        repository.save(reg);
        return toResponse(reg);
    }

    private PlanoContasPadraoResponse respostaPadraoEmbutido() {
        try {
            JsonNode arvore = objectMapper.readTree(carregarJsonEmbutido());
            return PlanoContasPadraoResponse.builder()
                    .arvore(arvore)
                    .dataAtualizacao(null)
                    .atualizadoPorEmail(null)
                    .usandoPadraoEmbutido(true)
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível carregar plano de contas padrão embutido", e);
        }
    }

    private PlanoContasPadraoResponse toResponse(PlanoContasPadraoSistema reg) {
        try {
            JsonNode arvore = objectMapper.readTree(reg.getConteudoJson());
            return PlanoContasPadraoResponse.builder()
                    .arvore(arvore)
                    .dataAtualizacao(reg.getDataAtualizacao())
                    .atualizadoPorEmail(reg.getAtualizadoPorEmail())
                    .usandoPadraoEmbutido(false)
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("JSON do plano padrão inválido no banco", e);
        }
    }

    private void validarArvore(JsonNode arvore) {
        if (arvore == null || !arvore.isArray() || arvore.isEmpty()) {
            throw new IllegalArgumentException("O plano deve ser um array JSON com ao menos uma raiz.");
        }
        for (JsonNode no : arvore) {
            if (!no.isObject()) {
                throw new IllegalArgumentException("Cada item da raiz deve ser um objeto.");
            }
            String tipo = no.path("tipo").asText("").trim().toLowerCase();
            if (!"receita".equals(tipo) && !"despesa".equals(tipo)) {
                throw new IllegalArgumentException("Cada raiz precisa de tipo \"receita\" ou \"despesa\".");
            }
            String nome = no.path("nome").asText("").trim();
            if (nome.isEmpty()) {
                nome = no.path("categoria").asText("").trim();
            }
            if (nome.isEmpty()) {
                throw new IllegalArgumentException("Cada raiz precisa de \"nome\" (ou \"categoria\").");
            }
        }
    }

    private String serializar(JsonNode arvore) {
        try {
            return objectMapper.writeValueAsString(arvore);
        } catch (IOException e) {
            throw new IllegalArgumentException("Não foi possível serializar o plano de contas.", e);
        }
    }

    private String carregarJsonEmbutido() {
        try {
            ClassPathResource res = new ClassPathResource("plano-contas-padrao-default.json");
            try (InputStream in = res.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Arquivo plano-contas-padrao-default.json ausente", e);
        }
    }
}
