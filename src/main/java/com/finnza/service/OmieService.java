package com.finnza.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service para integração com API do OMIE
 * Documentação: https://developer.omie.com.br/
 * 
 * OMIE usa JSON com formato específico:
 * {
 *   "call": "NomeDoMetodo",
 *   "app_key": "...",
 *   "app_secret": "...",
 *   "param": [{ ... }]
 * }
 */
@Slf4j
@Service
public class OmieService {

    private final WebClient webClient;
    private final String appKey;
    private final String appSecret;
    private final String baseUrl;
    private final boolean mockEnabled;
    
    // Cache de totais calculados (chave: dataInicio_dataFim, valor: TotaisCache)
    private final Map<String, TotaisCache> cacheTotais = new ConcurrentHashMap<>();
    
    // Cache de requisições recentes para evitar consumo redundante (chave: endpoint_call_params, valor: ResponseCache)
    private final Map<String, ResponseCache> cacheRequisicoes = new ConcurrentHashMap<>();
    
    // Locks por chave de requisição para evitar chamadas simultâneas duplicadas
    private final Map<String, Object> locksRequisicoes = new ConcurrentHashMap<>();
    
    // Classe interna para armazenar totais com timestamp
    private static class TotaisCache {
        final double totalReceitas;
        final double totalDespesas;
        final double saldoLiquido;
        final long timestamp;
        
        TotaisCache(double totalReceitas, double totalDespesas, double saldoLiquido) {
            this.totalReceitas = totalReceitas;
            this.totalDespesas = totalDespesas;
            this.saldoLiquido = saldoLiquido;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired(long ttlMillis) {
            return (System.currentTimeMillis() - timestamp) > ttlMillis;
        }
    }
    
    // Classe interna para cache de respostas da API
    private static class ResponseCache {
        final Map<String, Object> response;
        final long timestamp;
        
        ResponseCache(Map<String, Object> response) {
            this.response = response;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired(long ttlMillis) {
            return (System.currentTimeMillis() - timestamp) > ttlMillis;
        }
    }

    public OmieService(
            @Value("${omie.app.key:}") String appKey,
            @Value("${omie.app.secret:}") String appSecret,
            @Value("${omie.api.url:https://app.omie.com.br/api/v1}") String baseUrl,
            @Value("${omie.mock.enabled:false}") boolean mockEnabled) {
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.baseUrl = baseUrl;
        this.mockEnabled = mockEnabled || appKey == null || appKey.isEmpty() || appSecret == null || appSecret.isEmpty();

        // Log das credenciais (apenas primeiros e últimos caracteres por segurança)
        String appKeyMasked = appKey != null && appKey.length() > 5 
            ? appKey.substring(0, 5) + "..." + appKey.substring(appKey.length() - 5)
            : (appKey != null ? appKey : "não configurada");
        String appSecretMasked = appSecret != null && appSecret.length() > 5
            ? appSecret.substring(0, 5) + "..." + appSecret.substring(appSecret.length() - 5)
            : (appSecret != null ? appSecret : "não configurada");
        log.info("OmieService - App Key configurada: {}", appKeyMasked);
        log.info("OmieService - App Secret configurada: {}", appSecretMasked);

        // Configura estratégia de exchange com limite maior de buffer (10MB) para suportar respostas grandes
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 10MB
                .build();
        
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(strategies)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

        if (this.mockEnabled) {
            log.info("OmieService iniciado em modo MOCK - API real desabilitada");
        } else {
            log.info("OmieService iniciado - Conectando ao OMIE: {}", baseUrl);
        }
    }

    /**
     * Testa a conexão com a API do OMIE
     */
    public Map<String, Object> testarConexao() {
        if (mockEnabled) {
            return Map.of(
                    "status", "success",
                    "message", "Modo mock ativado - conexão simulada",
                    "mock", true,
                    "appKeyConfigurada", appKey != null && !appKey.isEmpty(),
                    "appSecretConfigurada", appSecret != null && !appSecret.isEmpty(),
                    "urlBase", baseUrl
            );
        }

        try {
            // OMIE usa JSON com app_key, app_secret, call e param (array)
            // Para teste, vamos usar um endpoint simples (ex: ListarClientes)
            Map<String, Object> params = new HashMap<>();
            params.put("pagina", 1);
            params.put("registros_por_pagina", 1);
            params.put("apenas_importado_api", "N");

            log.info("Testando conexão com OMIE - enviando requisição JSON para ListarClientes");
            
            Map<String, Object> response = executarChamadaApi("/geral/clientes/", "ListarClientes", params);

            log.info("Conexão com OMIE testada com sucesso");
            return Map.of(
                    "status", "success",
                    "message", "Conexão estabelecida com sucesso",
                    "appKeyConfigurada", appKey != null && !appKey.isEmpty(),
                    "appSecretConfigurada", appSecret != null && !appSecret.isEmpty(),
                    "urlBase", baseUrl,
                    "total_de_registros", response.getOrDefault("total_de_registros", 0),
                    "resposta", response
            );
        } catch (WebClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            log.error("Erro HTTP ao testar conexão com OMIE: {} - {}", e.getStatusCode(), responseBody);
            
            // Tenta extrair mensagem de erro específica
            String mensagemErro = "Erro ao conectar com OMIE: " + e.getStatusCode();
            if (responseBody != null) {
                if (responseBody.contains("faultstring")) {
                    try {
                        int inicio = responseBody.indexOf("\"faultstring\":\"") + 15;
                        int fim = responseBody.indexOf("\"", inicio);
                        if (fim > inicio) {
                            mensagemErro = responseBody.substring(inicio, fim);
                        }
                    } catch (Exception ex) {
                        // Ignora erro ao extrair
                    }
                } else if (responseBody.contains("message")) {
                    try {
                        int inicio = responseBody.indexOf("\"message\":\"") + 11;
                        int fim = responseBody.indexOf("\"", inicio);
                        if (fim > inicio) {
                            mensagemErro = responseBody.substring(inicio, fim);
                        }
                    } catch (Exception ex) {
                        // Ignora erro ao extrair
                    }
                }
            }
            
            return Map.of(
                    "status", "error",
                    "message", mensagemErro,
                    "statusCode", e.getStatusCode().value(),
                    "detalhes", responseBody != null && responseBody.length() > 500 
                        ? responseBody.substring(0, 500) + "..." 
                        : responseBody,
                    "appKeyConfigurada", appKey != null && !appKey.isEmpty(),
                    "appSecretConfigurada", appSecret != null && !appSecret.isEmpty(),
                    "urlBase", baseUrl
            );
        } catch (Exception e) {
            log.error("Erro ao testar conexão com OMIE", e);
            return Map.of(
                    "status", "error",
                    "message", "Erro ao testar conexão: " + e.getMessage(),
                    "erroTipo", e.getClass().getSimpleName(),
                    "appKeyConfigurada", appKey != null && !appKey.isEmpty(),
                    "appSecretConfigurada", appSecret != null && !appSecret.isEmpty(),
                    "urlBase", baseUrl
            );
        }
    }

    /**
     * Lista empresas do OMIE
     */
    public Map<String, Object> listarEmpresas() {
        if (mockEnabled) {
            return Map.of(
                    "total_de_registros", 1,
                    "registros", java.util.List.of(
                            Map.of(
                                    "codigo_empresa", "1",
                                    "razao_social", "Empresa Mock",
                                    "nome_fantasia", "Empresa Mock"
                            )
                    )
            );
        }

        try {
            // OMIE não tem endpoint direto de empresas via SOAP
            // Normalmente empresas são obtidas via contexto da aplicação
            log.warn("OMIE não possui endpoint direto de listagem de empresas via API");
            return Map.of(
                    "total_de_registros", 0,
                    "registros", java.util.List.of(),
                    "aviso", "OMIE não possui endpoint de listagem de empresas. Use o contexto da aplicação."
            );
        } catch (Exception e) {
            log.error("Erro ao listar empresas do OMIE", e);
            throw new RuntimeException("Erro ao listar empresas: " + e.getMessage(), e);
        }
    }

    /**
     * Cria o corpo da requisição JSON para API OMIE
     */
    private Map<String, Object> criarRequestBody(String call, Map<String, Object> params) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("call", call);
        requestBody.put("app_key", appKey);
        requestBody.put("app_secret", appSecret);
        
        // OMIE espera param como array: [] (vazio) ou [{...}] (com objeto)
        // Para ListarContasPagar/ListarContasReceber, pode precisar de objeto vazio [{}]
        if (params == null || params.isEmpty()) {
            // Tentando array com objeto vazio - alguns endpoints precisam do objeto mesmo que vazio
            requestBody.put("param", List.of(new HashMap<>()));
        } else {
            requestBody.put("param", List.of(params));
        }
        
        return requestBody;
    }

    /**
     * Gera chave única para cache de requisições
     */
    private String gerarChaveCache(String endpoint, String call, Map<String, Object> params) {
        return endpoint + "|" + call + "|" + params.toString();
    }
    
    /**
     * Executa uma chamada à API OMIE com cache e retry automático para consumo redundante
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executarChamadaApi(String endpoint, String call, Map<String, Object> params) {
        String cacheKey = gerarChaveCache(endpoint, call, params);
        
        // Verifica cache recente (últimos 5 segundos para evitar consumo redundante)
        ResponseCache cached = cacheRequisicoes.get(cacheKey);
        if (cached != null && !cached.isExpired(5000)) {
            log.debug("Retornando resposta do cache para evitar consumo redundante: {}", cacheKey);
            return new HashMap<>(cached.response);
        }
        
        // Obtém ou cria lock para esta requisição específica
        Object lock = locksRequisicoes.computeIfAbsent(cacheKey, k -> new Object());
        
        // Sincroniza por chave para evitar chamadas simultâneas duplicadas
        synchronized (lock) {
            try {
                // Verifica cache novamente após adquirir lock (pode ter sido atualizado por outra thread)
                cached = cacheRequisicoes.get(cacheKey);
                if (cached != null && !cached.isExpired(5000)) {
                    log.debug("Retornando resposta do cache (após lock): {}", cacheKey);
                    return new HashMap<>(cached.response);
                }
                
                return executarChamadaApiComRetry(endpoint, call, params, cacheKey);
            } finally {
                // Remove lock após 10 segundos para evitar acúmulo de locks
                locksRequisicoes.remove(cacheKey);
            }
        }
    }
    
    /**
     * Executa chamada à API com retry automático para consumo redundante
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executarChamadaApiComRetry(String endpoint, String call, 
                                                           Map<String, Object> params, String cacheKey) {
        Map<String, Object> requestBody = criarRequestBody(call, params);
        
        // Log do request body (sem app_secret por segurança)
        Map<String, Object> logBody = new HashMap<>(requestBody);
        logBody.put("app_secret", "***HIDDEN***");
        log.debug("Chamando API OMIE: {} - call: {} - body: {}", endpoint, call, logBody);
        
        int maxRetries = 2;
        int retryDelaySeconds = 6; // Aguarda 6 segundos (um pouco mais que os 5 recomendados)
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                Map<String, Object> response = webClient.post()
                        .uri(endpoint)
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();

                // Verifica se há erro na resposta
                if (response != null && response.containsKey("faultstring")) {
                    String erro = (String) response.get("faultstring");
                    log.error("Erro retornado pela API OMIE: {}", erro);
                    throw new RuntimeException("Erro na API OMIE: " + erro);
                }

                Map<String, Object> finalResponse = response != null ? response : new HashMap<>();
                
                // Armazena no cache para evitar consumo redundante
                cacheRequisicoes.put(cacheKey, new ResponseCache(finalResponse));
                
                return finalResponse;
            } catch (WebClientResponseException e) {
                String responseBody = e.getResponseBodyAsString();
                
                // Tratamento especial para consumo redundante (500 com mensagem específica)
                if (e.getStatusCode().value() == 500 && 
                    responseBody != null && 
                    (responseBody.contains("Consumo redundante") || responseBody.contains("CACHED"))) {
                    
                    if (attempt < maxRetries) {
                        log.warn("Consumo redundante detectado na API OMIE (tentativa {}/{}). Aguardando {} segundos...", 
                                attempt + 1, maxRetries + 1, retryDelaySeconds);
                        try {
                            Thread.sleep(retryDelaySeconds * 1000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrompido durante retry", ie);
                        }
                        continue; // Tenta novamente
                    } else {
                        String mensagemErro = "ERROR: Consumo redundante detectado. Aguarde 5 segundos para tentar novamente.";
                        if (responseBody.contains("CACHED:")) {
                            try {
                                int inicio = responseBody.indexOf("CACHED:") + 7;
                                int fim = responseBody.indexOf(")", inicio);
                                if (fim > inicio) {
                                    String cacheId = responseBody.substring(inicio, fim).trim();
                                    mensagemErro += " (CACHED: " + cacheId + ")";
                                }
                            } catch (Exception ex) {
                                // Ignora erro ao extrair cache ID
                            }
                        }
                        log.error("Consumo redundante após {} tentativas: {}", maxRetries + 1, mensagemErro);
                        throw new RuntimeException(mensagemErro, e);
                    }
                }
                
                // Tratamento especial para rate limiting (425 TOO_EARLY)
                if (e.getStatusCode().value() == 425) {
                    String mensagemRateLimit = "API OMIE temporariamente bloqueada por excesso de requisições. ";
                    if (responseBody != null && responseBody.contains("segundos")) {
                        try {
                            // Tenta extrair o número de segundos da mensagem
                            int inicio = responseBody.indexOf("em ") + 3;
                            int fim = responseBody.indexOf(" segundos", inicio);
                            if (fim > inicio) {
                                String segundos = responseBody.substring(inicio, fim);
                                mensagemRateLimit += "Tente novamente em " + segundos + " segundos.";
                            } else {
                                mensagemRateLimit += "Aguarde alguns minutos antes de tentar novamente.";
                            }
                        } catch (Exception ex) {
                            mensagemRateLimit += "Aguarde alguns minutos antes de tentar novamente.";
                        }
                    } else {
                        mensagemRateLimit += "Aguarde alguns minutos antes de tentar novamente.";
                    }
                    
                    log.warn("Rate limit da API OMIE atingido: {}", mensagemRateLimit);
                    throw new RuntimeException(mensagemRateLimit, e);
                }
                
                // Extrai mensagem de erro da resposta
                String mensagemErro = "Erro ao chamar API OMIE: " + e.getStatusCode();
                if (responseBody != null && !responseBody.isEmpty()) {
                    if (responseBody.contains("faultstring")) {
                        try {
                            int inicio = responseBody.indexOf("\"faultstring\":\"") + 15;
                            int fim = responseBody.indexOf("\"", inicio);
                            if (fim > inicio) {
                                mensagemErro = responseBody.substring(inicio, fim);
                            }
                        } catch (Exception ex) {
                            // Ignora erro ao extrair
                        }
                    } else if (responseBody.contains("message")) {
                        try {
                            int inicio = responseBody.indexOf("\"message\":\"") + 11;
                            int fim = responseBody.indexOf("\"", inicio);
                            if (fim > inicio) {
                                mensagemErro = responseBody.substring(inicio, fim);
                            }
                        } catch (Exception ex) {
                            // Ignora erro ao extrair
                        }
                    }
                }
                
                log.error("Erro HTTP ao chamar API OMIE ({}): {} - {}", 
                        endpoint, e.getStatusCode(), responseBody);
                throw new RuntimeException(mensagemErro, e);
            } catch (Exception e) {
                if (e instanceof RuntimeException && e.getMessage() != null && e.getMessage().contains("Consumo redundante")) {
                    throw e; // Re-lança erros de consumo redundante sem retry adicional
                }
                log.error("Erro inesperado ao chamar API OMIE", e);
                throw new RuntimeException("Erro ao chamar API OMIE: " + e.getMessage(), e);
            }
        }
        
        throw new RuntimeException("Erro ao chamar API OMIE após " + (maxRetries + 1) + " tentativas");
    }

    /**
     * Lista contas a pagar do OMIE
     * Documentação: https://app.omie.com.br/api/v1/financas/contapagar/
     * Método: ListarContasPagar
     * 
     * Parâmetros do lcpListarRequest:
     * - pagina: Número da página (obrigatório)
     * - registros_por_pagina: Quantidade de registros por página (obrigatório, máximo 500)
     * - apenas_importado_api: Filtrar apenas registros importados via API ("S" ou "N")
     * - filtrar_por_data_de: Data inicial no formato DD/MM/YYYY (opcional)
     * - filtrar_por_data_ate: Data final no formato DD/MM/YYYY (opcional)
     * - filtrar_por_status: Filtrar por status (opcional)
     * - ordenar_por: Campo para ordenação (opcional)
     * - ordem_decrescente: Ordenação decrescente ("S" ou "N") (opcional)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> listarContasPagar(String dataInicio, String dataFim, 
                                                   Integer pagina, Integer registrosPorPagina) {
        if (mockEnabled) {
            return criarMovimentacoesMock(true, dataInicio, dataFim);
        }

        try {
            // Conforme documentação oficial do Omie: https://app.omie.com.br/api/v1/financas/contapagar/
            Map<String, Object> params = new HashMap<>();
            
            // Parâmetros obrigatórios
            params.put("pagina", pagina != null ? pagina : 1);
            params.put("registros_por_pagina", registrosPorPagina != null ? Math.min(registrosPorPagina, 500) : 50);
            params.put("apenas_importado_api", "N"); // Exibir todos os registros
            
            // Filtros de data (formato DD/MM/YYYY conforme documentação Omie)
            if (dataInicio != null && !dataInicio.isEmpty()) {
                try {
                // Converter de YYYY-MM-DD para DD/MM/YYYY
                String[] partes = dataInicio.split("-");
                if (partes.length == 3) {
                    params.put("filtrar_por_data_de", partes[2] + "/" + partes[1] + "/" + partes[0]);
                }
                } catch (Exception e) {
                    log.warn("Erro ao converter dataInicio para formato Omie: {}", dataInicio, e);
            }
            }
            
            if (dataFim != null && !dataFim.isEmpty()) {
                try {
                // Converter de YYYY-MM-DD para DD/MM/YYYY
                String[] partes = dataFim.split("-");
                if (partes.length == 3) {
                    params.put("filtrar_por_data_ate", partes[2] + "/" + partes[1] + "/" + partes[0]);
                    }
                } catch (Exception e) {
                    log.warn("Erro ao converter dataFim para formato Omie: {}", dataFim, e);
                }
            }
            
            log.info("Listando contas a pagar do OMIE: pagina={}, registros_por_pagina={}, dataInicio={}, dataFim={}",
                    params.get("pagina"), params.get("registros_por_pagina"), dataInicio, dataFim);
            log.debug("Parâmetros enviados para Omie: {}", params);

            Map<String, Object> response = executarChamadaApi("/financas/contapagar/", "ListarContasPagar", params);
            
            log.debug("Resposta completa do Omie: {}", response);
            
            // OMIE retorna a resposta no formato:
            // {
            //   "pagina": 1,
            //   "total_de_paginas": X,
            //   "registros": [...],
            //   "conta_pagar_cadastro": [...] (alternativo)
            // }
            List<Map<String, Object>> registros = new ArrayList<>();
            
            // Primeiro tenta conta_pagar_cadastro (formato mais comum)
            Object contaPagarCadastro = response.get("conta_pagar_cadastro");
            if (contaPagarCadastro instanceof List) {
                registros = (List<Map<String, Object>>) contaPagarCadastro;
                log.debug("Registros encontrados em 'conta_pagar_cadastro': {}", registros.size());
            }
            
            // Se não encontrou, tenta registros
            if (registros.isEmpty()) {
                Object registrosObj = response.get("registros");
                if (registrosObj instanceof List) {
                    registros = (List<Map<String, Object>>) registrosObj;
                    log.debug("Registros encontrados em 'registros': {}", registros.size());
                }
            }
            
            // Enriquece registros com nomes de clientes/fornecedores
            enriquecerComNomesClientes(registros);
            
            // Obtém total de registros e páginas
            Integer totalRegistros = (Integer) response.getOrDefault("total_de_registros", registros.size());
            Integer totalPaginas = (Integer) response.getOrDefault("total_de_paginas", 1);
            Integer paginaAtual = (Integer) response.getOrDefault("pagina", pagina != null ? pagina : 1);
            
            Map<String, Object> resultado = new HashMap<>();
            resultado.put("tipo", "CONTA_PAGAR");
            resultado.put("total_de_registros", totalRegistros);
            resultado.put("total_de_paginas", totalPaginas);
            resultado.put("pagina", paginaAtual);
            resultado.put("registros", registros);
            
            log.info("Contas a pagar retornadas: {} registros (total: {}, página: {}/{})", 
                    registros.size(), totalRegistros, paginaAtual, totalPaginas);
            return resultado;
        } catch (Exception e) {
            log.error("Erro ao listar contas a pagar do OMIE", e);
            throw new RuntimeException("Erro ao listar contas a pagar: " + e.getMessage(), e);
        }
    }

    /**
     * Lista contas a receber do OMIE
     * Documentação: https://app.omie.com.br/api/v1/financas/contareceber/
     * Método: ListarContasReceber
     * 
     * Parâmetros do lcrListarRequest:
     * - pagina: Número da página (obrigatório)
     * - registros_por_pagina: Quantidade de registros por página (obrigatório, máximo 500)
     * - apenas_importado_api: Filtrar apenas registros importados via API ("S" ou "N")
     * - filtrar_por_data_de: Data inicial no formato DD/MM/YYYY (opcional)
     * - filtrar_por_data_ate: Data final no formato DD/MM/YYYY (opcional)
     * - filtrar_por_status: Filtrar por status (opcional)
     * - ordenar_por: Campo para ordenação (opcional)
     * - ordem_decrescente: Ordenação decrescente ("S" ou "N") (opcional)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> listarContasReceber(String dataInicio, String dataFim, 
                                                     Integer pagina, Integer registrosPorPagina) {
        if (mockEnabled) {
            return criarMovimentacoesMock(false, dataInicio, dataFim);
        }

        try {
            // Conforme documentação oficial do Omie: https://app.omie.com.br/api/v1/financas/contareceber/
            Map<String, Object> params = new HashMap<>();
            
            // Parâmetros obrigatórios
            params.put("pagina", pagina != null ? pagina : 1);
            params.put("registros_por_pagina", registrosPorPagina != null ? Math.min(registrosPorPagina, 500) : 50);
            params.put("apenas_importado_api", "N"); // Exibir todos os registros
            
            // Filtros de data (formato DD/MM/YYYY conforme documentação Omie)
            if (dataInicio != null && !dataInicio.isEmpty()) {
                try {
                // Converter de YYYY-MM-DD para DD/MM/YYYY
                String[] partes = dataInicio.split("-");
                if (partes.length == 3) {
                    params.put("filtrar_por_data_de", partes[2] + "/" + partes[1] + "/" + partes[0]);
                }
                } catch (Exception e) {
                    log.warn("Erro ao converter dataInicio para formato Omie: {}", dataInicio, e);
            }
            }
            
            if (dataFim != null && !dataFim.isEmpty()) {
                try {
                // Converter de YYYY-MM-DD para DD/MM/YYYY
                String[] partes = dataFim.split("-");
                if (partes.length == 3) {
                    params.put("filtrar_por_data_ate", partes[2] + "/" + partes[1] + "/" + partes[0]);
                    }
                } catch (Exception e) {
                    log.warn("Erro ao converter dataFim para formato Omie: {}", dataFim, e);
                }
            }
            
            log.info("Listando contas a receber do OMIE: pagina={}, registros_por_pagina={}, dataInicio={}, dataFim={}",
                    params.get("pagina"), params.get("registros_por_pagina"), dataInicio, dataFim);
            log.debug("Parâmetros enviados para Omie: {}", params);

            Map<String, Object> response = executarChamadaApi("/financas/contareceber/", "ListarContasReceber", params);
            
            log.debug("Resposta completa do Omie: {}", response);
            
            // OMIE retorna a resposta no formato:
            // {
            //   "pagina": 1,
            //   "total_de_paginas": X,
            //   "registros": [...],
            //   "conta_receber_cadastro": [...] (alternativo)
            // }
            List<Map<String, Object>> registros = new ArrayList<>();
            
            // Primeiro tenta conta_receber_cadastro (formato mais comum)
            Object contaReceberCadastro = response.get("conta_receber_cadastro");
            if (contaReceberCadastro instanceof List) {
                registros = (List<Map<String, Object>>) contaReceberCadastro;
                log.debug("Registros encontrados em 'conta_receber_cadastro': {}", registros.size());
            }
            
            // Se não encontrou, tenta registros
            if (registros.isEmpty()) {
                Object registrosObj = response.get("registros");
                if (registrosObj instanceof List) {
                    registros = (List<Map<String, Object>>) registrosObj;
                    log.debug("Registros encontrados em 'registros': {}", registros.size());
                }
            }
            
            // Enriquece registros com nomes de clientes/fornecedores
            enriquecerComNomesClientes(registros);
            
            // Obtém total de registros e páginas
            Integer totalRegistros = (Integer) response.getOrDefault("total_de_registros", registros.size());
            Integer totalPaginas = (Integer) response.getOrDefault("total_de_paginas", 1);
            Integer paginaAtual = (Integer) response.getOrDefault("pagina", pagina != null ? pagina : 1);
            
            Map<String, Object> resultado = new HashMap<>();
            resultado.put("tipo", "CONTA_RECEBER");
            resultado.put("total_de_registros", totalRegistros);
            resultado.put("total_de_paginas", totalPaginas);
            resultado.put("pagina", paginaAtual);
            resultado.put("registros", registros);
            
            log.info("Contas a receber retornadas: {} registros (total: {}, página: {}/{})", 
                    registros.size(), totalRegistros, paginaAtual, totalPaginas);
            return resultado;
        } catch (Exception e) {
            log.error("Erro ao listar contas a receber do OMIE", e);
            throw new RuntimeException("Erro ao listar contas a receber: " + e.getMessage(), e);
        }
    }

    /**
     * Processa um movimento do OMIE e retorna uma movimentação normalizada
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> processarMovimento(Map<String, Object> movimento) {
        Map<String, Object> detalhesMov = (Map<String, Object>) movimento.get("detalhes");
        Map<String, Object> resumoMov = (Map<String, Object>) movimento.get("resumo");
        List<Map<String, Object>> categoriasMov = (List<Map<String, Object>>) movimento.get("categorias");
        
        if (detalhesMov == null) {
            return null;
        }
        
        // Determina se é receita ou despesa
        String natureza = (String) detalhesMov.getOrDefault("cNatureza", "");
        boolean isDebito = "P".equals(natureza); // P = Contas a Pagar (despesa)
        
        // Extrai valores do resumo (prioriza valores do resumo)
        // IMPORTANTE: nValLiquido pode ser 0 para títulos não liquidados, então verifica se é null
        Object valorObj = null;
        if (resumoMov != null) {
            // Usa valor líquido do resumo (nValLiquido) se não for null
            valorObj = resumoMov.get("nValLiquido");
            if (valorObj == null) {
                // Se nValLiquido é null, tenta nValAberto (valor em aberto)
                valorObj = resumoMov.get("nValAberto");
            }
            if (valorObj == null) {
                // Se ainda não tem, tenta nValPago
                valorObj = resumoMov.get("nValPago");
            }
        }
        
        // Fallback para valor do título se resumo não tiver ou se nValLiquido for 0
        // Para títulos não liquidados, nValLiquido pode ser 0, então usa nValorTitulo
        if (valorObj == null || (valorObj instanceof Number && ((Number) valorObj).doubleValue() == 0.0)) {
            Object valorTitulo = detalhesMov.get("nValorTitulo");
            if (valorTitulo != null) {
                valorObj = valorTitulo;
            }
        }
        
        // Para movimentações de conta corrente, usa nValorMovCC
        if (valorObj == null || (valorObj instanceof Number && ((Number) valorObj).doubleValue() == 0.0)) {
            Object valorMovCC = detalhesMov.get("nValorMovCC");
            if (valorMovCC != null) {
                valorObj = valorMovCC;
            }
        }
        
        double valor = 0.0;
        if (valorObj != null) {
            valor = valorObj instanceof Number ? 
                                    ((Number) valorObj).doubleValue() : 
                                    Double.parseDouble(valorObj.toString());
        }
        
        // Extrai categoria (primeira categoria encontrada)
        String categoriaMov = "Sem categoria";
        if (categoriasMov != null && !categoriasMov.isEmpty()) {
            Map<String, Object> primeiraCategoria = categoriasMov.get(0);
            Object codCateg = primeiraCategoria.get("cCodCateg");
            if (codCateg != null) {
                categoriaMov = codCateg.toString();
            }
        } else if (detalhesMov.get("cCodCateg") != null) {
            categoriaMov = detalhesMov.get("cCodCateg").toString();
        }
        
        // Detecta tipo de movimentação (conta corrente vs título)
        String grupo = (String) detalhesMov.getOrDefault("cGrupo", "");
        boolean isContaCorrente = grupo.contains("CONTA_CORRENTE");
        
        // Para conta corrente, usa nCodMovCC como código; para títulos, usa nCodTitulo
        Object codigoLancamento = isContaCorrente ? detalhesMov.get("nCodMovCC") : detalhesMov.get("nCodTitulo");
        
        // Para conta corrente, data de vencimento pode ser a data de pagamento ou null
        Object dataVencimento = detalhesMov.get("dDtVenc");
        if (dataVencimento == null && isContaCorrente) {
            // Para conta corrente sem data de vencimento, usa data de pagamento como referência
            dataVencimento = detalhesMov.get("dDtPagamento");
        }
        
        // Constrói movimentação normalizada
        Map<String, Object> movNormalizada = new HashMap<>();
        movNormalizada.put("codigo_lancamento_omie", codigoLancamento != null ? codigoLancamento : 0);
        movNormalizada.put("codigo_lancamento_integracao", detalhesMov.get("cCodIntTitulo"));
        movNormalizada.put("numero_documento", detalhesMov.get("cNumTitulo"));
        movNormalizada.put("data_emissao", detalhesMov.get("dDtEmissao"));
        movNormalizada.put("data_vencimento", dataVencimento);
        movNormalizada.put("data_previsao", detalhesMov.get("dDtPrevisao"));
        movNormalizada.put("data_pagamento", detalhesMov.get("dDtPagamento"));
        movNormalizada.put("data_registro", detalhesMov.get("dDtIncDe")); // Data de inclusão
        movNormalizada.put("codigo_cliente_fornecedor", detalhesMov.get("nCodCliente"));
        movNormalizada.put("cpf_cnpj_cliente", detalhesMov.get("cCPFCNPJCliente"));
        movNormalizada.put("numero_pedido", detalhesMov.get("cNumOS"));
        movNormalizada.put("status_titulo", detalhesMov.get("cStatus"));
        movNormalizada.put("tipo_documento", detalhesMov.get("cTipo"));
        movNormalizada.put("operacao", detalhesMov.get("cOperacao"));
        movNormalizada.put("numero_documento_fiscal", detalhesMov.get("cNumDocFiscal"));
        movNormalizada.put("codigo_barras", detalhesMov.get("cCodigoBarras"));
        movNormalizada.put("codigo_vendedor", detalhesMov.get("nCodVendedor"));
        movNormalizada.put("categoria", categoriaMov);
        movNormalizada.put("codigo_categoria", categoriaMov);
        // Usa o valor calculado (que já considera nValorMovCC para conta corrente)
        movNormalizada.put("valor_documento", valor);
        // Adiciona flag para identificar conta corrente
        movNormalizada.put("is_conta_corrente", isContaCorrente);
        movNormalizada.put("tipo", isDebito ? "DESPESA" : "RECEITA");
        movNormalizada.put("debito", isDebito);
        movNormalizada.put("natureza", natureza);
        
        // Adiciona informações do resumo
        if (resumoMov != null) {
            movNormalizada.put("valor_pago", resumoMov.get("nValPago"));
            movNormalizada.put("valor_aberto", resumoMov.get("nValAberto"));
            movNormalizada.put("valor_desconto", resumoMov.get("nDesconto"));
            movNormalizada.put("valor_juros", resumoMov.get("nJuros"));
            movNormalizada.put("valor_multa", resumoMov.get("nMulta"));
            movNormalizada.put("valor_liquido", resumoMov.get("nValLiquido"));
            movNormalizada.put("liquidado", resumoMov.get("cLiquidado"));
        }
        
        // Adiciona categorias completas
        movNormalizada.put("categorias", categoriasMov);
        
        // Adiciona departamentos se houver
        movNormalizada.put("departamentos", movimento.get("departamentos"));
        
        // Preserva dados originais completos
        movNormalizada.put("_detalhes", detalhesMov);
        movNormalizada.put("_resumo", resumoMov);
        movNormalizada.put("_movimento_completo", movimento);
        
        return movNormalizada;
    }

    /**
     * Pesquisa movimentações financeiras do OMIE usando o endpoint de Movimentos Financeiros
     * Documentação: https://app.omie.com.br/api/v1/financas/mf/
     * Usa o método ListarMovimentos que retorna Contas a Pagar, Contas a Receber e Lançamentos do Conta Corrente
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> pesquisarMovimentacoes(
            String idEmpresa,
            String dataInicio,
            String dataFim,
            Integer pagina,
            Integer registrosPorPagina,
            String tipo,
            String categoria,
            String textoPesquisa) {
        
        if (mockEnabled) {
            return criarMovimentacoesCombinadasMock(dataInicio, dataFim);
        }

        try {
            log.info("Pesquisando movimentações financeiras do OMIE (endpoint MF): dataInicio={}, dataFim={}, pagina={}, registrosPorPagina={}, tipo={}, categoria={}, textoPesquisa={}",
                    dataInicio, dataFim, pagina, registrosPorPagina, tipo, categoria, textoPesquisa);

            // Determina se precisa buscar todas as páginas para calcular totais corretos
            // Se não há filtros de data e está pedindo muitos registros (cache), busca todas as páginas
            boolean semFiltroData = (dataInicio == null || dataInicio.isEmpty()) && (dataFim == null || dataFim.isEmpty());
            boolean pedindoCacheCompleto = registrosPorPagina != null && registrosPorPagina >= 500;
            boolean precisaBuscarTodasPaginas = semFiltroData && pedindoCacheCompleto;
            
            // Normaliza movimentos para o formato esperado pelo frontend
            List<Map<String, Object>> movimentacoesNormalizadas = new ArrayList<>();
            double totalReceitas = 0.0;
            double totalDespesas = 0.0;
            
            Integer nPagina = pagina != null ? pagina : 1;
            Integer nTotPaginas = 1;
            Integer nTotRegistros = 0;
            
            if (precisaBuscarTodasPaginas) {
                // Busca todas as páginas para calcular totais corretos
                log.info("Buscando todas as páginas do OMIE para calcular totais corretos (sem filtros de data)");
                int paginaAtual = 1;
                int registrosPorPaginaOmie = 500; // Máximo permitido pelo OMIE
                
                do {
                    Map<String, Object> params = new HashMap<>();
                    params.put("nPagina", paginaAtual);
                    params.put("nRegPorPagina", registrosPorPaginaOmie);
                    
                    log.debug("Buscando página {} do OMIE", paginaAtual);
                    Map<String, Object> response = executarChamadaApi("/financas/mf/", "ListarMovimentos", params);
                    
                    List<Map<String, Object>> movimentosPagina = new ArrayList<>();
                    Object movimentosObj = response.get("movimentos");
                    if (movimentosObj instanceof List) {
                        movimentosPagina = (List<Map<String, Object>>) movimentosObj;
                    }
                    
                    // Atualiza informações de paginação na primeira página
                    if (paginaAtual == 1) {
                        nTotPaginas = (Integer) response.getOrDefault("nTotPaginas", 1);
                        nTotRegistros = (Integer) response.getOrDefault("nTotRegistros", movimentosPagina.size());
                    }
                    
                    // Processa movimentos desta página
                    for (Map<String, Object> movimento : movimentosPagina) {
                        Map<String, Object> movNormalizada = processarMovimento(movimento);
                        if (movNormalizada != null) {
                            movimentacoesNormalizadas.add(movNormalizada);
                            // Acumula totais
                            Object valorObj = movNormalizada.get("valor_documento");
                            if (valorObj != null) {
                                double valor = valorObj instanceof Number ? 
                                        ((Number) valorObj).doubleValue() : 
                                        Double.parseDouble(valorObj.toString());
                                Boolean debito = (Boolean) movNormalizada.get("debito");
                                if (Boolean.TRUE.equals(debito)) {
                                    totalDespesas += valor;
                                } else {
                                    totalReceitas += valor;
                                }
                            }
                        }
                    }
                    
                    paginaAtual++;
                } while (paginaAtual <= nTotPaginas && movimentacoesNormalizadas.size() < nTotRegistros);
                
                log.info("Todas as páginas buscadas: {} movimentações processadas (total: {})", 
                        movimentacoesNormalizadas.size(), nTotRegistros);
            } else {
                // Busca apenas a página solicitada (comportamento normal)
                Map<String, Object> params = new HashMap<>();
                params.put("nPagina", nPagina);
                params.put("nRegPorPagina", registrosPorPagina != null ? Math.min(registrosPorPagina, 500) : 50);
                
                log.debug("Parâmetros enviados para Omie ListarMovimentos: {}", params);
                
                Map<String, Object> response = executarChamadaApi("/financas/mf/", "ListarMovimentos", params);
                
                log.debug("Resposta completa do Omie (MF): {}", response);
                
                List<Map<String, Object>> movimentos = new ArrayList<>();
                Object movimentosObj = response.get("movimentos");
                if (movimentosObj instanceof List) {
                    movimentos = (List<Map<String, Object>>) movimentosObj;
                }
                
                // Extrai informações de paginação
                nPagina = (Integer) response.getOrDefault("nPagina", nPagina);
                nTotPaginas = (Integer) response.getOrDefault("nTotPaginas", 1);
                nTotRegistros = (Integer) response.getOrDefault("nTotRegistros", movimentos.size());
                
                log.info("Movimentações retornadas do OMIE (MF): {} registros (total: {}, página: {}/{})", 
                        movimentos.size(), nTotRegistros, nPagina, nTotPaginas);
                
                // Processa movimentos da página atual
                for (Map<String, Object> movimento : movimentos) {
                    Map<String, Object> movNormalizada = processarMovimento(movimento);
                    if (movNormalizada != null) {
                        movimentacoesNormalizadas.add(movNormalizada);
                        // Acumula totais
                        Object valorObj = movNormalizada.get("valor_documento");
                        if (valorObj != null) {
                            double valor = valorObj instanceof Number ? 
                                    ((Number) valorObj).doubleValue() : 
                                    Double.parseDouble(valorObj.toString());
                            Boolean debito = (Boolean) movNormalizada.get("debito");
                            if (Boolean.TRUE.equals(debito)) {
                                totalDespesas += valor;
                            } else {
                                totalReceitas += valor;
                            }
                        }
                    }
                }
            }
            
            // Enriquece movimentações com nomes de clientes/fornecedores
            enriquecerComNomesClientes(movimentacoesNormalizadas);
            
            // Enriquece movimentações com nomes de formas de pagamento
            enriquecerComNomesFormasPagamento(movimentacoesNormalizadas);
            
            // Aplica filtros no backend (data, natureza, categoria, texto de pesquisa)
            List<Map<String, Object>> movimentacoesFiltradas = movimentacoesNormalizadas;
            
            // Filtro por data (início e fim)
            if ((dataInicio != null && !dataInicio.isEmpty()) || (dataFim != null && !dataFim.isEmpty())) {
                try {
                    String dataInicioStr = null;
                    String dataFimStr = null;
                    
                    if (dataInicio != null && !dataInicio.isEmpty()) {
                        String[] partes = dataInicio.split("-");
                        if (partes.length == 3) {
                            dataInicioStr = partes[2] + "/" + partes[1] + "/" + partes[0];
                        }
                    }
                    
                    if (dataFim != null && !dataFim.isEmpty()) {
                        String[] partes = dataFim.split("-");
                        if (partes.length == 3) {
                            dataFimStr = partes[2] + "/" + partes[1] + "/" + partes[0];
                        }
                    }
                    
                    final String dataInicioFinal = dataInicioStr;
                    final String dataFimFinal = dataFimStr;
                    
                    movimentacoesFiltradas = movimentacoesFiltradas.stream()
                            .filter(mov -> {
                                // Tenta data de vencimento primeiro, depois data de pagamento (para conta corrente)
                                String dataVenc = getStringValue(mov, "data_vencimento");
                                if (dataVenc == null || dataVenc.isEmpty()) {
                                    // Se não tem data de vencimento, tenta data de pagamento
                                    dataVenc = getStringValue(mov, "data_pagamento");
                                }
                                if (dataVenc == null || dataVenc.isEmpty()) {
                                    // Se não tem nenhuma, tenta data de emissão
                                    dataVenc = getStringValue(mov, "data_emissao");
                                }
                                
                                // Se ainda não tem data, mantém a movimentação (não filtra)
                                if (dataVenc == null || dataVenc.isEmpty()) {
                                    return true;
                                }
                                
                                // Compara datas no formato DD/MM/YYYY
                                boolean dentroRange = true;
                                if (dataInicioFinal != null) {
                                    dentroRange = dataVenc.compareTo(dataInicioFinal) >= 0;
                                }
                                if (dentroRange && dataFimFinal != null) {
                                    dentroRange = dataVenc.compareTo(dataFimFinal) <= 0;
                                }
                                return dentroRange;
                            })
                            .collect(Collectors.toList());
                    
                    log.info("Filtro de data aplicado: {} de {} movimentações", 
                            movimentacoesFiltradas.size(), movimentacoesNormalizadas.size());
                } catch (Exception e) {
                    log.warn("Erro ao aplicar filtro de data: {}", e.getMessage());
                }
            }
            
            // Filtro por natureza (tipo: receita/despesa)
            if (tipo != null && !tipo.isEmpty()) {
                final boolean isDespesa = "despesa".equalsIgnoreCase(tipo);
                movimentacoesFiltradas = movimentacoesFiltradas.stream()
                        .filter(mov -> {
                            Boolean debito = (Boolean) mov.get("debito");
                            String natureza = getStringValue(mov, "natureza");
                            return (isDespesa && (Boolean.TRUE.equals(debito) || "P".equals(natureza))) ||
                                   (!isDespesa && (!Boolean.TRUE.equals(debito) && !"P".equals(natureza)));
                        })
                        .collect(Collectors.toList());
                
                log.info("Filtro de tipo aplicado: {} de {} movimentações", 
                        movimentacoesFiltradas.size(), movimentacoesNormalizadas.size());
            }
            
            // Filtro por categoria
            if (categoria != null && !categoria.isEmpty()) {
                final String categoriaFiltro = categoria;
                movimentacoesFiltradas = movimentacoesFiltradas.stream()
                        .filter(mov -> {
                            String categoriaMov = getStringValue(mov, "categoria", "codigo_categoria");
                            return categoriaMov != null && categoriaMov.equalsIgnoreCase(categoriaFiltro);
                        })
                        .collect(Collectors.toList());
                
                log.info("Filtro de categoria aplicado: {} de {} movimentações", 
                        movimentacoesFiltradas.size(), movimentacoesNormalizadas.size());
            }
            
            // Filtro por texto de pesquisa
            if (textoPesquisa != null && !textoPesquisa.trim().isEmpty()) {
                String texto = textoPesquisa.toLowerCase();
                movimentacoesFiltradas = movimentacoesFiltradas.stream()
                        .filter(mov -> {
                            String numeroDoc = getStringValue(mov, "numero_documento");
                            String numDocFiscal = getStringValue(mov, "numero_documento_fiscal");
                            String codBarras = getStringValue(mov, "codigo_barras");
                            String numPedido = getStringValue(mov, "numero_pedido");
                            
                            return (numeroDoc != null && numeroDoc.toLowerCase().contains(texto)) ||
                                   (numDocFiscal != null && numDocFiscal.toLowerCase().contains(texto)) ||
                                   (codBarras != null && codBarras.toLowerCase().contains(texto)) ||
                                   (numPedido != null && numPedido.toLowerCase().contains(texto));
                        })
                        .collect(Collectors.toList());
                
                log.info("Filtro de texto aplicado: {} de {} movimentações", 
                        movimentacoesFiltradas.size(), movimentacoesNormalizadas.size());
            }
            
            // Calcula totais finais (ajustados se há filtros aplicados)
            // Se filtros foram aplicados, recalcula totais apenas das movimentações filtradas
            boolean temFiltros = (dataInicio != null && !dataInicio.isEmpty()) || 
                                (dataFim != null && !dataFim.isEmpty()) ||
                                (tipo != null && !tipo.isEmpty()) ||
                                (categoria != null && !categoria.isEmpty()) ||
                                (textoPesquisa != null && !textoPesquisa.trim().isEmpty());
            
            if (temFiltros) {
                // Recalcula totais apenas das movimentações filtradas
                totalReceitas = 0.0;
                totalDespesas = 0.0;
                for (Map<String, Object> mov : movimentacoesFiltradas) {
                    Object valorObj = mov.get("valor_documento");
                    if (valorObj != null) {
                        double valor = valorObj instanceof Number ? 
                                ((Number) valorObj).doubleValue() : 
                                Double.parseDouble(valorObj.toString());
                        Boolean debito = (Boolean) mov.get("debito");
                        if (Boolean.TRUE.equals(debito)) {
                            totalDespesas += valor;
                        } else {
                            totalReceitas += valor;
                        }
                    }
                }
            }

            // Verifica se os nomes estão presentes nas movimentações filtradas
            int movimentacoesComNome = 0;
            for (Map<String, Object> mov : movimentacoesFiltradas) {
                if (mov.get("nome_cliente_fornecedor") != null) {
                    movimentacoesComNome++;
                }
            }
            log.info("Movimentações com nome de cliente/fornecedor: {} de {}", movimentacoesComNome, movimentacoesFiltradas.size());
            
            // Aplica paginação nas movimentações filtradas antes de retornar
            List<Map<String, Object>> movimentacoesPaginadas = movimentacoesFiltradas;
            int registrosPorPaginaFinal = registrosPorPagina != null ? registrosPorPagina : 50;
            int totalMovimentacoesFiltradas = movimentacoesFiltradas.size();
            
            if (pagina != null && pagina > 0 && registrosPorPaginaFinal > 0) {
                int inicio = (pagina - 1) * registrosPorPaginaFinal;
                int fim = inicio + registrosPorPaginaFinal;
                if (inicio < movimentacoesFiltradas.size()) {
                    movimentacoesPaginadas = movimentacoesFiltradas.subList(
                        inicio, Math.min(fim, movimentacoesFiltradas.size()));
                } else {
                    movimentacoesPaginadas = new ArrayList<>();
                }
            }
            
            // Determina o total a retornar:
            // - Se há filtros aplicados, retorna o total das movimentações filtradas
            // - Se não há filtros, retorna o total geral do OMIE (nTotRegistros)
            int totalRetornar = temFiltros ? totalMovimentacoesFiltradas : nTotRegistros;
            
            // Calcula total de páginas baseado no total retornado
            int totalPaginasRetornar = temFiltros 
                ? (int) Math.ceil((double) totalMovimentacoesFiltradas / registrosPorPaginaFinal)
                : nTotPaginas;
            
            // Constrói resultado final
            Map<String, Object> resultado = new HashMap<>();
            resultado.put("movimentacoes", movimentacoesPaginadas);
            resultado.put("total", totalRetornar);
            resultado.put("totalReceitas", totalReceitas);
            resultado.put("totalDespesas", totalDespesas);
            resultado.put("saldoLiquido", totalReceitas - totalDespesas);
            resultado.put("pagina", nPagina);
            resultado.put("totalPaginas", totalPaginasRetornar);
            resultado.put("registrosPorPagina", registrosPorPagina);
            resultado.put("dataInicio", dataInicio);
            resultado.put("dataFim", dataFim);
            
            log.info("Movimentações retornadas (MF): {} registros na página (total: {}, filtros: {}), Receitas: {}, Despesas: {}, Saldo: {}", 
                    movimentacoesPaginadas.size(), totalRetornar, temFiltros ? "sim" : "não", totalReceitas, totalDespesas, totalReceitas - totalDespesas);
            
            return resultado;
        } catch (Exception e) {
            log.error("Erro ao pesquisar movimentações do OMIE", e);
            throw new RuntimeException("Erro ao pesquisar movimentações: " + e.getMessage(), e);
        }
    }

    /**
     * Aplica filtros nas movimentações (tipo, categoria, texto de pesquisa)
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> aplicarFiltros(List<Map<String, Object>> movimentacoes, 
                                                     String tipo, String categoria, String textoPesquisa) {
        if (movimentacoes == null || movimentacoes.isEmpty()) {
            return movimentacoes;
        }
        
        return movimentacoes.stream()
                .filter(mov -> {
                    // Filtro por tipo (receita/despesa)
                    if (tipo != null && !tipo.isEmpty()) {
                        Boolean debito = (Boolean) mov.get("debito");
                        String tipoMov = (String) mov.get("tipo");
                        
                        if ("receita".equalsIgnoreCase(tipo)) {
                            if (Boolean.TRUE.equals(debito) || "DESPESA".equalsIgnoreCase(tipoMov)) {
                                return false;
                            }
                        } else if ("despesa".equalsIgnoreCase(tipo)) {
                            if (!Boolean.TRUE.equals(debito) && !"DESPESA".equalsIgnoreCase(tipoMov)) {
                                return false;
                            }
                        }
                    }
                    
                    // Filtro por categoria
                    if (categoria != null && !categoria.isEmpty()) {
                        String categoriaMov = extrairCategoria(mov);
                        if (categoriaMov == null || !categoriaMov.equalsIgnoreCase(categoria)) {
                            return false;
                        }
                    }
                    
                    // Filtro por texto de pesquisa (busca em nome, observação, cliente/fornecedor)
                    if (textoPesquisa != null && !textoPesquisa.trim().isEmpty()) {
                        String texto = textoPesquisa.toLowerCase();
                        String nome = getStringValue(mov, "nome_cliente_fornecedor", "nNomeFantasia", "nome");
                        String observacao = getStringValue(mov, "observacao", "cObservacao", "cObs");
                        String descricao = getStringValue(mov, "descricao", "cDescricao");
                        
                        boolean encontrado = (nome != null && nome.toLowerCase().contains(texto)) ||
                                           (observacao != null && observacao.toLowerCase().contains(texto)) ||
                                           (descricao != null && descricao.toLowerCase().contains(texto));
                        
                        if (!encontrado) {
                            return false;
                        }
                    }
                    
                    return true;
                })
                .collect(Collectors.toList());
    }
    
    /**
     * Extrai categoria de uma movimentação
     */
    private String extrairCategoria(Map<String, Object> mov) {
        // Tenta vários campos possíveis de categoria
        Object categoriaObj = mov.get("categoria");
        if (categoriaObj == null) {
            categoriaObj = mov.get("cCategoria");
        }
        if (categoriaObj == null) {
            categoriaObj = mov.get("categoria_financeira");
        }
        
        if (categoriaObj != null) {
            String categoria = categoriaObj.toString();
            // Se a categoria tem hierarquia (ex: "Pai > Filho"), pega apenas a raiz
            if (categoria.contains(">")) {
                categoria = categoria.split(">")[0].trim();
            }
            return categoria;
        }
        
        return null;
    }
    
    /**
     * Enriquece movimentações com nomes de clientes/fornecedores
     * Busca os nomes usando a API do Omie quando o código do cliente está disponível
     */
    @SuppressWarnings("unchecked")
    private void enriquecerComNomesClientes(List<Map<String, Object>> movimentacoes) {
        if (movimentacoes == null || movimentacoes.isEmpty()) {
            return;
        }
        
        // Coleta códigos únicos de clientes/fornecedores (usando String para garantir consistência)
        Set<String> codigosClientes = new HashSet<>();
        for (Map<String, Object> mov : movimentacoes) {
            Object codigoCliente = mov.get("codigo_cliente_fornecedor");
            if (codigoCliente != null) {
                String codigoStr = codigoCliente.toString().trim();
                // Ignora apenas strings vazias ou "null", mas aceita "0" e números válidos
                if (!codigoStr.isEmpty() && !codigoStr.equalsIgnoreCase("null")) {
                    codigosClientes.add(codigoStr);
                }
            }
        }
        
        log.debug("Coletados {} códigos únicos de clientes/fornecedores para enriquecimento", codigosClientes.size());
        
        if (codigosClientes.isEmpty()) {
            log.debug("Nenhum código de cliente/fornecedor encontrado para enriquecer");
            return;
        }
        
        // Cria mapa de código -> nome e razão social
        Map<String, String> mapaNomes = new HashMap<>();
        Map<String, String> mapaRazoesSociais = new HashMap<>();
        
        // Busca nomes e razões sociais dos clientes/fornecedores
        int clientesBuscados = 0;
        int clientesEncontrados = 0;
        for (String codigoStr : codigosClientes) {
            try {
                clientesBuscados++;
                Map<String, String> dadosCliente = buscarDadosClientePorCodigo(codigoStr);
                if (dadosCliente != null && !dadosCliente.isEmpty()) {
                    clientesEncontrados++;
                    String nome = dadosCliente.get("nome");
                    String razaoSocial = dadosCliente.get("razao_social");
                    if (nome != null && !nome.isEmpty()) {
                        mapaNomes.put(codigoStr, nome);
                        log.debug("Nome encontrado para cliente {}: {}", codigoStr, nome);
                    }
                    if (razaoSocial != null && !razaoSocial.isEmpty()) {
                        mapaRazoesSociais.put(codigoStr, razaoSocial);
                        log.debug("Razão social encontrada para cliente {}: {}", codigoStr, razaoSocial);
                    }
                } else {
                    log.debug("Dados do cliente {} não encontrados na API Omie", codigoStr);
                }
            } catch (Exception e) {
                log.warn("Erro ao buscar dados do cliente {}: {}", codigoStr, e.getMessage());
            }
        }
        
        log.info("Busca de clientes: {} buscados, {} encontrados, {} nomes, {} razões sociais no mapa", 
                clientesBuscados, clientesEncontrados, mapaNomes.size(), mapaRazoesSociais.size());
        
        // Enriquece movimentações com os nomes e razões sociais encontrados
        int enriquecidas = 0;
        int razoesSociaisAdicionadas = 0;
        for (Map<String, Object> mov : movimentacoes) {
            Object codigoCliente = mov.get("codigo_cliente_fornecedor");
            if (codigoCliente != null) {
                String codigoStr = codigoCliente.toString().trim();
                // Ignora apenas strings vazias ou "null"
                if (codigoStr.isEmpty() || codigoStr.equalsIgnoreCase("null")) {
                    continue;
                }
                
                String nome = mapaNomes.get(codigoStr);
                String razaoSocial = mapaRazoesSociais.get(codigoStr);
                
                if (nome != null && !nome.isEmpty()) {
                    mov.put("nome_cliente_fornecedor", nome);
                    enriquecidas++;
                    log.debug("Movimentação enriquecida: código={}, nome={}", codigoStr, nome);
                } else {
                    log.debug("Nome não encontrado no mapa para código: {} (mapa tem {} entradas)", 
                            codigoStr, mapaNomes.size());
                }
                
                if (razaoSocial != null && !razaoSocial.isEmpty()) {
                    mov.put("razao_social_cliente_fornecedor", razaoSocial);
                    razoesSociaisAdicionadas++;
                    log.debug("Razão social adicionada: código={}, razão_social={}", codigoStr, razaoSocial);
                }
            }
        }
        
        log.info("Enriquecidas {} de {} movimentações com nomes de clientes/fornecedores ({} nomes, {} razões sociais)", 
                enriquecidas, movimentacoes.size(), mapaNomes.size(), razoesSociaisAdicionadas);
    }
    
    /**
     * Enriquece movimentações com nomes de formas de pagamento
     * Busca os nomes usando a API do Omie quando o código do tipo de documento está disponível
     */
    @SuppressWarnings("unchecked")
    private void enriquecerComNomesFormasPagamento(List<Map<String, Object>> movimentacoes) {
        if (movimentacoes == null || movimentacoes.isEmpty()) {
            return;
        }
        
        // Coleta códigos únicos de tipos de documento
        Set<Object> codigosTipoDoc = new HashSet<>();
        for (Map<String, Object> mov : movimentacoes) {
            Object codigoTipoDoc = mov.get("tipo_documento");
            if (codigoTipoDoc != null && !codigoTipoDoc.toString().isEmpty()) {
                codigosTipoDoc.add(codigoTipoDoc);
            }
        }
        
        if (codigosTipoDoc.isEmpty()) {
            return;
        }
        
        // Cria mapa de código -> nome
        Map<String, String> mapaNomes = new HashMap<>();
        
        // Adiciona mapeamento conhecido para código "99999" (Outros) sem chamar API
        if (codigosTipoDoc.contains("99999")) {
            mapaNomes.put("99999", "Outros");
        }
        
        // Busca nomes dos tipos de documento (exceto 99999 que já foi mapeado)
        for (Object codigoObj : codigosTipoDoc) {
            String codigoStr = codigoObj.toString();
            // Pula código 99999 que já foi mapeado
            if ("99999".equals(codigoStr)) {
                continue;
            }
            try {
                String nome = buscarNomeTipoDocumentoPorCodigo(codigoStr);
                if (nome != null && !nome.isEmpty()) {
                    mapaNomes.put(codigoStr, nome);
                }
            } catch (Exception e) {
                log.warn("Erro ao buscar nome do tipo de documento {}: {}", codigoObj, e.getMessage());
            }
        }
        
        // Enriquece movimentações com os nomes encontrados
        int enriquecidas = 0;
        for (Map<String, Object> mov : movimentacoes) {
            Object codigoTipoDoc = mov.get("tipo_documento");
            if (codigoTipoDoc != null) {
                String codigoStr = codigoTipoDoc.toString();
                String nome = mapaNomes.get(codigoStr);
                if (nome != null && !nome.isEmpty()) {
                    mov.put("nome_forma_pagamento", nome);
                    enriquecidas++;
                    log.debug("Movimentação enriquecida com forma de pagamento: código={}, nome={}", codigoStr, nome);
                }
            }
        }
        
        log.info("Enriquecidas {} de {} movimentações com nomes de formas de pagamento ({} nomes encontrados)", 
                enriquecidas, movimentacoes.size(), mapaNomes.size());
    }
    
    /**
     * Busca nome do tipo de documento/forma de pagamento pelo código usando a API do Omie
     * Documentação: https://app.omie.com.br/api/v1/geral/tiposdoc/
     * Método: PesquisarTipoDocumento
     */
    @SuppressWarnings("unchecked")
    private String buscarNomeTipoDocumentoPorCodigo(String codigoTipoDoc) {
        if (mockEnabled || codigoTipoDoc == null || codigoTipoDoc.isEmpty()) {
            return null;
        }
        
        try {
            Map<String, Object> params = new HashMap<>();
            // Omie espera codigo como parâmetro para PesquisarTipoDocumento
            params.put("codigo", codigoTipoDoc);
            
            Map<String, Object> response = executarChamadaApi("/geral/tiposdoc/", "PesquisarTipoDocumento", params);
            
            log.debug("Resposta PesquisarTipoDocumento para código {}: {}", codigoTipoDoc, response);
            
            // Omie retorna o tipo de documento em tipo_documento_cadastro ou na raiz
            Object tipoDocObj = response.get("tipo_documento_cadastro");
            if (tipoDocObj == null) {
                tipoDocObj = response.get("tipo_documento");
            }
            // Se ainda não encontrou, pode estar na raiz da resposta
            if (tipoDocObj == null && response.containsKey("codigo")) {
                tipoDocObj = response;
            }
            
            if (tipoDocObj instanceof Map) {
                Map<String, Object> tipoDoc = (Map<String, Object>) tipoDocObj;
                log.debug("Dados do tipo de documento {}: {}", codigoTipoDoc, tipoDoc);
                
                // Busca o nome do tipo de documento
                Object nome = tipoDoc.get("nome");
                if (nome != null && !nome.toString().isEmpty()) {
                    log.debug("Nome encontrado para tipo de documento {}: {}", codigoTipoDoc, nome);
                    return nome.toString();
                }
                
                Object descricao = tipoDoc.get("descricao");
                if (descricao != null && !descricao.toString().isEmpty()) {
                    log.debug("Descrição encontrada para tipo de documento {}: {}", codigoTipoDoc, descricao);
                    return descricao.toString();
                }
            }
            
            log.warn("Nome não encontrado para tipo de documento {} na resposta: {}", codigoTipoDoc, response);
            return null;
        } catch (Exception e) {
            // Log apenas em debug para não poluir logs quando tipo não for encontrado
            log.debug("Erro ao buscar tipo de documento {}: {}", codigoTipoDoc, e.getMessage());
            return null;
        }
    }
    
    /**
     * Busca dados do cliente/fornecedor pelo código usando a API do Omie
     * Retorna um mapa com "nome" e "razao_social"
     * Documentação: https://app.omie.com.br/api/v1/geral/clientes/
     * Método: ConsultarCliente
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> buscarDadosClientePorCodigo(String codigoCliente) {
        if (mockEnabled || codigoCliente == null || codigoCliente.isEmpty()) {
            return null;
        }
        
        try {
            Map<String, Object> params = new HashMap<>();
            // Omie espera codigo_cliente_omie como parâmetro
            params.put("codigo_cliente_omie", codigoCliente);
            
            Map<String, Object> response = executarChamadaApi("/geral/clientes/", "ConsultarCliente", params);
            
            log.debug("Resposta ConsultarCliente para código {}: {}", codigoCliente, response);
            
            // Omie pode retornar o cliente em cliente_cadastro, cliente, ou diretamente no nível raiz
            Map<String, Object> cliente = null;
            Object clienteObj = response.get("cliente_cadastro");
            if (clienteObj instanceof Map) {
                cliente = (Map<String, Object>) clienteObj;
                log.debug("Cliente encontrado em 'cliente_cadastro'");
            } else {
                clienteObj = response.get("cliente");
                if (clienteObj instanceof Map) {
                    cliente = (Map<String, Object>) clienteObj;
                    log.debug("Cliente encontrado em 'cliente'");
                }
            }
            
            // Se não encontrou em cliente_cadastro ou cliente, verifica se os dados estão no nível raiz
            // A API do Omie pode retornar os dados diretamente no nível raiz
            if (cliente == null) {
                // Verifica se há campos de cliente no nível raiz
                boolean temRazaoSocial = response.containsKey("razao_social") && response.get("razao_social") != null;
                boolean temNomeFantasia = response.containsKey("nome_fantasia") && response.get("nome_fantasia") != null;
                boolean temCodigoCliente = response.containsKey("codigo_cliente_omie");
                
                if (temRazaoSocial || temNomeFantasia || temCodigoCliente) {
                    cliente = response;
                    log.debug("Cliente encontrado no nível raiz da resposta (razao_social={}, nome_fantasia={})", 
                            temRazaoSocial, temNomeFantasia);
                }
            }
            
            if (cliente != null) {
                log.debug("Dados do cliente {} extraídos: razao_social={}, nome_fantasia={}", 
                        codigoCliente, cliente.get("razao_social"), cliente.get("nome_fantasia"));
                
                Map<String, String> resultado = new HashMap<>();
                
                // Prioriza nome fantasia, depois razão social, depois nome
                Object nomeFantasia = cliente.get("nome_fantasia");
                Object razaoSocial = cliente.get("razao_social");
                Object nome = cliente.get("nome");
                
                // Armazena razão social se disponível
                if (razaoSocial != null && !razaoSocial.toString().trim().isEmpty()) {
                    resultado.put("razao_social", razaoSocial.toString().trim());
                    log.debug("Razão social encontrada para cliente {}: {}", codigoCliente, razaoSocial);
                }
                
                // Determina o nome a retornar (prioriza fantasia)
                if (nomeFantasia != null && !nomeFantasia.toString().trim().isEmpty()) {
                    resultado.put("nome", nomeFantasia.toString().trim());
                    log.debug("Nome encontrado (fantasia) para cliente {}: {}", codigoCliente, nomeFantasia);
                } else if (razaoSocial != null && !razaoSocial.toString().trim().isEmpty()) {
                    resultado.put("nome", razaoSocial.toString().trim());
                    log.debug("Nome encontrado (razão social) para cliente {}: {}", codigoCliente, razaoSocial);
                } else if (nome != null && !nome.toString().trim().isEmpty()) {
                    resultado.put("nome", nome.toString().trim());
                    log.debug("Nome encontrado (nome) para cliente {}: {}", codigoCliente, nome);
                }
                
                if (!resultado.isEmpty()) {
                    log.info("Dados do cliente {} retornados: nome={}, razao_social={}", 
                            codigoCliente, resultado.get("nome"), resultado.get("razao_social"));
                    return resultado;
                } else {
                    log.warn("Cliente {} encontrado mas sem nome ou razão social válidos", codigoCliente);
                }
            }
            
            log.warn("Dados não encontrados para cliente {} na resposta. Chaves disponíveis: {}", 
                    codigoCliente, response.keySet());
            return null;
        } catch (Exception e) {
            // Log apenas em debug para não poluir logs quando cliente não for encontrado
            log.warn("Erro ao buscar cliente {}: {}", codigoCliente, e.getMessage());
            return null;
        }
    }
    
    /**
     * Busca nome do cliente/fornecedor pelo código usando a API do Omie
     * @deprecated Use buscarDadosClientePorCodigo() para obter nome e razão social
     */
    @Deprecated
    private String buscarNomeClientePorCodigo(String codigoCliente) {
        Map<String, String> dados = buscarDadosClientePorCodigo(codigoCliente);
        return dados != null ? dados.get("nome") : null;
    }
    
    /**
     * Obtém valor string de um mapa, tentando várias chaves
     */
    private String getStringValue(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }

    /**
     * Filtra registros por data de vencimento
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> filtrarPorDataVencimento(List<Map<String, Object>> registros, 
                                                                 String dataInicio, String dataFim) {
        if (registros == null || registros.isEmpty()) {
            return registros;
        }
        
        return registros.stream()
                .filter(registro -> {
                    Object dataVencObj = registro.get("dDtVcto"); // Campo de data de vencimento do OMIE
                    if (dataVencObj == null) {
                        // Tentar outros campos possíveis
                        dataVencObj = registro.get("data_vencimento");
                    }
                    
                    if (dataVencObj == null) {
                        return true; // Se não tem data, mantém o registro
                    }
                    
                    String dataVenc = dataVencObj.toString();
                    
                    // Se tem dataInicio e a data do registro é anterior, filtra
                    if (dataInicio != null && dataVenc.compareTo(dataInicio) < 0) {
                        return false;
                    }
                    
                    // Se tem dataFim e a data do registro é posterior, filtra
                    if (dataFim != null && dataVenc.compareTo(dataFim) > 0) {
                        return false;
                    }
                    
                    return true;
                })
                .collect(Collectors.toList());
    }

    /**
     * Cria dados mock para contas a pagar ou receber
     * Formato conforme resposta real do Omie
     */
    private Map<String, Object> criarMovimentacoesMock(boolean isPagar, String dataInicio, String dataFim) {
        List<Map<String, Object>> registros = new ArrayList<>();
        
        // Cria alguns registros mock para simular dados reais
        for (int i = 1; i <= 5; i++) {
            Map<String, Object> registro = new HashMap<>();
            registro.put("codigo_lancamento_omie", i);
            registro.put("codigo_lancamento_integracao", "MOCK-" + i);
            registro.put("codigo_cliente_fornecedor", i);
            registro.put("nome_cliente_fornecedor", isPagar ? "Fornecedor Mock " + i : "Cliente Mock " + i);
            registro.put("data_vencimento", dataInicio != null ? dataInicio : "15/01/2025");
            registro.put("data_emissao", dataInicio != null ? dataInicio : "01/01/2025");
            registro.put("valor_documento", 1000.00 * i);
            registro.put("valor_pago", i % 2 == 0 ? 1000.00 * i : 0.0);
            registro.put("data_pagamento", i % 2 == 0 ? (dataInicio != null ? dataInicio : "15/01/2025") : null);
            registro.put("status", i % 2 == 0 ? (isPagar ? "PAGO" : "RECEBIDO") : (isPagar ? "A PAGAR" : "A RECEBER"));
            registro.put("observacao", "Movimentação mock " + i);
            registro.put("codigo_categoria", "1.01.0" + i);
            registro.put("descricao_categoria", "Categoria Mock " + i);
            registros.add(registro);
        }

        Map<String, Object> resultado = new HashMap<>();
        resultado.put("tipo", isPagar ? "CONTA_PAGAR" : "CONTA_RECEBER");
        resultado.put("total_de_registros", 5);
        resultado.put("total_de_paginas", 1);
        resultado.put("pagina", 1);
        resultado.put("registros", registros);
        resultado.put("mock", true);
        
        return resultado;
    }

    /**
     * Cria dados mock combinados
     */
    private Map<String, Object> criarMovimentacoesCombinadasMock(String dataInicio, String dataFim) {
        Map<String, Object> pagar = criarMovimentacoesMock(true, dataInicio, dataFim);
        Map<String, Object> receber = criarMovimentacoesMock(false, dataInicio, dataFim);

        List<Map<String, Object>> todas = new ArrayList<>();
        todas.addAll((List<Map<String, Object>>) pagar.get("registros"));
        todas.addAll((List<Map<String, Object>>) receber.get("registros"));

        Map<String, Object> resultado = new HashMap<>();
        resultado.put("movimentacoes", todas);
        resultado.put("total", todas.size());
        resultado.put("total_contas_pagar", pagar.get("total_de_registros"));
        resultado.put("total_contas_receber", receber.get("total_de_registros"));
        resultado.put("dataInicio", dataInicio != null ? dataInicio : "");
        resultado.put("dataFim", dataFim != null ? dataFim : "");
        resultado.put("mock", true);
        
        return resultado;
    }

    public String getAppKey() {
        return appKey;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public boolean isMockEnabled() {
        return mockEnabled;
    }
}

