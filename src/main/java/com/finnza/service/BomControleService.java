package com.finnza.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDate;
import java.util.*;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service para integração com API do Bom Controle
 * Documentação: https://documenter.getpostman.com/view/1797561/SWT7BKWo
 */
@Slf4j
@Service
public class BomControleService {

    private final WebClient webClient;
    private final String apiKey;
    private final String baseUrl;
    private final boolean mockEnabled;
    private final BomControleRateLimiter rateLimiter;
    
    // Cache por requisição usando ThreadLocal (não compartilhado entre usuários)
    // Armazena o ID da empresa durante a execução da requisição atual
    private static final ThreadLocal<Integer> empresaIdPorRequisicao = new ThreadLocal<>();
    
    // Cache por usuário (chave: email do usuário, valor: ID da empresa)
    // Cache de curta duração (5 minutos) para evitar rate limit sem compartilhar entre usuários
    private static final Map<String, CacheEmpresaUsuario> cachePorUsuario = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CACHE_DURATION_MS = 5 * 60 * 1000; // 5 minutos
    
    // Classe interna para cache por usuário
    private static class CacheEmpresaUsuario {
        final Integer empresaId;
        final long timestamp;
        
        CacheEmpresaUsuario(Integer empresaId) {
            this.empresaId = empresaId;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return (System.currentTimeMillis() - timestamp) > CACHE_DURATION_MS;
        }
    }
    
    // ID padrão de empresa para usar quando não há cache e está em rate limit
    // Pode ser configurado via variável de ambiente BOMCONTROLE_DEFAULT_EMPRESA_ID
    @Value("${bomcontrole.default.empresa.id:}")
    private String defaultEmpresaIdStr;
    
    @Autowired(required = false)
    private PermissionService permissionService;
    
    /**
     * Obtém ou busca o ID da empresa do Bom Controle para o usuário atual
     * Usa cache por requisição (ThreadLocal) e cache por usuário para evitar rate limit
     * SEM compartilhar entre diferentes usuários
     */
    private Integer obterOuBuscarEmpresaId() {
        // 1. Verificar se já foi buscado nesta requisição (ThreadLocal)
        Integer empresaIdRequisicao = empresaIdPorRequisicao.get();
        if (empresaIdRequisicao != null) {
            log.debug("Usando ID de empresa da requisição atual: idsEmpresa={}", empresaIdRequisicao);
            return empresaIdRequisicao;
        }
        
        // 2. Obter email do usuário atual
        String emailUsuario = null;
        try {
            if (permissionService != null) {
                var usuario = permissionService.getCurrentUser();
                if (usuario != null) {
                    emailUsuario = usuario.getEmail();
                }
            }
        } catch (Exception e) {
            log.debug("Não foi possível obter usuário atual: {}", e.getMessage());
        }
        
        // 3. Verificar cache por usuário (se disponível) - usar mesmo se expirado para evitar rate limit
        Integer empresaIdCache = null;
        if (emailUsuario != null) {
            CacheEmpresaUsuario cacheUsuario = cachePorUsuario.get(emailUsuario);
            if (cacheUsuario != null) {
                // Usar cache mesmo se expirado para evitar rate limit
                empresaIdCache = cacheUsuario.empresaId;
                if (!cacheUsuario.isExpired()) {
                    log.debug("Usando ID de empresa do cache válido do usuário '{}': idsEmpresa={}", emailUsuario, empresaIdCache);
                } else {
                    log.debug("Usando ID de empresa do cache expirado do usuário '{}' para evitar rate limit: idsEmpresa={}", emailUsuario, empresaIdCache);
                }
            }
        }
        
        // 4. Se não há cache, tentar usar ID padrão configurado
        if (empresaIdCache == null) {
            if (defaultEmpresaIdStr != null && !defaultEmpresaIdStr.isEmpty()) {
                try {
                    empresaIdCache = Integer.parseInt(defaultEmpresaIdStr.trim());
                    log.info("Usando ID de empresa padrão configurado: idsEmpresa={}", empresaIdCache);
                    // Armazenar no cache por usuário
                    if (emailUsuario != null) {
                        cachePorUsuario.put(emailUsuario, new CacheEmpresaUsuario(empresaIdCache));
                    }
                } catch (NumberFormatException e) {
                    log.warn("ID de empresa padrão inválido: {}", defaultEmpresaIdStr);
                }
            }
        }
        
        // 5. Se ainda não tem ID e não há cache expirado, tentar buscar da API APENAS UMA VEZ
        // Se falhar (rate limit), retornar null e deixar API buscar sem filtro
        if (empresaIdCache == null) {
            try {
                log.debug("Buscando ID de empresa da API para usuário '{}'...", emailUsuario != null ? emailUsuario : "desconhecido");
                Map<String, Object> empresasResponse = listarEmpresas(null);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> empresas = (List<Map<String, Object>>) empresasResponse.get("empresas");
                
                if (empresas != null && !empresas.isEmpty()) {
                    Map<String, Object> primeiraEmpresa = empresas.get(0);
                    Object idEmpresaObj = primeiraEmpresa.get("Id");
                    if (idEmpresaObj == null) {
                        idEmpresaObj = primeiraEmpresa.get("IdEmpresa");
                    }
                    if (idEmpresaObj == null) {
                        idEmpresaObj = primeiraEmpresa.get("id");
                    }
                    if (idEmpresaObj == null) {
                        idEmpresaObj = primeiraEmpresa.get("idEmpresa");
                    }
                    
                    if (idEmpresaObj instanceof Number) {
                        empresaIdCache = ((Number) idEmpresaObj).intValue();
                    } else if (idEmpresaObj instanceof String) {
                        try {
                            empresaIdCache = Integer.parseInt((String) idEmpresaObj);
                        } catch (NumberFormatException e) {
                            log.warn("Não foi possível converter ID da empresa para Integer: {}", idEmpresaObj);
                        }
                    }
                    
                    // Atualizar cache por usuário
                    if (empresaIdCache != null && emailUsuario != null) {
                        cachePorUsuario.put(emailUsuario, new CacheEmpresaUsuario(empresaIdCache));
                        log.info("✅ ID de empresa obtido da API para usuário '{}': idsEmpresa={} (cache atualizado)", emailUsuario, empresaIdCache);
                    }
                } else {
                    log.info("⚠️ Nenhuma empresa retornada pela API (possível rate limit). Continuando sem filtro de empresa.");
                }
            } catch (Exception e) {
                // Não logar como erro, apenas avisar - é esperado em caso de rate limit
                log.info("⚠️ Não foi possível buscar empresa da API para usuário '{}': {}. Continuando sem filtro de empresa (API retornará todas as empresas).", 
                        emailUsuario != null ? emailUsuario : "desconhecido", e.getMessage());
            }
        }
        
        // 5. Armazenar no ThreadLocal para reutilizar na mesma requisição
        if (empresaIdCache != null) {
            empresaIdPorRequisicao.set(empresaIdCache);
        }
        
        // 6. Se não conseguiu buscar, retornar null (buscar sem filtro de empresa)
        if (empresaIdCache == null) {
            log.info("Nenhuma empresa encontrada ou erro ao buscar para usuário '{}'. Buscando movimentações sem filtro de empresa (API retornará todas as empresas).", 
                    emailUsuario != null ? emailUsuario : "desconhecido");
        }
        
        return empresaIdCache;
    }
    
    /**
     * Limpa o cache do ThreadLocal após a requisição
     * Deve ser chamado no final do método ou usar @RequestScope
     */
    private void limparCacheRequisicao() {
        empresaIdPorRequisicao.remove();
    }

    public BomControleService(
            @Value("${bomcontrole.api.key:}") String apiKey,
            @Value("${bomcontrole.api.url:https://apinewintegracao.bomcontrole.com.br}") String baseUrl,
            @Value("${bomcontrole.mock.enabled:false}") boolean mockEnabled,
            BomControleRateLimiter rateLimiter) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.mockEnabled = mockEnabled || apiKey == null || apiKey.isEmpty();
        this.rateLimiter = rateLimiter;

        // Configurar WebClient com buffer maior para respostas grandes
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 10MB
                .build();

        // Bom Controle usa "ApiKey" no header, não "Bearer"
        String authHeader = this.mockEnabled ? "" : "ApiKey " + this.apiKey;
        
        // Validar API key se não estiver em modo mock
        if (!this.mockEnabled && (this.apiKey == null || this.apiKey.trim().isEmpty())) {
            log.error("❌ ERRO CRÍTICO: BOMCONTROLE_API_KEY não está configurada!");
            log.error("   Configure a variável de ambiente BOMCONTROLE_API_KEY");
            log.error("   O sistema continuará, mas todas as requisições falharão com 401 Unauthorized");
        } else if (!this.mockEnabled) {
            // Mascarar API key nos logs (mostrar apenas primeiros e últimos caracteres)
            String maskedKey = this.apiKey.length() > 8 
                ? this.apiKey.substring(0, 4) + "..." + this.apiKey.substring(this.apiKey.length() - 4)
                : "***";
            log.info("✅ Bom Controle API Key configurada: {}", maskedKey);
        }
        
        this.webClient = WebClient.builder()
                .baseUrl(this.baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Authorization", authHeader)
                .exchangeStrategies(strategies)
                .build();

        if (this.mockEnabled) {
            log.warn("⚠️ Bom Controle em modo MOCK - API Key não configurada ou mock habilitado");
        } else {
            log.info("✅ Bom Controle Service inicializado - URL: {}, Rate Limiter: ativo", this.baseUrl);
        }
    }

    /**
     * Testa a conexão com a API do Bom Controle
     */
    public Map<String, Object> testarConexao() {
        if (mockEnabled) {
            return Map.of(
                    "sucesso", true,
                    "modo", "MOCK",
                    "mensagem", "Modo mock ativo - API Key não configurada"
            );
        }

        try {
            Map<String, Object> response = webClient.get()
                    .uri("/integracao/Empresa/Pesquisar")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            return Map.of(
                    "sucesso", true,
                    "mensagem", "Conexão com Bom Controle estabelecida com sucesso",
                    "dados", response != null ? response : Map.of()
            );
        } catch (Exception e) {
            log.error("Erro ao testar conexão com Bom Controle", e);
            return Map.of(
                    "sucesso", false,
                    "erro", true,
                    "mensagem", "Erro ao conectar com Bom Controle: " + e.getMessage()
            );
        }
    }

    /**
     * Lista empresas do Bom Controle
     */
    public Map<String, Object> listarEmpresas(String pesquisa) {
        if (mockEnabled) {
            return criarRespostaMockEmpresas();
        }

        try {
            String cacheKey = "empresas:" + (pesquisa != null ? pesquisa : "all");
            
            // Executar com rate limiting e cache (cache mais longo para empresas - 10 minutos)
            List<Map<String, Object>> empresas = rateLimiter.executeWithRateLimit(
                    cacheKey,
                    10 * 60 * 1000, // 10 minutos de cache para empresas
                    () -> {
                        log.debug("🌐 Buscando empresas do Bom Controle: pesquisa={}", pesquisa);
                        
                        // A API do Bom Controle retorna um array diretamente, não um objeto
                        List<Map<String, Object>> result = webClient.get()
                                .uri(uriBuilder -> {
                                    uriBuilder.path("/integracao/Empresa/Pesquisar");
                                    if (pesquisa != null && !pesquisa.isEmpty()) {
                                        uriBuilder.queryParam("pesquisa", pesquisa);
                                    }
                                    return uriBuilder.build();
                                })
                                .retrieve()
                                .bodyToFlux(new ParameterizedTypeReference<Map<String, Object>>() {})
                                .collectList()
                                .block();

                        if (result == null) {
                            result = new ArrayList<>();
                        }
                        
                        return result;
                    },
                    () -> {
                        // Fallback: retornar lista vazia
                        log.warn("📦 Usando fallback (lista vazia) para empresas devido a rate limit");
                        return new ArrayList<Map<String, Object>>();
                    }
            );

            return Map.of(
                    "empresas", empresas,
                    "total", empresas.size()
            );
        } catch (BomControleRateLimiter.RateLimitException e) {
            log.warn("⚠️ Rate limit detectado ao listar empresas. Retornando lista vazia.");
            return Map.of(
                    "empresas", new ArrayList<>(),
                    "total", 0
            );
        } catch (WebClientResponseException e) {
            if (e.getStatusCode() != null && e.getStatusCode().value() == 429) {
                log.warn("⚠️ Rate limit (429) ao listar empresas. Retornando lista vazia.");
                return Map.of(
                        "empresas", new ArrayList<>(),
                        "total", 0
                );
            }
            log.error("Erro ao listar empresas do Bom Controle: {}", e.getResponseBodyAsString(), e);
            throw new RuntimeException("Erro ao listar empresas: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Erro ao listar empresas do Bom Controle", e);
            throw new RuntimeException("Erro ao listar empresas: " + e.getMessage(), e);
        }
    }

    /**
     * Busca TODAS as páginas de movimentações para um tipo de data específico
     */
    private List<Map<String, Object>> buscarTodasPaginasMovimentacoes(
            String dataInicio,
            String dataTermino,
            String tipoData,
            Integer idsEmpresa,
            Integer idsCliente,
            Integer idsFornecedor,
            String textoPesquisa,
            String categoria,
            int itensPorPagina) {
        
        List<Map<String, Object>> todasMovimentacoes = new ArrayList<>();
        int paginaAtual = 1;
        int totalPaginas = 1;
        boolean continuar = true;
        
        while (continuar) {
            try {
                // Usar buscarMovimentacoesApi para buscar apenas uma página (evita loop infinito)
                Map<String, Object> resultado = buscarMovimentacoesApi(
                        dataInicio, dataTermino, tipoData, idsEmpresa, idsCliente, idsFornecedor,
                        textoPesquisa, categoria, null, itensPorPagina, paginaAtual);
                
                if (resultado != null && resultado.containsKey("movimentacoes")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> movimentacoes = (List<Map<String, Object>>) resultado.get("movimentacoes");
                    
                    if (movimentacoes.isEmpty()) {
                        continuar = false;
                    } else {
                        todasMovimentacoes.addAll(movimentacoes);
                        
                        // Calcular total de páginas baseado no totalItens
                        Object totalItensObj = resultado.get("total");
                        int totalItens = totalItensObj instanceof Number ? ((Number) totalItensObj).intValue() : 0;
                        
                        // Se temos totalItens, calcular totalPaginas
                        if (totalItens > 0) {
                            totalPaginas = (int) Math.ceil((double) totalItens / itensPorPagina);
                        }
                        
                        log.debug("📄 Página {}: {} movimentações encontradas (total acumulado: {}, totalItens: {}, totalPaginas estimado: {})", 
                                paginaAtual, movimentacoes.size(), todasMovimentacoes.size(), totalItens, totalPaginas);
                        
                        // Se retornou menos itens que o esperado, não há mais páginas
                        // OU se já coletamos todas as movimentações baseado no totalItens
                        if (movimentacoes.size() < itensPorPagina) {
                            // Não há mais páginas
                            continuar = false;
                            log.debug("✅ Última página alcançada (retornou {} < {})", movimentacoes.size(), itensPorPagina);
                        } else if (totalItens > 0 && todasMovimentacoes.size() >= totalItens) {
                            // Já coletamos todas as movimentações
                            continuar = false;
                            log.debug("✅ Todas as movimentações coletadas ({}/{})", todasMovimentacoes.size(), totalItens);
                        } else {
                            // Continuar para próxima página
                            paginaAtual++;
                            // Delay entre páginas para evitar rate limiting
                            try {
                                Thread.sleep(150);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                continuar = false;
                            }
                        }
                    }
                } else {
                    continuar = false;
                }
            } catch (Exception e) {
                log.warn("⚠️ Erro ao buscar página {}: {}", paginaAtual, e.getMessage());
                continuar = false;
            }
        }
        
        log.info("📚 Total de páginas buscadas: {}, total de movimentações coletadas: {}", paginaAtual, todasMovimentacoes.size());
        return todasMovimentacoes;
    }

    /**
     * Lista contas a pagar (movimentações com Debito=true)
     * Busca por TODOS os tipos de data para garantir que nenhuma movimentação seja perdida
     */
    public Map<String, Object> listarContasPagar(
            String dataInicio,
            String dataTermino,
            String tipoData,
            Integer idsEmpresa,
            Integer idsCliente,
            Integer idsFornecedor,
            String textoPesquisa,
            String categoria,
            Integer itensPorPagina,
            Integer numeroDaPagina) {
        
        log.info("Buscando contas a pagar: dataInicio={}, dataTermino={}, tipoData={}, pagina={}", 
                dataInicio, dataTermino, tipoData, numeroDaPagina);
        
        // Usar apenas o tipo de data solicitado (ou DataVencimento como padrão para contas a pagar)
        String tipoDataParaBuscar = tipoData != null && !tipoData.isEmpty() 
                ? converterTipoData(tipoData) 
                : "DataPrevista"; // Data de vencimento é o padrão para contas a pagar
        
        log.info("🔍 Buscando contas a pagar usando tipo de data: {} (página {})", tipoDataParaBuscar, numeroDaPagina != null ? numeroDaPagina : 1);
        
        // Buscar apenas a página solicitada usando buscarMovimentacoes
        Map<String, Object> resultadoBusca = buscarMovimentacoes(
                dataInicio, dataTermino, tipoDataParaBuscar, idsEmpresa, idsCliente, idsFornecedor,
                textoPesquisa, categoria, "despesa", // Filtro de tipo: despesa para contas a pagar
                itensPorPagina != null ? itensPorPagina : 50,
                numeroDaPagina != null ? numeroDaPagina : 1);
        
        // Extrair movimentações do resultado
        List<Map<String, Object>> todasMovimentacoes = new ArrayList<>();
        Integer totalItens = 0;
        
        if (resultadoBusca != null && resultadoBusca.containsKey("movimentacoes")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> movimentacoes = (List<Map<String, Object>>) resultadoBusca.get("movimentacoes");
            todasMovimentacoes = movimentacoes != null ? movimentacoes : new ArrayList<>();
            
            // Obter totalItens da paginação se disponível
            if (resultadoBusca.containsKey("paginacao")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> paginacao = (Map<String, Object>) resultadoBusca.get("paginacao");
                if (paginacao != null && paginacao.containsKey("totalItens")) {
                    Object totalObj = paginacao.get("totalItens");
                    if (totalObj instanceof Number) {
                        totalItens = ((Number) totalObj).intValue();
                    }
                }
            }
            
            // Se não tem totalItens na paginação, usar o total da resposta
            if (totalItens == 0 && resultadoBusca.containsKey("total")) {
                Object totalObj = resultadoBusca.get("total");
                if (totalObj instanceof Number) {
                    totalItens = ((Number) totalObj).intValue();
                }
            }
        }
        
        log.info("✅ Busca de contas a pagar concluída: {} movimentações na página {} (total: {})", 
                todasMovimentacoes.size(), numeroDaPagina != null ? numeroDaPagina : 1, totalItens);
        
        // Criar resultado combinado
        Map<String, Object> resultado = new HashMap<>();
        resultado.put("movimentacoes", todasMovimentacoes);
        resultado.put("total", totalItens > 0 ? totalItens : todasMovimentacoes.size());
        
        // Adicionar informações de paginação para o frontend
        if (resultadoBusca != null && resultadoBusca.containsKey("paginacao")) {
            resultado.put("paginacao", resultadoBusca.get("paginacao"));
        } else {
            resultado.put("paginacao", Map.of(
                    "itensPorPagina", itensPorPagina != null ? itensPorPagina : 50,
                    "numeroDaPagina", numeroDaPagina != null ? numeroDaPagina : 1,
                    "totalItens", totalItens > 0 ? totalItens : todasMovimentacoes.size()
            ));
        }
        
        // Filtrar apenas movimentações com Debito=true (já filtrado pelo tipo "despesa", mas garantir)
        if (resultado.containsKey("movimentacoes")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> movimentacoes = (List<Map<String, Object>>) resultado.get("movimentacoes");
            
            log.debug("Total de movimentações antes do filtro: {}", movimentacoes.size());
            
            List<Map<String, Object>> contasPagar = movimentacoes.stream()
                    .filter(mov -> {
                        Object debito = mov.get("Debito");
                        boolean isDebito = debito instanceof Boolean ? (Boolean) debito : false;
                        
                        // Log detalhado para debug - mostrar TODOS os campos relevantes
                        Object idFornecedor = mov.get("IdFornecedor");
                        Object idCliente = mov.get("IdCliente");
                        Object nome = mov.get("Nome");
                        Object valor = mov.get("Valor");
                        Object nomeClienteFornecedor = mov.get("NomeClienteFornecedor");
                        Object tipoMovimentacao = mov.get("TipoMovimentacao");
                        Object nomeTipoMovimentacao = mov.get("NomeTipoMovimentacao");
                        Object nomeCategoriaFinanceira = mov.get("NomeCategoriaFinanceira");
                        
                        // Log completo para análise
                        log.info("🔍 Analisando movimentação: " +
                                "Debito={}, " +
                                "IdFornecedor={}, " +
                                "IdCliente={}, " +
                                "Nome={}, " +
                                "Valor={}, " +
                                "Cliente/Fornecedor={}, " +
                                "TipoMovimentacao={}, " +
                                "NomeTipoMovimentacao={}, " +
                                "Categoria={}",
                                debito, idFornecedor, idCliente, nome, valor,
                                nomeClienteFornecedor, tipoMovimentacao, nomeTipoMovimentacao, nomeCategoriaFinanceira);
                        
                        // Verificar se é transferência (pode ter Debito=false mas ainda ser saída de caixa)
                        boolean isTransferencia = false;
                        if (nome != null) {
                            String nomeStr = nome.toString().toLowerCase();
                            isTransferencia = nomeStr.contains("transferência") || 
                                            nomeStr.contains("transferencia") ||
                                            (nomeStr.contains("origem:") && nomeStr.contains("destino:")) ||
                                            nomeStr.contains("origem: banco") ||
                                            nomeStr.contains("destino: caixa");
                        }
                        if (nomeCategoriaFinanceira != null) {
                            String categoriaStr = nomeCategoriaFinanceira.toString().toLowerCase();
                            isTransferencia = isTransferencia || categoriaStr.contains("transferência") ||
                                            categoriaStr.contains("transferencia");
                        }
                        // Verificar também pelo tipo de movimentação (se houver tipo específico para transferências)
                        // Tipos 15 (AporteCapital) podem incluir transferências
                        if (tipoMovimentacao != null) {
                            int tipoMov = tipoMovimentacao instanceof Number ? 
                                    ((Number) tipoMovimentacao).intValue() : 0;
                            // Tipo 15 = AporteCapital pode incluir transferências entre contas
                            if (tipoMov == 15) {
                                isTransferencia = true;
                            }
                        }
                        
                        // Verificar se tem IdFornecedor mas Debito=false (pode ser erro de classificação)
                        boolean temFornecedor = idFornecedor != null && 
                                !idFornecedor.toString().equals("0") && 
                                !idFornecedor.toString().isEmpty();
                        boolean temCliente = idCliente != null && 
                                !idCliente.toString().equals("0") && 
                                !idCliente.toString().isEmpty();
                        
                        // Verificar tipos de movimentação que são despesas
                        // 19 = DespesaFornecedor, 20 = DespesaFuncionario, 21 = DespesaImposto
                        boolean isTipoDespesa = false;
                        if (tipoMovimentacao != null) {
                            int tipoMov = tipoMovimentacao instanceof Number ? 
                                    ((Number) tipoMovimentacao).intValue() : 0;
                            isTipoDespesa = tipoMov == 19 || tipoMov == 20 || tipoMov == 21;
                        }
                        
                        // Incluir se:
                        // 1. Debito=true (padrão)
                        // 2. OU é transferência (saída de caixa)
                        // 3. OU tem tipo de despesa (mesmo que Debito=false por erro)
                        // 4. OU tem fornecedor mas não tem cliente (conta a pagar mal classificada)
                        boolean deveIncluir = isDebito || 
                                            (isTransferencia && !temCliente) ||
                                            (isTipoDespesa && !temCliente) ||
                                            (temFornecedor && !temCliente && !isDebito);
                        
                        if (!isDebito && deveIncluir) {
                            log.warn("⚠️ Incluindo movimentação com Debito=false: " +
                                    "Nome={}, Valor={}, Tipo={}, Categoria={}, " +
                                    "Motivo: Transferência={}, TipoDespesa={}, TemFornecedor={}",
                                    nome, valor, nomeTipoMovimentacao, nomeCategoriaFinanceira,
                                    isTransferencia, isTipoDespesa, temFornecedor);
                        }
                        
                        log.debug("Movimentação: Debito={}, será incluída={}", debito, deveIncluir);
                        
                        return deveIncluir;
                    })
                    .collect(java.util.stream.Collectors.toList());
            
            log.info("Total de contas a pagar após filtro: {}", contasPagar.size());
            
            resultado.put("movimentacoes", contasPagar);
            resultado.put("total", contasPagar.size());
        } else {
            log.warn("Resultado não contém chave 'movimentacoes'");
        }
        
        return resultado;
    }

    /**
     * Lista contas a receber (movimentações com Debito=false)
     * Busca por TODOS os tipos de data para garantir que nenhuma movimentação seja perdida
     */
    public Map<String, Object> listarContasReceber(
            String dataInicio,
            String dataTermino,
            String tipoData,
            Integer idsEmpresa,
            Integer idsCliente,
            Integer idsFornecedor,
            String textoPesquisa,
            String categoria,
            Integer itensPorPagina,
            Integer numeroDaPagina) {
        
        log.info("Buscando contas a receber: dataInicio={}, dataTermino={}, tipoData={}, pagina={}", 
                dataInicio, dataTermino, tipoData, numeroDaPagina);
        
        // Usar apenas o tipo de data solicitado (ou DataVencimento como padrão para contas a receber)
        String tipoDataParaBuscar = tipoData != null && !tipoData.isEmpty() 
                ? converterTipoData(tipoData) 
                : "DataPrevista"; // Data de vencimento é o padrão para contas a receber
        
        log.info("🔍 Buscando contas a receber usando tipo de data: {} (página {})", tipoDataParaBuscar, numeroDaPagina != null ? numeroDaPagina : 1);
        
        // Buscar apenas a página solicitada usando buscarMovimentacoes
        Map<String, Object> resultadoBusca = buscarMovimentacoes(
                dataInicio, dataTermino, tipoDataParaBuscar, idsEmpresa, idsCliente, idsFornecedor,
                textoPesquisa, categoria, "receita", // Filtro de tipo: receita para contas a receber
                itensPorPagina != null ? itensPorPagina : 50,
                numeroDaPagina != null ? numeroDaPagina : 1);
        
        // Extrair movimentações do resultado
        List<Map<String, Object>> todasMovimentacoes = new ArrayList<>();
        Integer totalItens = 0;
        
        if (resultadoBusca != null && resultadoBusca.containsKey("movimentacoes")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> movimentacoes = (List<Map<String, Object>>) resultadoBusca.get("movimentacoes");
            todasMovimentacoes = movimentacoes != null ? movimentacoes : new ArrayList<>();
            
            // Obter totalItens da paginação se disponível
            if (resultadoBusca.containsKey("paginacao")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> paginacao = (Map<String, Object>) resultadoBusca.get("paginacao");
                if (paginacao != null && paginacao.containsKey("totalItens")) {
                    Object totalObj = paginacao.get("totalItens");
                    if (totalObj instanceof Number) {
                        totalItens = ((Number) totalObj).intValue();
                    }
                }
            }
            
            // Se não tem totalItens na paginação, usar o total da resposta
            if (totalItens == 0 && resultadoBusca.containsKey("total")) {
                Object totalObj = resultadoBusca.get("total");
                if (totalObj instanceof Number) {
                    totalItens = ((Number) totalObj).intValue();
                }
            }
        }
        
        log.info("✅ Busca de contas a receber concluída: {} movimentações na página {} (total: {})", 
                todasMovimentacoes.size(), numeroDaPagina != null ? numeroDaPagina : 1, totalItens);
        
        // Criar resultado combinado
        Map<String, Object> resultado = new HashMap<>();
        resultado.put("movimentacoes", todasMovimentacoes);
        resultado.put("total", totalItens > 0 ? totalItens : todasMovimentacoes.size());
        
        // Adicionar informações de paginação para o frontend
        if (resultadoBusca != null && resultadoBusca.containsKey("paginacao")) {
            resultado.put("paginacao", resultadoBusca.get("paginacao"));
        } else {
            resultado.put("paginacao", Map.of(
                    "itensPorPagina", itensPorPagina != null ? itensPorPagina : 50,
                    "numeroDaPagina", numeroDaPagina != null ? numeroDaPagina : 1,
                    "totalItens", totalItens > 0 ? totalItens : todasMovimentacoes.size()
            ));
        }
        
        return resultado;
    }

    /**
     * Busca movimentações financeiras com filtros e paginação
     * Busca por TODOS os tipos de data e TODAS as páginas para garantir que nenhuma movimentação seja perdida
     */
    // Cache de totais por chave de filtros (para evitar recalcular toda vez)
    private static final Map<String, TotaisCache> cacheTotais = new ConcurrentHashMap<>();
    private static final long CACHE_TOTAIS_TTL = 5 * 60 * 1000; // 5 minutos
    
    private static class TotaisCache {
        double totalReceitas;
        double totalDespesas;
        double saldoLiquido;
        long timestamp;
        
        TotaisCache(double totalReceitas, double totalDespesas, double saldoLiquido) {
            this.totalReceitas = totalReceitas;
            this.totalDespesas = totalDespesas;
            this.saldoLiquido = saldoLiquido;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return (System.currentTimeMillis() - timestamp) > CACHE_TOTAIS_TTL;
        }
    }
    
    private String gerarChaveCacheTotais(String dataInicio, String dataTermino, String tipoData,
                                         Integer idsEmpresa, Integer idsCliente, Integer idsFornecedor,
                                         String textoPesquisa, String categoria, String tipo) {
        return String.format("%s_%s_%s_%s_%s_%s_%s_%s_%s",
                dataInicio, dataTermino, tipoData,
                idsEmpresa != null ? idsEmpresa : "null",
                idsCliente != null ? idsCliente : "null",
                idsFornecedor != null ? idsFornecedor : "null",
                textoPesquisa != null ? textoPesquisa : "null",
                categoria != null ? categoria : "null",
                tipo != null ? tipo : "null");
    }

    public Map<String, Object> buscarMovimentacoes(
            String dataInicio,
            String dataTermino,
            String tipoData,
            Integer idsEmpresa,
            Integer idsCliente,
            Integer idsFornecedor,
            String textoPesquisa,
            String categoria,
            String tipo,
            Integer itensPorPagina,
            Integer numeroDaPagina) {

        if (mockEnabled) {
            return criarRespostaMockMovimentacoes(dataInicio, dataTermino, tipo, itensPorPagina, numeroDaPagina);
        }

        // Validar parâmetros obrigatórios conforme documentação da API
        if (dataInicio == null || dataInicio.isEmpty()) {
            throw new IllegalArgumentException("Parâmetro 'dataInicio' é obrigatório para buscar movimentações");
        }
        if (dataTermino == null || dataTermino.isEmpty()) {
            throw new IllegalArgumentException("Parâmetro 'dataTermino' é obrigatório para buscar movimentações");
        }

        log.info("Buscando movimentações: dataInicio={}, dataTermino={}, tipoData={}, pagina={}", 
                dataInicio, dataTermino, tipoData, numeroDaPagina);
        
        try {
            // Tentar buscar empresa UMA VEZ antes de começar a buscar por tipos de data
            // Se não conseguir (rate limit, erro, etc), usar null e deixar API retornar todas as empresas
            Integer idsEmpresaFinal = idsEmpresa;
            if (idsEmpresaFinal == null) {
                try {
                    idsEmpresaFinal = obterOuBuscarEmpresaId();
                    if (idsEmpresaFinal == null) {
                        log.info("⚠️ Não foi possível obter ID de empresa. Buscando movimentações sem filtro de empresa (API retornará todas as empresas).");
                    } else {
                        log.info("✅ ID de empresa obtido: {} (será reutilizado para todas as buscas desta requisição)", idsEmpresaFinal);
                    }
                } catch (Exception e) {
                    log.warn("⚠️ Erro ao buscar empresa: {}. Continuando sem filtro de empresa.", e.getMessage());
                    idsEmpresaFinal = null; // Continuar sem filtro
                }
            }
            
            // IMPORTANTE: Para garantir que todas as movimentações sejam encontradas,
            // usar DataPadrao que é o mais genérico e captura movimentações independente do tipo de data
            // Isso garante que movimentações com diferentes tipos de data (vencimento, competência, criação) sejam todas encontradas
            String tipoDataParaBuscar = "DataPadrao"; // Sempre usar DataPadrao para garantir todas as movimentações
            
            log.info("📅 Usando tipoData: {} (DataPadrao garante cobertura completa de todas as movimentações no período)", tipoDataParaBuscar);
            
            // Gerar chave de cache para totais (usando sempre DataPadrao para garantir consistência)
            String chaveCacheTotais = gerarChaveCacheTotais(dataInicio, dataTermino, tipoDataParaBuscar,
                    idsEmpresaFinal, idsCliente, idsFornecedor, textoPesquisa, categoria, tipo);
            
            // Verificar se temos totais em cache válidos
            TotaisCache totaisCache = cacheTotais.get(chaveCacheTotais);
            boolean temTotaisCacheValido = totaisCache != null && !totaisCache.isExpired();
            
            // Se é primeira página e não temos cache válido, calcular totais de TODAS as movimentações
            boolean primeiraPagina = numeroDaPagina == null || numeroDaPagina == 1;
            if (primeiraPagina && !temTotaisCacheValido) {
                log.info("📊 Primeira página sem cache de totais - calculando totais de TODAS as movimentações (todas as páginas)...");
                
                // Buscar TODAS as páginas de movimentações para calcular totais corretos
                // IMPORTANTE: Não passar filtro de tipo aqui, pois queremos calcular totais de receitas E despesas
                // O filtro de tipo será aplicado apenas na página solicitada, não no cálculo de totais
                List<Map<String, Object>> todasMovimentacoes = buscarTodasPaginasMovimentacoes(
                        dataInicio, dataTermino, tipoDataParaBuscar, idsEmpresaFinal, idsCliente, idsFornecedor,
                        textoPesquisa, categoria, 
                        50); // Usar 50 itens por página para evitar rate limit
                
                // Calcular totais de todas as movimentações coletadas
                double totalReceitasGeral = 0;
                double totalDespesasGeral = 0;
                
                if (todasMovimentacoes != null && !todasMovimentacoes.isEmpty()) {
                    log.info("📊 Calculando totais de {} movimentações coletadas...", todasMovimentacoes.size());
                    
                    for (Map<String, Object> mov : todasMovimentacoes) {
                        Object debitoObj = mov.get("Debito");
                        boolean isDebito = debitoObj instanceof Boolean ? (Boolean) debitoObj : false;
                        Object valorObj = mov.get("Valor");
                        double valor = valorObj instanceof Number ? ((Number) valorObj).doubleValue() : 0;
                        
                        if (isDebito) {
                            totalDespesasGeral += valor;
                        } else {
                            totalReceitasGeral += valor;
                        }
                    }
                    
                    // Armazenar no cache
                    totaisCache = new TotaisCache(totalReceitasGeral, totalDespesasGeral, totalReceitasGeral - totalDespesasGeral);
                    cacheTotais.put(chaveCacheTotais, totaisCache);
                    
                    log.info("💰 Totais calculados e armazenados no cache: Receitas={}, Despesas={}, Saldo={} (de {} movimentações)", 
                            totalReceitasGeral, totalDespesasGeral, totalReceitasGeral - totalDespesasGeral, todasMovimentacoes.size());
                } else {
                    log.warn("⚠️ Nenhuma movimentação encontrada para calcular totais");
                }
            }
            
            log.info("🔍 Buscando movimentações usando tipo de data: {} (página {} solicitada)", 
                    tipoDataParaBuscar, numeroDaPagina != null ? numeroDaPagina : 1);
            
            // Buscar APENAS a página solicitada
            Map<String, Object> resultadoBusca = buscarMovimentacoesApi(
                    dataInicio, dataTermino, tipoDataParaBuscar, idsEmpresaFinal, idsCliente, idsFornecedor,
                    textoPesquisa, categoria, tipo, 
                    itensPorPagina != null ? itensPorPagina : 50,
                    numeroDaPagina != null ? numeroDaPagina : 1);
            
            // Se temos totais em cache, substituir os totais calculados apenas da página atual
            if (resultadoBusca != null && totaisCache != null && !totaisCache.isExpired()) {
                resultadoBusca.put("totalReceitas", totaisCache.totalReceitas);
                resultadoBusca.put("totalDespesas", totaisCache.totalDespesas);
                resultadoBusca.put("saldoLiquido", totaisCache.saldoLiquido);
                log.info("💰 Totais substituídos pelos valores do cache (de todas as movimentações)");
            }
            
            // Retornar o resultado
            if (resultadoBusca != null) {
                log.info("✅ Busca concluída: {} movimentações encontradas na página {} (total: {})", 
                        resultadoBusca.getOrDefault("movimentacoes", Collections.emptyList()) instanceof List 
                            ? ((List<?>) resultadoBusca.get("movimentacoes")).size() 
                            : 0,
                        numeroDaPagina != null ? numeroDaPagina : 1,
                        resultadoBusca.getOrDefault("total", 0));
                return resultadoBusca;
            }
            
            // Se resultadoBusca for null, retornar resposta vazia
            Map<String, Object> resultado = new HashMap<>();
            resultado.put("movimentacoes", new ArrayList<>());
            resultado.put("total", 0);
            resultado.put("totalReceitas", 0);
            resultado.put("totalDespesas", 0);
            resultado.put("saldoLiquido", 0);
            resultado.put("dataInicio", dataInicio);
            resultado.put("dataTermino", dataTermino);
            resultado.put("tipoData", tipoData);
            resultado.put("endpointUsado", "/api/bomcontrole/movimentacoes");
            resultado.put("paginacao", Map.of(
                    "itensPorPagina", itensPorPagina != null ? itensPorPagina : 50,
                    "numeroDaPagina", numeroDaPagina != null ? numeroDaPagina : 1,
                    "totalItens", 0
            ));

            return resultado;
        } finally {
            // Sempre limpar cache do ThreadLocal após processar a requisição (mesmo em caso de exceção)
            limparCacheRequisicao();
        }
    }
    
    /**
     * Método auxiliar que busca movimentações usando a API diretamente (usado internamente)
     * Este método é usado por buscarTodasPaginasMovimentacoes
     */
    private Map<String, Object> buscarMovimentacoesApi(
            String dataInicio,
            String dataTermino,
            String tipoData,
            Integer idsEmpresa,
            Integer idsCliente,
            Integer idsFornecedor,
            String textoPesquisa,
            String categoria,
            String tipo,
            Integer itensPorPagina,
            Integer numeroDaPagina) {

        try {
            // NÃO buscar empresa aqui! O idsEmpresa já deve vir do método chamador
            // Se for null, simplesmente não passar o parâmetro na requisição (API aceita sem filtro)
            Integer idsEmpresaFinal = idsEmpresa;
            
            // Formatar datas com hora (formato: "aaaa-mm-dd hh24:mi:ss")
            // Data início: 00:00:00, Data término: 23:59:59 para incluir o dia inteiro
            // Nota: A documentação diz que o formato deve incluir hora, mas o exemplo do Postman usa sem hora
            // Vamos usar com hora para garantir precisão na busca
            String dataInicioFormatada = formatarDataComHora(dataInicio, true);
            String dataTerminoFormatada = formatarDataComHora(dataTermino, false);
            
            log.debug("Datas formatadas: inicio={}, termino={}", dataInicioFormatada, dataTerminoFormatada);
            
            // Converter tipoData para o formato da API (ex: DataCriacao -> Criacao)
            log.debug("Convertendo tipoData: original={}", tipoData);
            String tipoDataFormatado = converterTipoData(tipoData);
            log.debug("tipoData convertido: {}", tipoDataFormatado);
            
            // Criar variáveis finais para usar no lambda
            final Integer idsEmpresaParaLambda = idsEmpresaFinal;
            final Integer idsClienteParaLambda = idsCliente;
            final Integer idsFornecedorParaLambda = idsFornecedor;
            final String textoPesquisaParaLambda = textoPesquisa;
            final String categoriaParaLambda = categoria;
            final String tipoParaLambda = tipo;
            final Integer itensPorPaginaParaLambda = itensPorPagina;
            final Integer numeroDaPaginaParaLambda = numeroDaPagina;
            
            // Gerar chave de cache única baseada nos parâmetros
            String cacheKey = gerarChaveCacheMovimentacoes(dataInicioFormatada, dataTerminoFormatada, 
                    tipoDataFormatado, idsEmpresaParaLambda, idsClienteParaLambda, idsFornecedorParaLambda,
                    textoPesquisaParaLambda, categoriaParaLambda, tipoParaLambda, itensPorPaginaParaLambda, numeroDaPaginaParaLambda);
            
            // Executar com rate limiting e cache
            Map<String, Object> response = rateLimiter.executeWithRateLimit(
                    cacheKey,
                    CACHE_DURATION_MS,
                    () -> {
                        // Executar requisição à API
                        log.info("🌐 Chamando API Bom Controle: dataInicio={}, dataTermino={}, tipoData={}, idsEmpresa={}", 
                                dataInicioFormatada, dataTerminoFormatada, tipoDataFormatado, idsEmpresaParaLambda);
                        
                        Map<String, Object> apiResponse = webClient.get()
                                .uri(uriBuilder -> {
                                    uriBuilder.path("/integracao/Financeiro/Pesquisar");
                                    if (dataInicioFormatada != null) uriBuilder.queryParam("dataInicio", dataInicioFormatada);
                                    if (dataTerminoFormatada != null) uriBuilder.queryParam("dataTermino", dataTerminoFormatada);
                                    if (tipoDataFormatado != null) uriBuilder.queryParam("tipoData", tipoDataFormatado);
                                    if (idsEmpresaParaLambda != null) uriBuilder.queryParam("idsEmpresa", idsEmpresaParaLambda);
                                    if (idsClienteParaLambda != null) uriBuilder.queryParam("idsCliente", idsClienteParaLambda);
                                    if (idsFornecedorParaLambda != null) uriBuilder.queryParam("idsFornecedor", idsFornecedorParaLambda);
                                    if (textoPesquisaParaLambda != null) uriBuilder.queryParam("textoPesquisa", textoPesquisaParaLambda);
                                    if (categoriaParaLambda != null) uriBuilder.queryParam("categoria", categoriaParaLambda);
                                    if (tipoParaLambda != null) {
                                        boolean despesa = tipoParaLambda.equals("despesa");
                                        uriBuilder.queryParam("despesa", despesa);
                                    }
                                    if (itensPorPaginaParaLambda != null) uriBuilder.queryParam("paginacao.itensPorPagina", itensPorPaginaParaLambda);
                                    if (numeroDaPaginaParaLambda != null) uriBuilder.queryParam("paginacao.numeroDaPagina", numeroDaPaginaParaLambda);
                                    var uri = uriBuilder.build();
                                    log.debug("URL completa da requisição: {}", uri);
                                    return uri;
                                })
                                .retrieve()
                                .bodyToMono(Map.class)
                                .block();
                        
                        if (apiResponse == null) {
                            throw new BomControleRateLimiter.RateLimitException("Resposta vazia da API");
                        }
                        
                        // Log detalhado da resposta para debug
                        log.info("Resposta da API Bom Controle: TotalItens={}, temItens={}, chaves={}", 
                                apiResponse.get("TotalItens"),
                                apiResponse.containsKey("Itens"),
                                apiResponse.keySet());
                        
                        if (apiResponse.containsKey("Itens")) {
                            Object itens = apiResponse.get("Itens");
                            if (itens instanceof List) {
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> itensList = (List<Map<String, Object>>) itens;
                                log.debug("Quantidade de itens na resposta: {}", itensList.size());
                                if (!itensList.isEmpty()) {
                                    Map<String, Object> primeiroItem = itensList.get(0);
                                    log.debug("Primeiro item (amostra completa): {}", primeiroItem);
                                    log.debug("Primeiro item - Debito: {}, Valor: {}, Nome: {}, Campos disponíveis: {}", 
                                            primeiroItem.get("Debito"), primeiroItem.get("Valor"), primeiroItem.get("Nome"), 
                                            primeiroItem.keySet());
                                    
                                    // Log de TODOS os campos para garantir que nada está sendo perdido
                                    log.info("📋 Campos disponíveis na movimentação (total: {}): {}", 
                                            primeiroItem.keySet().size(), primeiroItem.keySet());
                                }
                            }
                        }
                        
                        return apiResponse;
                    },
                    () -> {
                        // Fallback: retornar resposta vazia
                        log.warn("📦 Usando fallback (resposta vazia) devido a rate limit");
                        return null;
                    }
            );
            
            // Se não houver resultados e idsEmpresa foi usado, tentar buscar sem filtro de empresa
            if (response != null && response.containsKey("TotalItens")) {
                Object totalObj = response.get("TotalItens");
                int total = totalObj instanceof Number ? ((Number) totalObj).intValue() : 0;
                if (total == 0 && idsEmpresaParaLambda != null) {
                    log.warn("⚠️ Nenhuma movimentação encontrada com idsEmpresa={}. Isso pode indicar que o ID da empresa está incorreto ou não há dados no período especificado.", idsEmpresaParaLambda);
                    log.info("💡 Dica: Verifique se o idsEmpresa está correto. Você pode listar empresas disponíveis usando o endpoint /api/bomcontrole/empresas");
                }
            }

            return processarRespostaMovimentacoes(response, dataInicio, dataTermino, tipoData, itensPorPagina, numeroDaPagina);
            
        } catch (BomControleRateLimiter.RateLimitException e) {
            log.warn("⚠️ Rate limit detectado pelo RateLimiter. Retornando resposta vazia.");
            return processarRespostaMovimentacoes(null, dataInicio, dataTermino, tipoData, itensPorPagina, numeroDaPagina);
        } catch (WebClientResponseException e) {
            // Tratamento especial para 429 Too Many Requests (caso escape do rate limiter)
            if (e.getStatusCode() != null && e.getStatusCode().value() == 429) {
                log.warn("⚠️ Rate limit atingido na API do Bom Controle (429 Too Many Requests). Retornando resposta vazia.");
                return processarRespostaMovimentacoes(null, dataInicio, dataTermino, tipoData, itensPorPagina, numeroDaPagina);
            }
            log.error("Erro ao buscar movimentações do Bom Controle: {}", e.getResponseBodyAsString(), e);
            throw new RuntimeException("Erro ao buscar movimentações: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Erro ao buscar movimentações do Bom Controle", e);
            throw new RuntimeException("Erro ao buscar movimentações: " + e.getMessage(), e);
        }
    }
    
    /**
     * Gera chave única de cache para movimentações
     */
    private String gerarChaveCacheMovimentacoes(String dataInicio, String dataTermino, String tipoData,
                                                Integer idsEmpresa, Integer idsCliente, Integer idsFornecedor,
                                                String textoPesquisa, String categoria, String tipo,
                                                Integer itensPorPagina, Integer numeroDaPagina) {
        return String.format("movimentacoes:%s:%s:%s:%s:%s:%s:%s:%s:%s:%s:%s",
                dataInicio, dataTermino, tipoData,
                idsEmpresa != null ? idsEmpresa : "null",
                idsCliente != null ? idsCliente : "null",
                idsFornecedor != null ? idsFornecedor : "null",
                textoPesquisa != null ? textoPesquisa : "null",
                categoria != null ? categoria : "null",
                tipo != null ? tipo : "null",
                itensPorPagina != null ? itensPorPagina : "null",
                numeroDaPagina != null ? numeroDaPagina : "null");
    }

    /**
     * Gera DFC (Demonstrativo de Fluxo de Caixa)
     * Nota: A API do Bom Controle não possui endpoint específico para DFC
     * Este método calcula o DFC baseado nas movimentações
     */
    public Map<String, Object> gerarDFC(String dataInicio, String dataTermino, boolean usarCache, boolean forcarAtualizacao) {
        if (mockEnabled) {
            return criarRespostaMockDFC(dataInicio, dataTermino);
        }

        try {
            // Buscar todas as movimentações do período para calcular DFC
            String dataInicioFormatada = formatarDataComHora(dataInicio, true);
            String dataTerminoFormatada = formatarDataComHora(dataTermino, false);
            
            Map<String, Object> response = webClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/integracao/Financeiro/Pesquisar");
                        uriBuilder.queryParam("dataInicio", dataInicioFormatada);
                        uriBuilder.queryParam("dataTermino", dataTerminoFormatada);
                        uriBuilder.queryParam("tipoData", "DataCompetencia");
                        uriBuilder.queryParam("paginacao.itensPorPagina", 100); // Máximo permitido
                        uriBuilder.queryParam("paginacao.numeroDaPagina", 1);
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            // Processar movimentações e gerar DFC
            return processarDFC(response, dataInicio, dataTermino);
        } catch (Exception e) {
            log.error("Erro ao gerar DFC do Bom Controle", e);
            throw new RuntimeException("Erro ao gerar DFC: " + e.getMessage(), e);
        }
    }

    /**
     * Sincroniza movimentações de um período específico
     * Nota: A API do Bom Controle não possui endpoint de sincronização
     * Este método apenas busca as movimentações do período
     */
    public Map<String, Object> sincronizarPeriodo(String dataInicio, String dataTermino, Integer idEmpresa) {
        if (mockEnabled) {
            return Map.of(
                    "sucesso", true,
                    "modo", "MOCK",
                    "mensagem", "Sincronização simulada",
                    "idEmpresa", idEmpresa != null ? idEmpresa : 0
            );
        }

        try {
            // Buscar movimentações do período (equivalente a sincronizar)
            String dataInicioFormatada = formatarDataComHora(dataInicio, true);
            String dataTerminoFormatada = formatarDataComHora(dataTermino, false);
            
            Map<String, Object> response = webClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/integracao/Financeiro/Pesquisar");
                        if (dataInicioFormatada != null) uriBuilder.queryParam("dataInicio", dataInicioFormatada);
                        if (dataTerminoFormatada != null) uriBuilder.queryParam("dataTermino", dataTerminoFormatada);
                        if (idEmpresa != null) uriBuilder.queryParam("idsEmpresa", idEmpresa);
                        uriBuilder.queryParam("tipoData", "Criacao");
                        uriBuilder.queryParam("paginacao.itensPorPagina", 100);
                        uriBuilder.queryParam("paginacao.numeroDaPagina", 1);
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            int totalItens = 0;
            if (response != null && response.containsKey("TotalItens")) {
                Object total = response.get("TotalItens");
                if (total instanceof Number) {
                    totalItens = ((Number) total).intValue();
                }
            }

            return Map.of(
                    "sucesso", true,
                    "mensagem", "Sincronização concluída",
                    "idEmpresa", idEmpresa != null ? idEmpresa : 0,
                    "totalItens", totalItens
            );
        } catch (Exception e) {
            log.error("Erro ao sincronizar período do Bom Controle", e);
            throw new RuntimeException("Erro ao sincronizar período: " + e.getMessage(), e);
        }
    }

    /**
     * Sincronização incremental - busca movimentações modificadas recentemente
     * Nota: A API do Bom Controle não possui endpoint de sincronização incremental
     * Este método busca movimentações alteradas nas últimas 24 horas
     */
    public Map<String, Object> sincronizarIncremental(Integer idEmpresa) {
        if (mockEnabled) {
            return Map.of(
                    "sucesso", true,
                    "modo", "MOCK",
                    "mensagem", "Sincronização incremental simulada",
                    "idEmpresa", idEmpresa != null ? idEmpresa : 0
            );
        }

        try {
            // Buscar movimentações alteradas nas últimas 24 horas
            java.time.LocalDateTime agora = java.time.LocalDateTime.now();
            java.time.LocalDateTime ontem = agora.minusDays(1);
            String dataInicio = ontem.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String dataTermino = agora.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            
            Map<String, Object> response = webClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/integracao/Financeiro/Pesquisar");
                        uriBuilder.queryParam("dataInicio", dataInicio);
                        uriBuilder.queryParam("dataTermino", dataTermino);
                        if (idEmpresa != null) uriBuilder.queryParam("idsEmpresa", idEmpresa);
                        uriBuilder.queryParam("tipoData", "UltimaAlteracao");
                        uriBuilder.queryParam("paginacao.itensPorPagina", 100);
                        uriBuilder.queryParam("paginacao.numeroDaPagina", 1);
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            int totalItens = 0;
            if (response != null && response.containsKey("TotalItens")) {
                Object total = response.get("TotalItens");
                if (total instanceof Number) {
                    totalItens = ((Number) total).intValue();
                }
            }

            return Map.of(
                    "sucesso", true,
                    "mensagem", "Sincronização incremental concluída",
                    "idEmpresa", idEmpresa != null ? idEmpresa : 0,
                    "totalItens", totalItens
            );
        } catch (Exception e) {
            log.error("Erro ao sincronizar incremental do Bom Controle", e);
            throw new RuntimeException("Erro ao sincronizar incremental: " + e.getMessage(), e);
        }
    }

    /**
     * Status do cache
     * Nota: A API do Bom Controle não possui endpoint de cache
     * Este método retorna informações básicas
     */
    public Map<String, Object> statusCache() {
        if (mockEnabled) {
            return Map.of(
                    "modo", "MOCK",
                    "cacheAtivo", false,
                    "mensagem", "Modo mock - cache não disponível"
            );
        }

        // Bom Controle não possui cache na API, retorna status básico
        return Map.of(
                "cacheAtivo", false,
                "mensagem", "API do Bom Controle não possui sistema de cache"
        );
    }

    // Métodos auxiliares

    private Map<String, Object> processarRespostaMovimentacoes(
            Map<String, Object> response,
            String dataInicio,
            String dataTermino,
            String tipoData,
            Integer itensPorPagina,
            Integer numeroDaPagina) {

        List<Map<String, Object>> movimentacoes = new ArrayList<>();
        Integer totalItens = 0;
        
        if (response != null) {
            log.debug("Processando resposta: chaves disponíveis={}", response.keySet());
            
            // Bom Controle retorna "Itens" e "TotalItens"
            // IMPORTANTE: Preservamos TODOS os campos retornados pela API sem modificação
            // A API já retorna todos os campos: IdMovimentacaoFinanceiraParcela, Debito, DataVencimento,
            // DataCompetencia, DataQuitacao, DataConciliacao, Valor, FormaPagamento, NomeFormaPagamento,
            // TipoMovimentacao, NomeTipoMovimentacao, Nome, Observacao, NumeroParcela, QuantidadeParcela,
            // IdCategoriaFinanceira, NomeCategoriaFinanceira, IconeCategoriaFinanceira, IdContaFinanceira,
            // NomeContaFinanceira, NumeroConta, DigitoConta, NumeroAgencia, DigitoAgencia, NomeBanco,
            // NumeroBanco, IdEmpresa, NomeEmpresa, DocumentoEmpresa, IdCliente, IdFornecedor, IdFuncionario,
            // NomeClienteFornecedor, NomeFantasiaClienteFornecedor, DocumentoClienteFornecedor,
            // LinkBoletoBancario, LinkNotaFiscalServico, IdDepartamento, NomeDepartamento, TipoDepartamento,
            // NomeTipoDepartamento, TemRateio, ValorDefinitivo, TotalItens, DataCriacaoParcela,
            // ValorAcrescimo, ValorDesconto, ValorBruto, DataUltimaAlteracao, DataFaturamento,
            // EtiquetasMovimentacao, IdFatura, NumeroDocumento, NotaFiscalServicoParcela,
            // NotaFiscalServicoVenda, NotaFiscalProduto, NotaFiscalConsumidor
            if (response.containsKey("Itens")) {
                Object itens = response.get("Itens");
                if (itens instanceof List) {
                    movimentacoes = (List<Map<String, Object>>) itens;
                    log.debug("Movimentações extraídas: {} (TODOS os campos preservados)", movimentacoes.size());
                    
                    // Verificar se todas as movimentações têm os campos esperados
                    if (!movimentacoes.isEmpty()) {
                        Map<String, Object> primeiraMov = movimentacoes.get(0);
                        log.debug("Campos preservados na primeira movimentação: {} campos", primeiraMov.keySet().size());
                        log.debug("Campos: {}", primeiraMov.keySet());
                    }
                } else {
                    log.warn("'Itens' não é uma Lista, tipo: {}", itens != null ? itens.getClass() : "null");
                }
            } else {
                log.warn("Resposta não contém chave 'Itens'");
            }
            
            if (response.containsKey("TotalItens")) {
                Object total = response.get("TotalItens");
                if (total instanceof Number) {
                    totalItens = ((Number) total).intValue();
                }
                log.debug("TotalItens: {}", totalItens);
            }
        } else {
            log.warn("Resposta da API é null");
        }

        // Calcular totais
        double totalReceitas = 0;
        double totalDespesas = 0;
        for (Map<String, Object> mov : movimentacoes) {
            Object debitoObj = mov.get("Debito");
            boolean isDebito = debitoObj instanceof Boolean ? (Boolean) debitoObj : false;
            Object valorObj = mov.get("Valor");
            double valor = valorObj instanceof Number ? ((Number) valorObj).doubleValue() : 0;

            if (isDebito) {
                totalDespesas += valor;
            } else {
                totalReceitas += valor;
            }
        }

        int totalItensFinal = totalItens > 0 ? totalItens : movimentacoes.size();
        int itensPorPaginaFinal = itensPorPagina != null ? itensPorPagina : 50;
        int numeroDaPaginaFinal = numeroDaPagina != null ? numeroDaPagina : 1;

        Map<String, Object> resultado = new HashMap<>();
        resultado.put("movimentacoes", movimentacoes);
        resultado.put("total", totalItensFinal);
        resultado.put("totalReceitas", totalReceitas);
        resultado.put("totalDespesas", totalDespesas);
        resultado.put("saldoLiquido", totalReceitas - totalDespesas);
        resultado.put("dataInicio", dataInicio);
        resultado.put("dataTermino", dataTermino);
        resultado.put("tipoData", tipoData);
        resultado.put("endpointUsado", "/api/bomcontrole/movimentacoes");
        resultado.put("paginacao", Map.of(
                "itensPorPagina", itensPorPaginaFinal,
                "numeroDaPagina", numeroDaPaginaFinal,
                "totalItens", totalItensFinal
        ));

        return resultado;
    }

    /**
     * Processa resposta para gerar DFC
     */
    private Map<String, Object> processarDFC(Map<String, Object> response, String dataInicio, String dataTermino) {
        List<Map<String, Object>> movimentacoes = new ArrayList<>();
        
        if (response != null && response.containsKey("Itens")) {
            Object itens = response.get("Itens");
            if (itens instanceof List) {
                movimentacoes = (List<Map<String, Object>>) itens;
            }
        }

        // Agrupar por categoria e calcular totais
        Map<String, Double> receitasPorCategoria = new HashMap<>();
        Map<String, Double> despesasPorCategoria = new HashMap<>();
        double totalReceitas = 0;
        double totalDespesas = 0;

        for (Map<String, Object> mov : movimentacoes) {
            Object debitoObj = mov.get("Debito");
            boolean isDebito = debitoObj instanceof Boolean ? (Boolean) debitoObj : false;
            Object valorObj = mov.get("Valor");
            double valor = valorObj instanceof Number ? ((Number) valorObj).doubleValue() : 0;
            
            String categoria = (String) mov.getOrDefault("NomeCategoriaFinanceira", "Sem Categoria");

            if (isDebito) {
                totalDespesas += valor;
                despesasPorCategoria.put(categoria, despesasPorCategoria.getOrDefault(categoria, 0.0) + valor);
            } else {
                totalReceitas += valor;
                receitasPorCategoria.put(categoria, receitasPorCategoria.getOrDefault(categoria, 0.0) + valor);
            }
        }

        // Montar estrutura do DFC
        List<Map<String, Object>> dfc = new ArrayList<>();
        
        // Receitas
        for (Map.Entry<String, Double> entry : receitasPorCategoria.entrySet()) {
            dfc.add(Map.of(
                    "tipo", "Receita",
                    "nome", entry.getKey(),
                    "nivel", 1,
                    "valor", entry.getValue()
            ));
        }
        
        // Despesas
        for (Map.Entry<String, Double> entry : despesasPorCategoria.entrySet()) {
            dfc.add(Map.of(
                    "tipo", "Despesa",
                    "nome", entry.getKey(),
                    "nivel", 1,
                    "valor", entry.getValue()
            ));
        }

        return Map.of(
                "dfc", dfc,
                "totalReceitas", totalReceitas,
                "totalDespesas", totalDespesas,
                "resultado", totalReceitas - totalDespesas,
                "dataInicio", dataInicio != null ? dataInicio : "",
                "dataTermino", dataTermino != null ? dataTermino : "",
                "totalMovimentacoes", movimentacoes.size()
        );
    }

    /**
     * Formata data para o formato da API: "aaaa-mm-dd hh24:mi:ss"
     * @param data Data a ser formatada
     * @param isInicio Se true, adiciona 00:00:00 (início do dia), se false adiciona 23:59:59 (fim do dia)
     */
    private String formatarDataComHora(String data, boolean isInicio) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        
        // Se já tem hora, retorna como está
        if (data.contains(" ")) {
            return data;
        }
        
        // Se é só data (yyyy-MM-dd), adiciona hora apropriada
        if (data.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return data + (isInicio ? " 00:00:00" : " 23:59:59");
        }
        
        return data;
    }
    
    /**
     * Formata data para o formato da API: "aaaa-mm-dd hh24:mi:ss" (mantido para compatibilidade)
     * @deprecated Use formatarDataComHora(String data, boolean isInicio) em vez disso
     */
    @Deprecated
    private String formatarDataComHora(String data) {
        return formatarDataComHora(data, true);
    }

    /**
     * Converte tipoData do frontend para o formato da API
     */
    private String converterTipoData(String tipoData) {
        if (tipoData == null || tipoData.isEmpty()) {
            return "DataPadrao"; // Padrão mais genérico
        }
        
        // Mapear tipos do frontend para tipos da API
        // Documentação: DataPadrao, DataPrevista, DataPagamento, DataCompetencia, DataConciliacao, Criacao, UltimaAlteracao
        switch (tipoData) {
            case "DataCriacao":
            case "Criacao": // Aceitar também "Criacao" diretamente
                return "Criacao";
            case "DataVencimento":
                // DataVencimento: usar DataPrevista que corresponde à data de vencimento prevista
                // A API do Bom Controle usa DataPrevista para filtrar por data de vencimento
                return "DataPrevista";
            case "DataCompetencia":
                return "DataCompetencia";
            case "DataPagamento":
                return "DataPagamento";
            case "DataConciliacao":
                return "DataConciliacao";
            case "UltimaAlteracao":
                return "UltimaAlteracao";
            case "DataPadrao":
                return "DataPadrao";
            case "DataPrevista":
                return "DataPrevista";
            default:
                log.warn("Tipo de data desconhecido: {}, usando DataPadrao como padrão", tipoData);
                return "DataPadrao";
        }
    }

    // Métodos mock para desenvolvimento/teste

    private Map<String, Object> criarRespostaMockEmpresas() {
        return Map.of(
                "empresas", List.of(
                        Map.of("id", 1, "nome", "Empresa Mock 1", "cnpj", "00.000.000/0001-00"),
                        Map.of("id", 2, "nome", "Empresa Mock 2", "cnpj", "00.000.000/0002-00")
                )
        );
    }

    private Map<String, Object> criarRespostaMockMovimentacoes(
            String dataInicio, String dataTermino, String tipo,
            Integer itensPorPagina, Integer numeroDaPagina) {

        List<Map<String, Object>> movimentacoes = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            boolean isDebito = tipo == null || tipo.equals("despesa") || (i % 2 == 0);
            movimentacoes.add(Map.of(
                    "IdMovimentacaoFinanceiraParcela", String.valueOf(i),
                    "Debito", isDebito,
                    "DataVencimento", dataTermino != null ? dataTermino : "2024-12-31",
                    "DataCompetencia", dataInicio != null ? dataInicio : "2024-01-01",
                    "Valor", 1000.0 * i,
                    "Nome", "Movimentação Mock " + i,
                    "NomeCategoriaFinanceira", "Categoria " + i,
                    "NomeContaFinanceira", "Conta " + i,
                    "NomeEmpresa", "Empresa Mock",
                    "IdEmpresa", 1
            ));
        }

        int totalItens = movimentacoes.size();
        int itensPorPaginaFinal = itensPorPagina != null ? itensPorPagina : 50;
        int numeroDaPaginaFinal = numeroDaPagina != null ? numeroDaPagina : 1;

        return Map.of(
                "movimentacoes", movimentacoes,
                "total", totalItens,
                "totalReceitas", 5000.0,
                "totalDespesas", 5000.0,
                "saldoLiquido", 0.0,
                "dataInicio", dataInicio != null ? dataInicio : "",
                "dataTermino", dataTermino != null ? dataTermino : "",
                "endpointUsado", "/api/bomcontrole/movimentacoes",
                "paginacao", Map.of(
                        "itensPorPagina", itensPorPaginaFinal,
                        "numeroDaPagina", numeroDaPaginaFinal,
                        "totalItens", totalItens
                )
        );
    }

    private Map<String, Object> criarRespostaMockDFC(String dataInicio, String dataTermino) {
        return Map.of(
                "dfc", List.of(
                        Map.of("tipo", "Receita", "nome", "Vendas", "nivel", 1, "valor", 10000.0),
                        Map.of("tipo", "Despesa", "nome", "Custos", "nivel", 1, "valor", 5000.0)
                ),
                "totalReceitas", 10000.0,
                "totalDespesas", 5000.0,
                "resultado", 5000.0,
                "dataInicio", dataInicio != null ? dataInicio : "",
                "dataTermino", dataTermino != null ? dataTermino : "",
                "totalMovimentacoes", 10
        );
    }
}
