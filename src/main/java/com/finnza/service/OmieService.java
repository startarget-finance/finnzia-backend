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
import java.util.List;
import java.util.Map;
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
     * Executa uma chamada à API OMIE
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executarChamadaApi(String endpoint, String call, Map<String, Object> params) {
        Map<String, Object> requestBody = criarRequestBody(call, params);
        
        // Log do request body (sem app_secret por segurança)
        Map<String, Object> logBody = new HashMap<>(requestBody);
        logBody.put("app_secret", "***HIDDEN***");
        log.debug("Chamando API OMIE: {} - call: {} - body: {}", endpoint, call, logBody);
        
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

            return response != null ? response : new HashMap<>();
        } catch (WebClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            
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
            log.error("Erro inesperado ao chamar API OMIE", e);
            throw new RuntimeException("Erro ao chamar API OMIE: " + e.getMessage(), e);
        }
    }

    /**
     * Lista contas a pagar do OMIE
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> listarContasPagar(String dataInicio, String dataFim, 
                                                   Integer pagina, Integer registrosPorPagina) {
        if (mockEnabled) {
            return criarMovimentacoesMock(true, dataInicio, dataFim);
        }

        try {
            // OMIE ListarContasPagar aceita: pagina, registros_por_pagina (não nPagina/nRegPorPagina!)
            Map<String, Object> params = new HashMap<>();
            params.put("pagina", pagina != null ? pagina : 1);
            params.put("registros_por_pagina", registrosPorPagina != null ? Math.min(registrosPorPagina, 500) : 50);
            params.put("apenas_importado_api", "N"); // Exibir todos os registros
            
            // Adicionar filtros de data se fornecidos (formato DD/MM/YYYY para OMIE)
            if (dataInicio != null) {
                // Converter de YYYY-MM-DD para DD/MM/YYYY
                String[] partes = dataInicio.split("-");
                if (partes.length == 3) {
                    params.put("filtrar_por_data_de", partes[2] + "/" + partes[1] + "/" + partes[0]);
                }
            }
            if (dataFim != null) {
                // Converter de YYYY-MM-DD para DD/MM/YYYY
                String[] partes = dataFim.split("-");
                if (partes.length == 3) {
                    params.put("filtrar_por_data_ate", partes[2] + "/" + partes[1] + "/" + partes[0]);
                }
            }
            
            log.info("Listando contas a pagar do OMIE: pagina={}, registros_por_pagina={}, dataInicio={}, dataFim={}",
                    params.get("pagina"), params.get("registros_por_pagina"), dataInicio, dataFim);

            Map<String, Object> response = executarChamadaApi("/financas/contapagar/", "ListarContasPagar", params);
            
            // OMIE retorna: { "total_de_registros": X, "registros": [...] }
            List<Map<String, Object>> registros = (List<Map<String, Object>>) response.getOrDefault("conta_pagar_cadastro", new ArrayList<>());
            
            // Se não encontrar em conta_pagar_cadastro, tenta registros
            if (registros.isEmpty()) {
                Object registrosObj = response.get("registros");
                if (registrosObj instanceof List) {
                    registros = (List<Map<String, Object>>) registrosObj;
                }
            }
            
            // OMIE retorna total_de_registros e registros na resposta
            Integer totalRegistros = (Integer) response.getOrDefault("total_de_registros", registros.size());
            
            Map<String, Object> resultado = new HashMap<>();
            resultado.put("tipo", "CONTA_PAGAR");
            resultado.put("total_de_registros", totalRegistros);
            resultado.put("registros", registros);
            
            log.info("Contas a pagar retornadas: {} registros (total: {})", registros.size(), totalRegistros);
            return resultado;
        } catch (Exception e) {
            log.error("Erro ao listar contas a pagar do OMIE", e);
            throw new RuntimeException("Erro ao listar contas a pagar: " + e.getMessage(), e);
        }
    }

    /**
     * Lista contas a receber do OMIE
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> listarContasReceber(String dataInicio, String dataFim, 
                                                     Integer pagina, Integer registrosPorPagina) {
        if (mockEnabled) {
            return criarMovimentacoesMock(false, dataInicio, dataFim);
        }

        try {
            // OMIE ListarContasReceber aceita: pagina, registros_por_pagina
            Map<String, Object> params = new HashMap<>();
            params.put("pagina", pagina != null ? pagina : 1);
            params.put("registros_por_pagina", registrosPorPagina != null ? Math.min(registrosPorPagina, 500) : 50);
            params.put("apenas_importado_api", "N"); // Exibir todos os registros
            
            // Adicionar filtros de data se fornecidos (formato DD/MM/YYYY para OMIE)
            if (dataInicio != null) {
                // Converter de YYYY-MM-DD para DD/MM/YYYY
                String[] partes = dataInicio.split("-");
                if (partes.length == 3) {
                    params.put("filtrar_por_data_de", partes[2] + "/" + partes[1] + "/" + partes[0]);
                }
            }
            if (dataFim != null) {
                // Converter de YYYY-MM-DD para DD/MM/YYYY
                String[] partes = dataFim.split("-");
                if (partes.length == 3) {
                    params.put("filtrar_por_data_ate", partes[2] + "/" + partes[1] + "/" + partes[0]);
                }
            }
            
            log.info("Listando contas a receber do OMIE: pagina={}, registros_por_pagina={}, dataInicio={}, dataFim={}",
                    params.get("pagina"), params.get("registros_por_pagina"), dataInicio, dataFim);

            Map<String, Object> response = executarChamadaApi("/financas/contareceber/", "ListarContasReceber", params);
            
            // OMIE retorna: { "total_de_registros": X, "registros": [...] }
            // Verificar tanto conta_receber_cadastro quanto registros
            List<Map<String, Object>> registros = new ArrayList<>();
            Object contaReceberCadastro = response.get("conta_receber_cadastro");
            if (contaReceberCadastro instanceof List) {
                registros = (List<Map<String, Object>>) contaReceberCadastro;
            } else {
                Object registrosObj = response.get("registros");
                if (registrosObj instanceof List) {
                    registros = (List<Map<String, Object>>) registrosObj;
                }
            }
            
            // OMIE retorna total_de_registros na resposta
            Integer totalRegistros = (Integer) response.getOrDefault("total_de_registros", registros.size());
            
            Map<String, Object> resultado = new HashMap<>();
            resultado.put("tipo", "CONTA_RECEBER");
            resultado.put("total_de_registros", totalRegistros);
            resultado.put("registros", registros);
            
            log.info("Contas a receber retornadas: {} registros (total: {})", registros.size(), totalRegistros);
            return resultado;
        } catch (Exception e) {
            log.error("Erro ao listar contas a receber do OMIE", e);
            throw new RuntimeException("Erro ao listar contas a receber: " + e.getMessage(), e);
        }
    }

    /**
     * Pesquisa movimentações financeiras do OMIE (combina contas a pagar e receber)
     * OMIE usa JSON com estrutura específica
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
            log.info("Pesquisando movimentações financeiras do OMIE: dataInicio={}, dataFim={}, pagina={}, registrosPorPagina={}, tipo={}, categoria={}, textoPesquisa={}",
                    dataInicio, dataFim, pagina, registrosPorPagina, tipo, categoria, textoPesquisa);

            // Busca contas a pagar e receber em paralelo (página atual)
            Map<String, Object> contasPagar = listarContasPagar(dataInicio, dataFim, pagina, registrosPorPagina);
            Map<String, Object> contasReceber = listarContasReceber(dataInicio, dataFim, pagina, registrosPorPagina);

            // Combina os resultados da página atual
            List<Map<String, Object>> todasMovimentacoes = new ArrayList<>();
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> registrosPagar = (List<Map<String, Object>>) contasPagar.get("registros");
            if (registrosPagar != null) {
                for (Map<String, Object> registro : registrosPagar) {
                    registro.put("tipo", "DESPESA");
                    registro.put("debito", true);
                    todasMovimentacoes.add(registro);
                }
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> registrosReceber = (List<Map<String, Object>>) contasReceber.get("registros");
            if (registrosReceber != null) {
                for (Map<String, Object> registro : registrosReceber) {
                    registro.put("tipo", "RECEITA");
                    registro.put("debito", false);
                    todasMovimentacoes.add(registro);
                }
            }

            int totalPagar = (Integer) contasPagar.getOrDefault("total_de_registros", 0);
            int totalReceber = (Integer) contasReceber.getOrDefault("total_de_registros", 0);
            int totalGeral = totalPagar + totalReceber;

            // Aplica filtros nas movimentações (se houver)
            boolean temFiltros = (tipo != null && !tipo.isEmpty()) || 
                                (categoria != null && !categoria.isEmpty()) || 
                                (textoPesquisa != null && !textoPesquisa.trim().isEmpty());
            
            List<Map<String, Object>> movimentacoesFiltradas = todasMovimentacoes;
            if (temFiltros) {
                movimentacoesFiltradas = aplicarFiltros(todasMovimentacoes, tipo, categoria, textoPesquisa);
                log.info("Filtros aplicados: tipo={}, categoria={}, textoPesquisa={}. Resultado: {} de {} movimentações",
                        tipo, categoria, textoPesquisa, movimentacoesFiltradas.size(), todasMovimentacoes.size());
            }

            // Calcula totais de receitas e despesas de TODAS as movimentações (não apenas da página atual)
            // Usa cache para evitar recalcular quando apenas a página muda
            double totalReceitas = 0.0;
            double totalDespesas = 0.0;
            
            // Gera chave do cache baseada no período
            String cacheKey = (dataInicio != null ? dataInicio : "") + "_" + (dataFim != null ? dataFim : "");
            TotaisCache cache = cacheTotais.get(cacheKey);
            
            // TTL do cache: 5 minutos (300000 ms) - suficiente para navegação entre páginas
            long cacheTtl = 5 * 60 * 1000;
            
            // Verifica se existe no cache e não está expirado
            if (cache != null && !cache.isExpired(cacheTtl)) {
                totalReceitas = cache.totalReceitas;
                totalDespesas = cache.totalDespesas;
                log.debug("Totais obtidos do cache para período {} a {}", dataInicio, dataFim);
            } else {
                // Cache não existe ou expirou - calcula os totais
                try {
                    log.info("Calculando totais para período {} a {} (cache miss ou expirado)", dataInicio, dataFim);
                    
                    // Calcula totais de despesas (contas a pagar) - itera pelas páginas
                    int paginaPagar = 1;
                    int registrosPorPaginaPagar = 500; // Limite máximo do OMIE
                    boolean temMaisPagar = true;
                    
                    while (temMaisPagar) {
                        Map<String, Object> contasPagarPage = listarContasPagar(dataInicio, dataFim, paginaPagar, registrosPorPaginaPagar);
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> registrosPagarPage = (List<Map<String, Object>>) contasPagarPage.get("registros");
                        
                        if (registrosPagarPage != null && !registrosPagarPage.isEmpty()) {
                            for (Map<String, Object> registro : registrosPagarPage) {
                                Object valorObj = registro.get("nValorTitulo") != null ? registro.get("nValorTitulo") : 
                                                 registro.get("valor_documento") != null ? registro.get("valor_documento") :
                                                 registro.get("valor_pago");
                                if (valorObj != null) {
                                    double valor = valorObj instanceof Number ? 
                                            ((Number) valorObj).doubleValue() : 
                                            Double.parseDouble(valorObj.toString());
                                    totalDespesas += valor;
                                }
                            }
                            // Verifica se há mais páginas
                            int totalRegistrosPagar = (Integer) contasPagarPage.getOrDefault("total_de_registros", 0);
                            temMaisPagar = (paginaPagar * registrosPorPaginaPagar) < totalRegistrosPagar;
                            paginaPagar++;
                        } else {
                            temMaisPagar = false;
                        }
                    }
                    
                    // Calcula totais de receitas (contas a receber) - itera pelas páginas
                    int paginaReceber = 1;
                    int registrosPorPaginaReceber = 500; // Limite máximo do OMIE
                    boolean temMaisReceber = true;
                    
                    while (temMaisReceber) {
                        Map<String, Object> contasReceberPage = listarContasReceber(dataInicio, dataFim, paginaReceber, registrosPorPaginaReceber);
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> registrosReceberPage = (List<Map<String, Object>>) contasReceberPage.get("registros");
                        
                        if (registrosReceberPage != null && !registrosReceberPage.isEmpty()) {
                            for (Map<String, Object> registro : registrosReceberPage) {
                                Object valorObj = registro.get("nValorTitulo") != null ? registro.get("nValorTitulo") : 
                                                 registro.get("valor_documento") != null ? registro.get("valor_documento") :
                                                 registro.get("valor_pago");
                                if (valorObj != null) {
                                    double valor = valorObj instanceof Number ? 
                                            ((Number) valorObj).doubleValue() : 
                                            Double.parseDouble(valorObj.toString());
                                    totalReceitas += valor;
                                }
                            }
                            // Verifica se há mais páginas
                            int totalRegistrosReceber = (Integer) contasReceberPage.getOrDefault("total_de_registros", 0);
                            temMaisReceber = (paginaReceber * registrosPorPaginaReceber) < totalRegistrosReceber;
                            paginaReceber++;
                        } else {
                            temMaisReceber = false;
                        }
                    }
                    
                    // Armazena no cache
                    double saldoLiquido = totalReceitas - totalDespesas;
                    cacheTotais.put(cacheKey, new TotaisCache(totalReceitas, totalDespesas, saldoLiquido));
                    
                    log.info("Totais calculados e armazenados no cache ({} páginas de despesas, {} páginas de receitas): Receitas={}, Despesas={}", 
                            paginaPagar - 1, paginaReceber - 1, totalReceitas, totalDespesas);
                } catch (Exception e) {
                    log.warn("Erro ao calcular totais completos, usando apenas da página atual: {}", e.getMessage());
                    // Fallback: calcula apenas da página atual
                    for (Map<String, Object> mov : todasMovimentacoes) {
                        Object valorObj = mov.get("nValorTitulo") != null ? mov.get("nValorTitulo") : 
                                         mov.get("valor_documento") != null ? mov.get("valor_documento") :
                                         mov.get("valor_pago");
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
            }

            // Ajusta totais se há filtros aplicados
            // Nota: Para filtros, os totais financeiros devem considerar apenas as movimentações filtradas
            // Por limitação da API do OMIE, filtramos apenas a página atual
            // Para totais precisos com filtros, seria necessário buscar todas as páginas
            double totalReceitasFiltrado = totalReceitas;
            double totalDespesasFiltrado = totalDespesas;
            
            if (temFiltros) {
                // Recalcula totais apenas das movimentações filtradas da página atual
                totalReceitasFiltrado = 0.0;
                totalDespesasFiltrado = 0.0;
                for (Map<String, Object> mov : movimentacoesFiltradas) {
                    Object valorObj = mov.get("nValorTitulo") != null ? mov.get("nValorTitulo") : 
                                     mov.get("valor_documento") != null ? mov.get("valor_documento") :
                                     mov.get("valor_pago");
                    if (valorObj != null) {
                        double valor = valorObj instanceof Number ? 
                                ((Number) valorObj).doubleValue() : 
                                Double.parseDouble(valorObj.toString());
                        Boolean debito = (Boolean) mov.get("debito");
                        if (Boolean.TRUE.equals(debito)) {
                            totalDespesasFiltrado += valor;
                        } else {
                            totalReceitasFiltrado += valor;
                        }
                    }
                }
                log.info("Totais recalculados com filtros aplicados: Receitas={}, Despesas={} (apenas da página atual)",
                        totalReceitasFiltrado, totalDespesasFiltrado);
            }

            Map<String, Object> resultado = new HashMap<>();
            // Frontend espera 'movimentacoes' e 'total' (conforme interface MovimentacoesOmieResponse)
            resultado.put("movimentacoes", movimentacoesFiltradas);
            // Se há filtros, o total reflete apenas as movimentações filtradas da página atual
            // Para total preciso, seria necessário buscar todas as páginas e filtrar
            int totalFiltrado = temFiltros ? movimentacoesFiltradas.size() : totalGeral;
            resultado.put("total", totalFiltrado);
            // Totais financeiros (ajustados se há filtros)
            resultado.put("totalReceitas", totalReceitasFiltrado);
            resultado.put("totalDespesas", totalDespesasFiltrado);
            resultado.put("saldoLiquido", totalReceitasFiltrado - totalDespesasFiltrado);
            // Campos adicionais para compatibilidade
            resultado.put("total_contas_pagar", totalPagar);
            resultado.put("total_contas_receber", totalReceber);
            resultado.put("dataInicio", dataInicio);
            resultado.put("dataFim", dataFim);
            
            log.info("Movimentações combinadas: {} total ({} pagar + {} receber), Receitas: {}, Despesas: {}", 
                    totalGeral, totalPagar, totalReceber, totalReceitas, totalDespesas);
            
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
     */
    private Map<String, Object> criarMovimentacoesMock(boolean isPagar, String dataInicio, String dataFim) {
        List<Map<String, Object>> registros = new ArrayList<>();
        
        registros.add(Map.of(
                "codigo_lancamento", "1",
                "codigo_cliente_fornecedor", "1",
                "nome_cliente_fornecedor", "Cliente/Fornecedor Mock",
                "data_vencimento", dataInicio != null ? dataInicio : "2025-01-15",
                "valor_documento", 1000.00,
                "status", isPagar ? "A PAGAR" : "A RECEBER",
                "observacao", "Movimentação mock"
        ));

        return Map.of(
                "tipo", isPagar ? "CONTA_PAGAR" : "CONTA_RECEBER",
                "total_de_registros", 1,
                "registros", registros,
                "mock", true
        );
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

        return Map.of(
                "movimentacoes", todas,
                "total", 2,
                "total_contas_pagar", 1,
                "total_contas_receber", 1,
                "dataInicio", dataInicio != null ? dataInicio : "",
                "dataFim", dataFim != null ? dataFim : "",
                "mock", true
        );
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

