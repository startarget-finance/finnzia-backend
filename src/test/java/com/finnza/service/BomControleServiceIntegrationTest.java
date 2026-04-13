package com.finnza.service;

import com.finnza.dto.response.ResumoFinanceiroDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integração HTTP do {@link BomControleService} contra WireMock (sem Spring context, sem banco).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BomControleServiceIntegrationTest {

    private static final WireMockServer wireMockServer =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private BomControleService bomControleService;

    @BeforeAll
    void startWireMock() {
        wireMockServer.start();
        String baseUrl = String.format("http://localhost:%d", wireMockServer.port());
        bomControleService = new BomControleService(
                "integration-key",
                baseUrl,
                false,
                new BomControleRateLimiter());
    }

    @AfterEach
    void resetWireMock() {
        wireMockServer.resetAll();
    }

    @AfterAll
    void stopWireMock() {
        wireMockServer.stop();
    }

    @Test
    void deveMarcarResumoComoFallbackQuandoSegundaPaginaFalha() throws Exception {
        stubFinanceiroPage(1, 50, 60, 100.0);
        stubFinanceiro429(2);

        ResumoFinanceiroDTO resumo = bomControleService.gerarResumoFinanceiro(
                LocalDate.of(2024, 1, 1).toString(),
                LocalDate.of(2024, 1, 31).toString(),
                "DataPadrao",
                101,
                null,
                null,
                null,
                null,
                null
        );

        assertTrue(resumo.isFallbackAtivo(), "Resumo deveria sinalizar fallback ativo");
        assertEquals("bom-controle/fallback", resumo.getFonteDados());
        Map<String, Object> metadata = resumo.getFallbackMetadata();
        assertNotNull(metadata, "Metadata de fallback deve estar presente");
        assertEquals(1, metadata.get("paginasProcessadas"));
        assertEquals(1, metadata.get("paginasViaFallback"));
        assertEquals(60L, ((Number) metadata.get("totalItensEstimados")).longValue());
        assertTrue(((Number) metadata.get("ttlFallbackRestanteMs")).longValue() >= 0);
    }

    @Test
    void deveManterResumoSaudavelQuandoTodasPaginasSucesso() throws Exception {
        stubFinanceiroPage(1, 50, 60, 100.0);
        stubFinanceiroPage(2, 10, 60, 200.0);

        ResumoFinanceiroDTO resumo = bomControleService.gerarResumoFinanceiro(
                LocalDate.of(2024, 2, 1).toString(),
                LocalDate.of(2024, 2, 29).toString(),
                "DataPadrao",
                202,
                null,
                null,
                null,
                null,
                null
        );

        assertFalse(resumo.isFallbackAtivo(), "Resumo não deve sinalizar fallback");
        assertEquals("bom-controle/api", resumo.getFonteDados());
        assertEquals(60, resumo.getTotalMovimentacoes());
        double expectedReceitas = serieSum(50, 100.0) + serieSum(10, 200.0);
        assertEquals(expectedReceitas, resumo.getContasReceber().getTotalGeral(), 0.01);
        assertEquals(0.0, resumo.getContasPagar().getTotalGeral(), 0.01);
        assertFalse(Boolean.TRUE.equals(resumo.getFallbackMetadata().getOrDefault("fallbackAtivo", true)));
    }

    private void stubFinanceiroPage(int numeroPagina, int quantidadeItens, int totalItens, double valorInicial) throws Exception {
        wireMockServer.stubFor(WireMock.get(WireMock.urlPathEqualTo("/integracao/Financeiro/Pesquisar"))
                .withQueryParam("paginacao.numeroDaPagina", WireMock.equalTo(String.valueOf(numeroPagina)))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(buildFinanceiroBody(quantidadeItens, totalItens, numeroPagina, valorInicial))));
    }

    private void stubFinanceiro429(int numeroPagina) {
        wireMockServer.stubFor(WireMock.get(WireMock.urlPathEqualTo("/integracao/Financeiro/Pesquisar"))
                .withQueryParam("paginacao.numeroDaPagina", WireMock.equalTo(String.valueOf(numeroPagina)))
                .willReturn(WireMock.aResponse().withStatus(429)));
    }

    private String buildFinanceiroBody(int quantidadeItens, int totalItens, int paginaAtual, double valorInicial) throws Exception {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        ArrayNode itens = root.putArray("Itens");
        for (int i = 0; i < quantidadeItens; i++) {
            ObjectNode item = itens.addObject();
            item.put("IdMovimentacaoFinanceiraParcela", ((paginaAtual - 1) * quantidadeItens) + i + 1);
            item.put("Debito", false);
            item.put("Valor", valorInicial + i);
            item.put("Nome", "Mov" + paginaAtual + "_" + i);
            item.put("DataQuitacao", "2024-01-10T00:00:00");
        }
        root.put("TotalItens", totalItens);
        return OBJECT_MAPPER.writeValueAsString(root);
    }

    private double serieSum(int quantidade, double inicio) {
        double ultimo = inicio + quantidade - 1;
        return quantidade * (inicio + ultimo) / 2.0;
    }
}
