package com.finnza.service;

import com.finnza.dto.response.DfcResponseDTO;
import com.finnza.dto.response.ResumoFinanceiroDTO;
import com.finnza.dto.response.ResumoFinanceiroPeriodosDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
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
     * IMPORTANTE: Chamar limparCacheRequisicao() no finally após usar este método
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
     * Deve ser chamado no finally após chamar obterOuBuscarEmpresaId()
     * Essencial para evitar vazamento de memória e compartilhamento de dados entre requisições
     */
    private void limparCacheRequisicao() {
        try {
            empresaIdPorRequisicao.remove();
            log.debug("✅ ThreadLocal de empresa limpado para esta requisição");
        } catch (Exception e) {
            log.warn("⚠️ Erro ao limpar ThreadLocal: {}", e.getMessage());
        }
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
     * Obtém lista de todas as empresas disponíveis no Bom Controle
     * Cache: 10 minutos
     * 
     * @return Lista de maps com dados das empresas (Id, Nome, etc)
     */
    public List<Map<String, Object>> obterTodasAsEmpresas() {
        try {
            Map<String, Object> resultado = listarEmpresas(null);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> empresas = (List<Map<String, Object>>) resultado.get("empresas");
            return empresas != null ? empresas : Collections.emptyList();
        } catch (Exception e) {
            log.error("Erro ao obter todas as empresas: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Obtém o nome de uma empresa específica pelo ID (BOMControle)
     * 
     * @param idEmpresa ID da empresa
     * @return Optional contendo o nome da empresa ou vazio se não encontrada
     */
    public Optional<String> obterNomeEmpresa(Integer idEmpresa) {
        if (idEmpresa == null || idEmpresa <= 0) {
            return Optional.empty();
        }
        
        try {
            List<Map<String, Object>> empresas = obterTodasAsEmpresas();
            return empresas.stream()
                    .filter(emp -> idEmpresa.equals(((Number) emp.get("Id")).intValue()))
                    .map(emp -> (String) emp.get("Nome"))
                    .filter(nome -> nome != null && !nome.isEmpty())
                    .findFirst();
        } catch (Exception e) {
            log.error("Erro ao obter nome da empresa {}: {}", idEmpresa, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Busca TODAS as páginas de movimentações para um tipo de data específico
     */
    private Map<String, Object> buscarTodasPaginasMovimentacoes(
            String dataInicio,
            String dataTermino,
            String tipoData,
            Integer idsEmpresa,
            Integer idsCliente,
            Integer idsFornecedor,
            String textoPesquisa,
            String categoria,
            String tipo,
            int itensPorPagina) {

        List<Map<String, Object>> todasMovimentacoes = new ArrayList<>();
        int paginaAtual = 1;
        int totalPaginasEstimadas = 1;
        boolean continuar = true;
        int paginasProcessadas = 0;
        int paginasViaFallback = 0;
        int totalItensEstimados = 0;
        Instant timestampUltimoBatchValido = null;
        boolean fallbackDetectado = false;
        long fallbackReferenciaTime = 0L;
        boolean fallbackLogEmitido = false;

        while (continuar) {
            try {
                Map<String, Object> resultado = buscarMovimentacoesApi(
                        dataInicio, dataTermino, tipoData, idsEmpresa, idsCliente, idsFornecedor,
                        textoPesquisa, categoria, tipo, itensPorPagina, paginaAtual);

                boolean paginaFallback = resultado == null || Boolean.TRUE.equals(resultado.get("usouFallback"));
                if (paginaFallback) {
                    paginasViaFallback++;
                    long ultimoRateLimitTime = obterUltimoEventoRateLimit();
                    if (ultimoRateLimitTime <= 0) {
                        ultimoRateLimitTime = System.currentTimeMillis();
                    }
                    if (estaEmJanelaFallback(ultimoRateLimitTime)) {
                        fallbackDetectado = true;
                        fallbackReferenciaTime = ultimoRateLimitTime;
                        if (!fallbackLogEmitido) {
                            long ttlRestanteMs = calcularTtlRestanteMs(ultimoRateLimitTime);
                            double ttlRestanteMin = ttlRestanteMs / 60000d;
                            log.warn("⚠️ Bom Controle retornou fallback na página {} (páginas válidas {}/{}). Registros com fallback: true. TTL do snapshot degradado (CACHE_RESUMO_STALE_TTL={}ms): {}ms (~{} min). Consulte os endpoints /api/bomcontrole/snapshots e /api/bomcontrole/degradacao para acompanhar o painel de degradação.",
                                    paginaAtual,
                                    paginasProcessadas,
                                    totalPaginasEstimadas,
                                    CACHE_RESUMO_STALE_TTL,
                                    ttlRestanteMs,
                                    String.format(Locale.ROOT, "%.2f", ttlRestanteMin));
                            fallbackLogEmitido = true;
                        }
                    }
                } else {
                    paginasProcessadas++;
                    timestampUltimoBatchValido = Instant.now();
                }

                if (resultado != null && resultado.containsKey("movimentacoes")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> movimentacoes = resultado.get("movimentacoes") instanceof List
                            ? (List<Map<String, Object>>) resultado.get("movimentacoes")
                            : Collections.emptyList();

                    if (!movimentacoes.isEmpty()) {
                        todasMovimentacoes.addAll(movimentacoes);
                    }

                    Object totalItensObj = resultado.get("total");
                    int totalItens = totalItensObj instanceof Number ? ((Number) totalItensObj).intValue() : 0;
                    if (totalItens > 0) {
                        totalItensEstimados = totalItens;
                        totalPaginasEstimadas = (int) Math.ceil((double) totalItens / itensPorPagina);
                    }

                    log.debug("📄 Página {}: {} movimentações (acumulado: {}, totalItens: {}, paginas estimadas: {}, fallbackPagina={})",
                            paginaAtual, movimentacoes.size(), todasMovimentacoes.size(), totalItens, totalPaginasEstimadas, paginaFallback);

                    boolean ultimaPaginaPorQtd = movimentacoes.size() < itensPorPagina;
                    boolean coletouTudoPorTotal = totalItens > 0 && todasMovimentacoes.size() >= totalItens;

                    if (ultimaPaginaPorQtd) {
                        continuar = false;
                        log.debug("✅ Última página alcançada (retornou {} < {})", movimentacoes.size(), itensPorPagina);
                    } else if (coletouTudoPorTotal) {
                        continuar = false;
                        log.debug("✅ Todas as movimentações coletadas ({}/{})", todasMovimentacoes.size(), totalItens);
                    } else {
                        paginaAtual++;
                        try {
                            Thread.sleep(150);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            continuar = false;
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

        Map<String, Object> resultado = montarResultadoPaginacao(
                todasMovimentacoes,
                paginasProcessadas,
                totalPaginasEstimadas,
                paginasViaFallback,
                timestampUltimoBatchValido,
                fallbackDetectado,
                fallbackReferenciaTime,
                totalItensEstimados);

        log.info("📚 Busca paginada concluída. Páginas válidas: {}/{}, fallbackAtivo={}, totalMovimentacoes={}",
                paginasProcessadas,
                totalPaginasEstimadas,
                fallbackDetectado,
                todasMovimentacoes.size());
        return resultado;
    }

    private Map<String, Object> montarResultadoPaginacao(
            List<Map<String, Object>> movimentacoes,
            int paginasProcessadas,
            int paginasEstimadas,
            int paginasViaFallback,
            Instant timestampUltimoBatchValido,
            boolean fallbackAtivo,
            long fallbackReferenciaTime,
            int totalItensEstimados) {

        int paginasFaltantes = Math.max(paginasEstimadas - paginasProcessadas, 0);
        Map<String, Object> fallbackMetadata = new HashMap<>();
        fallbackMetadata.put("fallbackAtivo", fallbackAtivo);
        fallbackMetadata.put("paginasProcessadas", paginasProcessadas);
        fallbackMetadata.put("paginasEstimadas", paginasEstimadas);
        fallbackMetadata.put("paginasFaltantes", paginasFaltantes);
        fallbackMetadata.put("paginasViaFallback", paginasViaFallback);
        fallbackMetadata.put("timestampUltimoBatchValido", timestampUltimoBatchValido != null ? timestampUltimoBatchValido.toString() : null);
        fallbackMetadata.put("totalItensEstimados", totalItensEstimados);
        fallbackMetadata.put("totalMovimentacoesParcial", movimentacoes.size());

        if (fallbackAtivo) {
            long referencia = fallbackReferenciaTime > 0 ? fallbackReferenciaTime : System.currentTimeMillis();
            long ttlRestante = calcularTtlRestanteMs(referencia);
            fallbackMetadata.put("ttlFallbackRestanteMs", ttlRestante);
            fallbackMetadata.put("janelaFallbackInicio", Instant.ofEpochMilli(referencia).toString());
            fallbackMetadata.put("janelaFallbackFim", Instant.ofEpochMilli(referencia + CACHE_RESUMO_STALE_TTL).toString());
        }

        Map<String, Object> resultado = new HashMap<>();
        resultado.put("movimentacoes", movimentacoes);
        resultado.put("paginasProcessadas", paginasProcessadas);
        resultado.put("paginasEstimadas", paginasEstimadas);
        resultado.put("paginasFaltantes", paginasFaltantes);
        resultado.put("paginasViaFallback", paginasViaFallback);
        resultado.put("timestampUltimoBatch", timestampUltimoBatchValido != null ? timestampUltimoBatchValido.toString() : null);
        resultado.put("fallbackAtivo", fallbackAtivo);
        resultado.put("totalItensEstimados", totalItensEstimados);
        resultado.put("fallbackMetadata", fallbackMetadata);

        return resultado;
    }

    private long obterUltimoEventoRateLimit() {
        try {
            Map<String, Object> stats = rateLimiter.getStats();
            Object lastRateLimit = stats.get("lastRateLimitTime");
            if (lastRateLimit instanceof Number) {
                return ((Number) lastRateLimit).longValue();
            }
        } catch (Exception e) {
            log.debug("Não foi possível consultar stats do rate limiter: {}", e.getMessage());
        }
        return 0L;
    }

    private boolean estaEmJanelaFallback(long lastRateLimitTime) {
        if (lastRateLimitTime <= 0) {
            return false;
        }
        return (System.currentTimeMillis() - lastRateLimitTime) < CACHE_RESUMO_STALE_TTL;
    }

    private long calcularTtlRestanteMs(long fallbackReferenceTime) {
        long fimJanela = fallbackReferenceTime + CACHE_RESUMO_STALE_TTL;
        return Math.max(0, fimJanela - System.currentTimeMillis());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extrairMovimentacoes(Map<String, Object> resultadoPaginado) {
        if (resultadoPaginado == null) {
            return Collections.emptyList();
        }
        Object movs = resultadoPaginado.get("movimentacoes");
        if (movs instanceof List) {
            return (List<Map<String, Object>>) movs;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extrairFallbackMetadata(Map<String, Object> resultadoPaginado) {
        if (resultadoPaginado == null) {
            return Collections.emptyMap();
        }
        Object metadata = resultadoPaginado.get("fallbackMetadata");
        if (metadata instanceof Map) {
            return (Map<String, Object>) metadata;
        }
        return Collections.emptyMap();
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
            textoPesquisa, categoria, "despesa", null, // Filtro de tipo: despesa para contas a pagar
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
            textoPesquisa, categoria, "receita", null, // Filtro de tipo: receita para contas a receber
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
    private static final Map<String, ResumoFinanceiroCache> cacheResumoFinanceiro = new ConcurrentHashMap<>();
    private static final Map<String, DfcCache> cacheDfc = new ConcurrentHashMap<>();
    private static final long CACHE_TOTAIS_TTL = 5 * 60 * 1000; // 5 minutos
    private static final long CACHE_RESUMO_TTL = 2 * 60 * 1000; // 2 minutos
    private static final long CACHE_RESUMO_STALE_TTL = 30 * 60 * 1000; // 30 minutos para snapshot
    private static final long CACHE_DFC_TTL = 5 * 60 * 1000; // 5 minutos
    private static final DateTimeFormatter MES_FORMATTER = DateTimeFormatter.ofPattern("MMM/yy", new Locale("pt", "BR"));
        private static final List<String> KEYWORDS_FATURAMENTO = List.of(
            "contrato", "assinatura", "mensal", "faturamento", "servico", "recorrente", "licenca", "plano", "nf"
        );
        private static final List<String> KEYWORDS_OUTRAS_ENTRADAS = List.of(
            "juros", "rendiment", "rebate", "reembolso", "aporte", "capital", "investidor", "dividendo", "ajuste", "premio"
        );
        private static final List<String> KEYWORDS_CUSTOS = List.of(
            "custo", "infra", "servidor", "cloud", "aws", "azure", "gcp", "licenca", "software", "operacao"
        );
        private static final List<String> KEYWORDS_ESTRATEGIA = List.of(
            "marketing", "venda", "comercial", "expansao", "evento", "growth", "campanha", "midia", "publicidade"
        );
        private static final List<String> KEYWORDS_INVESTIMENTOS = List.of(
            "equipamento", "maquina", "capex", "imobilizado", "pesquisa", "desenvolvimento", "implantacao", "upgrade", "melhoria"
        );
        private static final List<String> KEYWORDS_FINANCIAMENTO = List.of(
            "financi", "emprest", "bndes", "leasing", "mutuo", "debenture", "capital de giro", "credito", "banco"
        );
    
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

    private static class ResumoFinanceiroCache {
        final ResumoFinanceiroDTO resumo;
        final long timestamp;

        ResumoFinanceiroCache(ResumoFinanceiroDTO resumo) {
            this.resumo = resumo;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return (System.currentTimeMillis() - timestamp) > CACHE_RESUMO_TTL;
        }

        boolean isOlderThan(long maxAgeMs) {
            return (System.currentTimeMillis() - timestamp) > maxAgeMs;
        }
    }

    private static class DfcCache {
        final DfcResponseDTO resposta;
        final long timestamp;

        DfcCache(DfcResponseDTO resposta) {
            this.resposta = resposta;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return (System.currentTimeMillis() - timestamp) > CACHE_DFC_TTL;
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

    private String gerarChaveCacheDfc(String dataInicio, String dataTermino, Integer idsEmpresa) {
        return String.format("dfc:%s:%s:%s",
                dataInicio,
                dataTermino,
                idsEmpresa != null ? idsEmpresa : "null");
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
            String statusPagamento,
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

            String statusFiltro = statusPagamento != null ? statusPagamento.trim().toLowerCase() : null;
            boolean aplicarFiltroStatus = statusFiltro != null && !statusFiltro.isEmpty() && !"todas".equals(statusFiltro);

            if (aplicarFiltroStatus) {
                log.info("🎯 Aplicando filtro de status '{}' para movimentações", statusFiltro);
                return buscarMovimentacoesComFiltroStatus(
                        dataInicio,
                        dataTermino,
                        tipoDataParaBuscar,
                        idsEmpresaFinal,
                        idsCliente,
                        idsFornecedor,
                        textoPesquisa,
                        categoria,
                        tipo,
                        statusFiltro,
                        itensPorPagina,
                        numeroDaPagina);
            }
            
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
                Map<String, Object> resultadoPaginado = buscarTodasPaginasMovimentacoes(
                    dataInicio, dataTermino, tipoDataParaBuscar, idsEmpresaFinal, idsCliente, idsFornecedor,
                    textoPesquisa, categoria, 
                    null,
                    50); // Usar 50 itens por página para evitar rate limit

                List<Map<String, Object>> todasMovimentacoes = extrairMovimentacoes(resultadoPaginado);
                boolean fallbackAtivo = resultadoPaginado != null && Boolean.TRUE.equals(resultadoPaginado.get("fallbackAtivo"));
                if (fallbackAtivo) {
                    log.warn("⚠️ Totais gerais calculados com dados possivelmente degradados (fallback ativo). Metadata: {}",
                            extrairFallbackMetadata(resultadoPaginado));
                }

                // Calcular totais de todas as movimentações coletadas
                double totalReceitasGeral = 0;
                double totalDespesasGeral = 0;
                
                if (!todasMovimentacoes.isEmpty()) {
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

    private Map<String, Object> buscarMovimentacoesComFiltroStatus(
            String dataInicio,
            String dataTermino,
            String tipoData,
            Integer idsEmpresa,
            Integer idsCliente,
            Integer idsFornecedor,
            String textoPesquisa,
            String categoria,
            String tipo,
            String statusFiltro,
            Integer itensPorPagina,
            Integer numeroDaPagina) {

        int itensPagina = (itensPorPagina != null && itensPorPagina > 0) ? itensPorPagina : 50;
        int paginaSolicitada = (numeroDaPagina != null && numeroDaPagina > 0) ? numeroDaPagina : 1;
        int itensPorPaginaConsulta = Math.max(itensPagina, 50);

        Map<String, Object> resultadoPaginado = buscarTodasPaginasMovimentacoes(
                dataInicio,
                dataTermino,
                tipoData,
                idsEmpresa,
                idsCliente,
                idsFornecedor,
                textoPesquisa,
                categoria,
                tipo,
            itensPorPaginaConsulta);

        List<Map<String, Object>> todasMovimentacoes = extrairMovimentacoes(resultadoPaginado);
        Map<String, Object> fallbackMetadata = extrairFallbackMetadata(resultadoPaginado);
        boolean fallbackAtivo = resultadoPaginado != null && Boolean.TRUE.equals(resultadoPaginado.get("fallbackAtivo"));
        if (fallbackAtivo) {
            log.warn("⚠️ Filtro por status trabalhando com dados degradados. Metadata: {}", fallbackMetadata);
        }

        List<Map<String, Object>> filtradas = todasMovimentacoes.stream()
                .filter(mov -> correspondeAoStatus(mov, statusFiltro))
                .collect(java.util.stream.Collectors.toList());

        int totalFiltradas = filtradas.size();
        int totalPaginas = totalFiltradas == 0 ? 1 : (int) Math.ceil((double) totalFiltradas / itensPagina);
        if (totalFiltradas == 0) {
            paginaSolicitada = 1;
        } else if (paginaSolicitada > totalPaginas) {
            paginaSolicitada = totalPaginas;
        }

        int fromIndex = (paginaSolicitada - 1) * itensPagina;
        if (fromIndex > totalFiltradas) {
            fromIndex = Math.max(0, totalFiltradas - itensPagina);
        }
        int toIndex = Math.min(fromIndex + itensPagina, totalFiltradas);

        List<Map<String, Object>> pagina = filtradas.subList(fromIndex, toIndex);

        double totalReceitas = 0;
        double totalDespesas = 0;
        for (Map<String, Object> mov : filtradas) {
            double valor = extrairValorMovimentacao(mov.get("Valor"));
            boolean debito = isDebito(mov.get("Debito"));
            if (debito) {
                totalDespesas += valor;
            } else {
                totalReceitas += valor;
            }
        }

        Map<String, Object> resultado = new HashMap<>();
        resultado.put("movimentacoes", new ArrayList<>(pagina));
        resultado.put("total", totalFiltradas);
        resultado.put("totalReceitas", totalReceitas);
        resultado.put("totalDespesas", totalDespesas);
        resultado.put("saldoLiquido", totalReceitas - totalDespesas);
        resultado.put("dataInicio", dataInicio);
        resultado.put("dataTermino", dataTermino);
        resultado.put("tipoData", tipoData);
        resultado.put("endpointUsado", "/api/bomcontrole/movimentacoes");
        resultado.put("paginacao", Map.of(
                "itensPorPagina", itensPagina,
                "numeroDaPagina", paginaSolicitada,
                "totalItens", totalFiltradas,
                "totalPaginas", totalFiltradas == 0 ? 1 : totalPaginas
        ));

        resultado.put("fallbackAtivo", fallbackAtivo);
        resultado.put("fallbackMetadata", fallbackMetadata);

        return resultado;
    }

    private boolean correspondeAoStatus(Map<String, Object> movimentacao, String statusFiltro) {
        if (statusFiltro == null || statusFiltro.isBlank() || "todas".equals(statusFiltro)) {
            return true;
        }

        Object dataQuitacao = movimentacao.get("DataQuitacao");
        boolean recebido;
        if (dataQuitacao instanceof String) {
            recebido = !((String) dataQuitacao).isBlank();
        } else {
            recebido = dataQuitacao != null;
        }

        if ("pendente".equals(statusFiltro)) {
            return !recebido;
        }
        if ("recebido".equals(statusFiltro) || "liquidado".equals(statusFiltro)) {
            return recebido;
        }
        return true;
    }

    private double extrairValorMovimentacao(Object valorObj) {
        if (valorObj instanceof Number) {
            return ((Number) valorObj).doubleValue();
        }
        if (valorObj instanceof String) {
            try {
                String sanitized = ((String) valorObj).replace("R$", "").replace(" ", "").replace(".", "").replace(",", ".");
                return Double.parseDouble(sanitized);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private boolean isDebito(Object debitoObj) {
        if (debitoObj instanceof Boolean) {
            return (Boolean) debitoObj;
        }
        if (debitoObj instanceof Number) {
            return ((Number) debitoObj).intValue() != 0;
        }
        if (debitoObj instanceof String) {
            return Boolean.parseBoolean(((String) debitoObj));
        }
        return false;
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
        } finally {
            // Limpar cache por requisição do ThreadLocal
            limparCacheRequisicao();
        }
    }

    public ResumoFinanceiroDTO gerarResumoFinanceiro(
            String dataInicio,
            String dataTermino,
            String tipoData,
            Integer idsEmpresa,
            Integer idsCliente,
            Integer idsFornecedor,
            String textoPesquisa,
            String categoria,
            String tipo) {

        if (dataInicio == null || dataInicio.isBlank()) {
            throw new IllegalArgumentException("Parâmetro 'dataInicio' é obrigatório para o resumo financeiro");
        }
        if (dataTermino == null || dataTermino.isBlank()) {
            throw new IllegalArgumentException("Parâmetro 'dataTermino' é obrigatório para o resumo financeiro");
        }

        String tipoDataParaBuscar = "DataPadrao";
        Integer idsEmpresaFinal = idsEmpresa != null ? idsEmpresa : obterOuBuscarEmpresaId();
        String chaveCache = gerarChaveCacheTotais(
                dataInicio, dataTermino, tipoDataParaBuscar,
                idsEmpresaFinal, idsCliente, idsFornecedor,
                textoPesquisa, categoria, tipo) + ":resumo";

        try {
            ResumoFinanceiroCache cache = cacheResumoFinanceiro.get(chaveCache);
            if (cache != null && !cache.isExpired()) {
                log.debug("♻️ Resumo financeiro atendido via cache para chave {}", chaveCache);
                return cache.resumo.toBuilder()
                        .usandoCache(true)
                        .fonteDados("bom-controle/cache")
                        .build();
            }

            Map<String, Object> resultadoPaginado = buscarTodasPaginasMovimentacoes(
                    dataInicio,
                    dataTermino,
                    tipoDataParaBuscar,
                    idsEmpresaFinal,
                    idsCliente,
                    idsFornecedor,
                    textoPesquisa,
                    categoria,
                    null,
                    50);

            List<Map<String, Object>> todasMovimentacoes = extrairMovimentacoes(resultadoPaginado);
            Map<String, Object> fallbackMetadata = extrairFallbackMetadata(resultadoPaginado);
            boolean fallbackAtivo = resultadoPaginado != null && Boolean.TRUE.equals(resultadoPaginado.get("fallbackAtivo"));
            if (fallbackAtivo) {
                log.warn("⚠️ Resumo financeiro calculado durante janela de fallback. Metadata: {}", fallbackMetadata);
            }

            double totalReceitas = 0;
            double receitasLiquidadas = 0;
            double receitasPendentes = 0;
            long totalReceitasCount = 0;
            long receitasPendentesCount = 0;

            double totalDespesas = 0;
            double despesasPagas = 0;
            double despesasPendentes = 0;
            long totalDespesasCount = 0;
            long despesasPendentesCount = 0;

            for (Map<String, Object> mov : todasMovimentacoes) {
                boolean isDebito = converterParaBooleano(mov.get("Debito"));
                double valor = extrairValor(mov.get("Valor"));
                boolean liquidado = isLiquidado(mov);

                if (isDebito) {
                    totalDespesas += valor;
                    totalDespesasCount++;
                    if (liquidado) {
                        despesasPagas += valor;
                    } else {
                        despesasPendentes += valor;
                        despesasPendentesCount++;
                    }
                } else {
                    totalReceitas += valor;
                    totalReceitasCount++;
                    if (liquidado) {
                        receitasLiquidadas += valor;
                    } else {
                        receitasPendentes += valor;
                        receitasPendentesCount++;
                    }
                }
            }

            double saldoDisponivel = receitasLiquidadas - despesasPagas;
            double saldoProjetado = totalReceitas - totalDespesas;

                String fonteDados = fallbackAtivo ? "bom-controle/fallback" : "bom-controle/api";

                ResumoFinanceiroDTO resumo = ResumoFinanceiroDTO.builder()
                    .periodo(ResumoFinanceiroDTO.PeriodoResumo.builder()
                            .dataInicio(dataInicio)
                            .dataTermino(dataTermino)
                            .build())
                    .contasReceber(ResumoFinanceiroDTO.BlocoResumo.builder()
                            .totalGeral(totalReceitas)
                            .totalLiquidado(receitasLiquidadas)
                            .totalPendente(receitasPendentes)
                            .totalContas(totalReceitasCount)
                            .contasPendentes(receitasPendentesCount)
                            .build())
                    .contasPagar(ResumoFinanceiroDTO.BlocoResumo.builder()
                            .totalGeral(totalDespesas)
                            .totalLiquidado(despesasPagas)
                            .totalPendente(despesasPendentes)
                            .totalContas(totalDespesasCount)
                            .contasPendentes(despesasPendentesCount)
                            .build())
                    .saldoDisponivel(saldoDisponivel)
                    .saldoProjetado(saldoProjetado)
                    .totalMovimentacoes(todasMovimentacoes.size())
                    .usandoCache(false)
                        .fonteDados(fonteDados)
                    .atualizadoEm(LocalDateTime.now().toString())
                        .fallbackAtivo(fallbackAtivo)
                        .fallbackMetadata(fallbackMetadata)
                    .build();

            cacheResumoFinanceiro.put(chaveCache, new ResumoFinanceiroCache(resumo));
            return resumo;
        } finally {
            limparCacheRequisicao();
        }
    }

            public ResumoFinanceiroPeriodosDTO gerarResumoFinanceiroPeriodosPadrao(
                String tipoData,
                Integer idsEmpresa,
                Integer idsCliente,
                Integer idsFornecedor,
                String textoPesquisa,
                String categoria,
                String tipo) {

            LocalDate hoje = LocalDate.now();
            String inicioMes = hoje.withDayOfMonth(1).toString();
            String fimMes = hoje.toString();
            String inicioAno = hoje.withDayOfYear(1).toString();
            String fimAno = hoje.toString();

            ResumoFinanceiroDTO mesAtual = gerarResumoFinanceiro(
                inicioMes,
                fimMes,
                tipoData,
                idsEmpresa,
                idsCliente,
                idsFornecedor,
                textoPesquisa,
                categoria,
                tipo
            );

            ResumoFinanceiroDTO anoAtual = gerarResumoFinanceiro(
                inicioAno,
                fimAno,
                tipoData,
                idsEmpresa,
                idsCliente,
                idsFornecedor,
                textoPesquisa,
                categoria,
                tipo
            );

            return ResumoFinanceiroPeriodosDTO.builder()
                .mesAtual(mesAtual)
                .anoAtual(anoAtual)
                .build();
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
    public DfcResponseDTO gerarDFC(
            String dataInicio,
            String dataTermino,
            Boolean usarCache,
            Boolean forcarAtualizacao,
            Integer idsEmpresa) {

        if (mockEnabled) {
            return criarRespostaMockDFC(dataInicio, dataTermino);
        }

        if (dataInicio == null || dataInicio.isBlank()) {
            throw new IllegalArgumentException("Parâmetro 'dataInicio' é obrigatório para o DFC");
        }
        if (dataTermino == null || dataTermino.isBlank()) {
            throw new IllegalArgumentException("Parâmetro 'dataTermino' é obrigatório para o DFC");
        }

        LocalDate inicio = LocalDate.parse(dataInicio);
        LocalDate termino = LocalDate.parse(dataTermino);
        if (termino.isBefore(inicio)) {
            throw new IllegalArgumentException("'dataTermino' deve ser maior ou igual a 'dataInicio'");
        }

        boolean usarCacheEfetivo = usarCache == null || usarCache;
        boolean forcarAtualizacaoEfetivo = forcarAtualizacao != null && forcarAtualizacao;
        Integer idsEmpresaFinal = idsEmpresa != null ? idsEmpresa : obterOuBuscarEmpresaId();
        String chaveCache = gerarChaveCacheDfc(dataInicio, dataTermino, idsEmpresaFinal);

        try {
            if (usarCacheEfetivo && !forcarAtualizacaoEfetivo) {
                DfcCache cache = cacheDfc.get(chaveCache);
                if (cache != null && !cache.isExpired()) {
                    log.debug("♻️ DFC atendido via cache para chave {}", chaveCache);
                    return cache.resposta.toBuilder()
                            .usandoCache(true)
                            .fonteDados("bom-controle/cache")
                            .build();
                }
            }

            long inicioProcessamento = System.currentTimeMillis();

            Map<String, Object> resultadoPaginado = buscarTodasPaginasMovimentacoes(
                    dataInicio,
                    dataTermino,
                    "DataQuitacao", // Alterado de DataCompetencia para DataQuitacao (Regime de Caixa)
                    idsEmpresaFinal,
                    null,
                    null,
                    null,
                    null,
                    null,
                    50);

            List<Map<String, Object>> movimentacoes = extrairMovimentacoes(resultadoPaginado);
            Map<String, Object> fallbackMetadata = extrairFallbackMetadata(resultadoPaginado);
            boolean fallbackAtivo = resultadoPaginado != null && Boolean.TRUE.equals(resultadoPaginado.get("fallbackAtivo"));
            long paginasProcessadas = extrairLong(resultadoPaginado, "paginasProcessadas");
            long paginasEstimadas = extrairLong(resultadoPaginado, "paginasEstimadas");
            long totalDisponiveis = extrairLong(resultadoPaginado, "totalItensEstimados");
            long tempoProcessamentoMs = System.currentTimeMillis() - inicioProcessamento;

            DfcResponseDTO resposta = montarDfcResponse(
                    movimentacoes,
                    inicio,
                    termino,
                    fallbackAtivo,
                    fallbackMetadata,
                    paginasProcessadas,
                    paginasEstimadas,
                    totalDisponiveis,
                    tempoProcessamentoMs);

            cacheDfc.put(chaveCache, new DfcCache(resposta));
            return resposta;
        } finally {
            limparCacheRequisicao();
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

        boolean respostaValida = response != null;
        List<Map<String, Object>> movimentacoes = new ArrayList<>();
        Integer totalItens = 0;
        
        if (respostaValida) {
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
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> itensLista = (List<Map<String, Object>>) itens;
                    movimentacoes = itensLista;
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
        resultado.put("usouFallback", !respostaValida);

        return resultado;
    }

    private DfcResponseDTO montarDfcResponse(
            List<Map<String, Object>> movimentacoes,
            LocalDate dataInicio,
            LocalDate dataTermino,
            boolean fallbackAtivo,
            Map<String, Object> fallbackMetadata,
            long paginasProcessadas,
            long paginasEstimadas,
            long totalDisponiveis,
            long tempoProcessamentoMs) {

        List<YearMonth> intervalo = gerarIntervaloMensal(dataInicio, dataTermino);
        if (intervalo.isEmpty()) {
            intervalo = List.of(YearMonth.from(dataInicio));
        }

        Map<YearMonth, Integer> indicePorMes = new HashMap<>();
        for (int i = 0; i < intervalo.size(); i++) {
            indicePorMes.put(intervalo.get(i), i);
        }

        Map<String, LinhaDfcAccumulator> acumuladores = new LinkedHashMap<>();
        double[] receitasPorMes = new double[intervalo.size()];
        double[] despesasPorMes = new double[intervalo.size()];
        double totalReceitas = 0;
        double totalDespesas = 0;
        Set<String> clientesDistintos = new HashSet<>();

        for (Map<String, Object> movimentacao : movimentacoes) {
            LocalDate dataMov = extrairDataMovimentacao(movimentacao);
            if (dataMov == null) {
                continue;
            }

            YearMonth competencia = YearMonth.from(dataMov);
            Integer indiceMes = indicePorMes.get(competencia);
            if (indiceMes == null) {
                continue;
            }

            double valor = Math.abs(extrairValor(movimentacao.get("Valor")));
            if (valor == 0) {
                continue;
            }

                DfcGrupo grupo = classificarGrupo(movimentacao);
            if (grupo == DfcGrupo.TRANSFERENCIA_INTERNA) {
                continue;
            }

            String nomeLinha = extrairNomeLinhaDfc(movimentacao);
            String chaveLinha = grupo.name() + "|" + nomeLinha;
                DfcGrupo grupoFinal = grupo;
                String nomeLinhaFinal = nomeLinha;
                int tamanhoIntervalo = intervalo.size();
            LinhaDfcAccumulator accumulator = acumuladores.computeIfAbsent(
                    chaveLinha,
                    key -> new LinhaDfcAccumulator(grupoFinal, nomeLinhaFinal, tamanhoIntervalo));

            accumulator.adicionarValor(indiceMes, valor);

            if (grupo.isReceita()) {
                totalReceitas += valor;
                receitasPorMes[indiceMes] += valor;
            } else if (grupo.isDespesa()) {
                totalDespesas += valor;
                despesasPorMes[indiceMes] += valor;
            }

            registrarCliente(movimentacao, clientesDistintos);
        }

        List<DfcResponseDTO.Linha> linhas = montarLinhasOrdenadas(
                acumuladores,
                intervalo.size(),
                receitasPorMes,
                despesasPorMes);

        double resultado = totalReceitas - totalDespesas;
        double margemPercentual = totalReceitas == 0 ? 0 : (resultado / totalReceitas) * 100d;
        double ticketMedio = clientesDistintos.isEmpty() ? 0 : totalReceitas / clientesDistintos.size();
        double burnRateMensal = intervalo.isEmpty() ? 0 : totalDespesas / intervalo.size();

        DfcResponseDTO.Indicadores indicadores = DfcResponseDTO.Indicadores.builder()
                .faturamentoNovosContratos(somarGrupo(acumuladores, DfcGrupo.FATURAMENTO))
                .receitasOperacionais(somarGrupo(acumuladores, DfcGrupo.RECEITA_OPERACIONAL))
                .outrasEntradas(somarGrupo(acumuladores, DfcGrupo.OUTRAS_ENTRADAS))
                .custosOperacionais(somarGrupo(acumuladores, DfcGrupo.CUSTO_OPERACIONAL))
                .despesasOperacionais(somarGrupo(acumuladores, DfcGrupo.DESPESA_OPERACIONAL))
                .atividadesEstrategicas(somarGrupo(acumuladores, DfcGrupo.ATIVIDADE_ESTRATEGICA))
                .investimentos(somarGrupo(acumuladores, DfcGrupo.INVESTIMENTO))
                .financiamentos(somarGrupo(acumuladores, DfcGrupo.FINANCIAMENTO))
                .totalReceitas(totalReceitas)
                .totalDespesas(totalDespesas)
                .resultado(resultado)
                .margemPercentual(margemPercentual)
                .ticketMedio(ticketMedio)
                .burnRateMensal(burnRateMensal)
                .build();

        return DfcResponseDTO.builder()
                .periodo(DfcResponseDTO.Periodo.builder()
                        .dataInicio(dataInicio.toString())
                        .dataTermino(dataTermino.toString())
                        .build())
                .meses(formatarMeses(intervalo))
                .linhas(linhas)
                .indicadores(indicadores)
                .fonteDados(fallbackAtivo ? "bom-controle/fallback" : "bom-controle/api")
                .fallbackAtivo(fallbackAtivo)
                .fallbackMetadata(fallbackMetadata)
                .totalMovimentacoesProcessadas(movimentacoes.size())
                .totalMovimentacoesDisponiveis(totalDisponiveis > 0 ? totalDisponiveis : movimentacoes.size())
                .paginasProcessadas(paginasProcessadas)
                .paginasEstimadas(paginasEstimadas)
                .tempoProcessamentoMs(tempoProcessamentoMs)
                .usandoCache(false)
                .atualizadoEm(LocalDateTime.now().toString())
                .build();
    }

    private List<DfcResponseDTO.Linha> montarLinhasOrdenadas(
            Map<String, LinhaDfcAccumulator> acumuladores,
            int quantidadeMeses,
            double[] receitasPorMes,
            double[] despesasPorMes) {

        List<DfcResponseDTO.Linha> linhas = new ArrayList<>();
        linhas.add(criarLinhaSecao("FATURAMENTO (NOVOS CONTRATOS)", quantidadeMeses));
        adicionarLinhasPorGrupo(linhas, acumuladores, DfcGrupo.FATURAMENTO);

        linhas.add(criarLinhaSecao("TOTAL RECEITAS", quantidadeMeses));
        adicionarLinhasPorGrupo(linhas, acumuladores, DfcGrupo.RECEITA_OPERACIONAL);
        adicionarLinhasPorGrupo(linhas, acumuladores, DfcGrupo.OUTRAS_ENTRADAS);
        linhas.add(criarSubtotalLinha("Subtotal Receitas", "SUBTOTAL_RECEITA", receitasPorMes));

        linhas.add(criarLinhaSecao("TOTAL DESPESAS", quantidadeMeses));
        adicionarLinhasPorGrupo(linhas, acumuladores, DfcGrupo.CUSTO_OPERACIONAL);
        adicionarLinhasPorGrupo(linhas, acumuladores, DfcGrupo.DESPESA_OPERACIONAL);
        adicionarLinhasPorGrupo(linhas, acumuladores, DfcGrupo.ATIVIDADE_ESTRATEGICA);
        adicionarLinhasPorGrupo(linhas, acumuladores, DfcGrupo.INVESTIMENTO);
        adicionarLinhasPorGrupo(linhas, acumuladores, DfcGrupo.FINANCIAMENTO);
        linhas.add(criarSubtotalLinha("Subtotal Despesas", "SUBTOTAL_DESPESA", despesasPorMes));

        linhas.add(criarResultadoLinha(receitasPorMes, despesasPorMes));
        return linhas;
    }

    private void adicionarLinhasPorGrupo(List<DfcResponseDTO.Linha> destino,
                                         Map<String, LinhaDfcAccumulator> acumuladores,
                                         DfcGrupo grupo) {

        List<LinhaDfcAccumulator> linhas = acumuladores.values().stream()
                .filter(acc -> acc.grupo == grupo)
                .sorted(Comparator.comparingDouble(LinhaDfcAccumulator::getTotal).reversed())
                .collect(Collectors.toList());

        for (LinhaDfcAccumulator accumulator : linhas) {
            destino.add(accumulator.toDto());
        }
    }

    private DfcResponseDTO.Linha criarLinhaSecao(String nome, int quantidadeMeses) {
        return DfcResponseDTO.Linha.builder()
                .nome(nome)
                .tipo("SECAO")
                .nivel(0)
                .valores(criarListaNula(quantidadeMeses))
                .total(0)
                .media(0)
                .build();
    }

    private static DfcResponseDTO.Linha criarSubtotalLinha(String nome, String tipo, double[] valores) {
        double total = Arrays.stream(valores).sum();
        return DfcResponseDTO.Linha.builder()
                .nome(nome)
                .tipo(tipo)
                .nivel(1)
                .valores(converterArrayParaLista(valores))
                .total(total)
                .media(calcularMedia(valores))
                .build();
    }

    private static DfcResponseDTO.Linha criarResultadoLinha(double[] receitasPorMes, double[] despesasPorMes) {
        double[] resultado = new double[receitasPorMes.length];
        for (int i = 0; i < receitasPorMes.length; i++) {
            resultado[i] = receitasPorMes[i] - despesasPorMes[i];
        }
        double total = Arrays.stream(resultado).sum();
        return DfcResponseDTO.Linha.builder()
                .nome("RESULTADO")
                .tipo("RESULTADO")
                .nivel(0)
                .valores(converterArrayParaLista(resultado))
                .total(total)
                .media(calcularMedia(resultado))
                .build();
    }

    private static List<Double> converterArrayParaLista(double[] valores) {
        List<Double> lista = new ArrayList<>(valores.length);
        for (double valor : valores) {
            lista.add(arredondar(valor));
        }
        return lista;
    }

    private List<Double> criarListaNula(int tamanho) {
        List<Double> valores = new ArrayList<>(tamanho);
        for (int i = 0; i < tamanho; i++) {
            valores.add(null);
        }
        return valores;
    }

    private static double calcularMedia(double[] valores) {
        if (valores.length == 0) {
            return 0;
        }
        double soma = 0;
        int mesesComValor = 0;
        for (double valor : valores) {
            soma += valor;
            if (valor != 0) {
                mesesComValor++;
            }
        }
        int divisor = mesesComValor > 0 ? mesesComValor : valores.length;
        return divisor == 0 ? 0 : soma / divisor;
    }

    private double somarGrupo(Map<String, LinhaDfcAccumulator> acumuladores, DfcGrupo grupo) {
        return acumuladores.values().stream()
                .filter(acc -> acc.grupo == grupo)
                .mapToDouble(LinhaDfcAccumulator::getTotal)
                .sum();
    }

    private void registrarCliente(Map<String, Object> movimentacao, Set<String> clientesDistintos) {
        Object idCliente = movimentacao.get("IdCliente");
        if (idCliente != null && !idCliente.toString().isBlank()) {
            clientesDistintos.add(idCliente.toString());
            return;
        }
        Object nomeCliente = movimentacao.get("NomeClienteFornecedor");
        if (nomeCliente instanceof String nome && !nome.isBlank()) {
            clientesDistintos.add(nome);
        }
    }

    private LocalDate extrairDataMovimentacao(Map<String, Object> movimentacao) {
        // DFC (Demonstrativo de Fluxo de Caixa) segue o Regime de Caixa.
        // A prioridade deve ser a data em que o dinheiro efetivamente entrou ou saiu da conta.
        List<String> campos = List.of(
                "DataQuitacao",      // 1. Quando foi efetivamente pago/recebido (realizado)
                "DataPagamento",     // 2. Alternativa para quitação
                "DataVencimento",    // 3. Quando deveria ser pago/recebido (projetado)
                "DataPrevista",      // 4. Alternativa para vencimento
                "DataCompetencia",   // 5. Quando o fato gerador ocorreu (último recurso)
                "DataCriacaoParcela" // 6. Data de criação do registro
        );
        for (String campo : campos) {
            LocalDate data = converterParaData(movimentacao.get(campo));
            if (data != null) {
                return data;
            }
        }
        return null;
    }

    private LocalDate converterParaData(Object valor) {
        if (valor instanceof LocalDate data) {
            return data;
        }
        if (valor instanceof LocalDateTime dataHora) {
            return dataHora.toLocalDate();
        }
        if (valor instanceof String texto && !texto.isBlank()) {
            String normalizado = texto.trim();
            try {
                if (normalizado.length() >= 10) {
                    return LocalDate.parse(normalizado.substring(0, 10));
                }
                return LocalDate.parse(normalizado);
            } catch (Exception e) {
                try {
                    return LocalDateTime.parse(normalizado).toLocalDate();
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private DfcGrupo classificarGrupo(Map<String, Object> movimentacao) {
        if (pareceTransferenciaInterna(movimentacao)) {
            return DfcGrupo.TRANSFERENCIA_INTERNA;
        }

        boolean debito = converterParaBooleano(movimentacao.get("Debito"));
        String categoria = normalizarTexto(movimentacao.get("NomeCategoriaFinanceira"));
        String nome = normalizarTexto(movimentacao.get("Nome"));
        String tipoMovimentacao = normalizarTexto(movimentacao.get("NomeTipoMovimentacao"));
        int tipoNumerico = movimentacao.get("TipoMovimentacao") instanceof Number numero ? numero.intValue() : 0;

        if (!debito) {
            if (contemAlgumaPalavra(categoria, KEYWORDS_FATURAMENTO) ||
                    contemAlgumaPalavra(nome, KEYWORDS_FATURAMENTO) ||
                    tipoNumerico == 1) {
                return DfcGrupo.FATURAMENTO;
            }
            if (contemAlgumaPalavra(categoria, KEYWORDS_OUTRAS_ENTRADAS) || tipoNumerico == 15) {
                return DfcGrupo.OUTRAS_ENTRADAS;
            }
            return DfcGrupo.RECEITA_OPERACIONAL;
        }

        if (contemAlgumaPalavra(categoria, KEYWORDS_INVESTIMENTOS) || contemAlgumaPalavra(nome, KEYWORDS_INVESTIMENTOS)) {
            return DfcGrupo.INVESTIMENTO;
        }
        if (contemAlgumaPalavra(categoria, KEYWORDS_FINANCIAMENTO) ||
                contemAlgumaPalavra(tipoMovimentacao, KEYWORDS_FINANCIAMENTO) ||
                tipoNumerico == 16) {
            return DfcGrupo.FINANCIAMENTO;
        }
        if (contemAlgumaPalavra(categoria, KEYWORDS_ESTRATEGIA) || contemAlgumaPalavra(nome, KEYWORDS_ESTRATEGIA)) {
            return DfcGrupo.ATIVIDADE_ESTRATEGICA;
        }
        if (contemAlgumaPalavra(categoria, KEYWORDS_CUSTOS)) {
            return DfcGrupo.CUSTO_OPERACIONAL;
        }
        return DfcGrupo.DESPESA_OPERACIONAL;
    }

    private String extrairNomeLinhaDfc(Map<String, Object> movimentacao) {
        Object categoria = movimentacao.get("NomeCategoriaFinanceira");
        if (categoria instanceof String cat && !cat.isBlank()) {
            return cat.trim();
        }
        Object tipo = movimentacao.get("NomeTipoMovimentacao");
        if (tipo instanceof String nomeTipo && !nomeTipo.isBlank()) {
            return nomeTipo.trim();
        }
        Object nome = movimentacao.get("Nome");
        if (nome instanceof String texto && !texto.isBlank()) {
            return texto.trim();
        }
        boolean debito = converterParaBooleano(movimentacao.get("Debito"));
        return debito ? "Despesa sem categoria" : "Receita sem categoria";
    }

    private String normalizarTexto(Object valor) {
        if (valor == null) {
            return "";
        }
        String texto = Normalizer.normalize(valor.toString().trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return texto.replaceAll("[^\\p{ASCII}]", "");
    }

    private boolean contemAlgumaPalavra(String textoNormalizado, List<String> palavras) {
        if (textoNormalizado == null || textoNormalizado.isBlank()) {
            return false;
        }
        for (String palavra : palavras) {
            if (textoNormalizado.contains(palavra)) {
                return true;
            }
        }
        return false;
    }

    private boolean pareceTransferenciaInterna(Map<String, Object> movimentacao) {
        String nome = normalizarTexto(movimentacao.get("Nome"));
        String categoria = normalizarTexto(movimentacao.get("NomeCategoriaFinanceira"));
        String tipo = normalizarTexto(movimentacao.get("NomeTipoMovimentacao"));

        if (nome.contains("transfer") || categoria.contains("transfer") || tipo.contains("transfer")) {
            return true;
        }

        Object tipoMovimentacao = movimentacao.get("TipoMovimentacao");
        if (tipoMovimentacao instanceof Number numero) {
            int codigo = numero.intValue();
            if (codigo == 22 || codigo == 23) {
                return true;
            }
        }
        return false;
    }

    private List<YearMonth> gerarIntervaloMensal(LocalDate inicio, LocalDate termino) {
        List<YearMonth> meses = new ArrayList<>();
        YearMonth atual = YearMonth.from(inicio);
        YearMonth limite = YearMonth.from(termino);
        while (!atual.isAfter(limite)) {
            meses.add(atual);
            atual = atual.plusMonths(1);
        }
        return meses;
    }

    private List<String> formatarMeses(List<YearMonth> intervalo) {
        return intervalo.stream()
                .map(ym -> MES_FORMATTER.format(ym.atDay(1)).toLowerCase(Locale.ROOT))
                .collect(Collectors.toList());
    }

    private long extrairLong(Map<String, Object> mapa, String chave) {
        if (mapa == null) {
            return 0L;
        }
        Object valor = mapa.get(chave);
        if (valor instanceof Number numero) {
            return numero.longValue();
        }
        return 0L;
    }

    private static double arredondar(double valor) {
        return Math.round(valor * 100.0) / 100.0;
    }

    private enum DfcGrupo {
        FATURAMENTO("FATURAMENTO", "Faturamento"),
        RECEITA_OPERACIONAL("RECEITA", "Receitas Operacionais"),
        OUTRAS_ENTRADAS("RECEITA", "Outras Entradas"),
        CUSTO_OPERACIONAL("DESPESA", "Custos Operacionais"),
        DESPESA_OPERACIONAL("DESPESA", "Despesas Operacionais"),
        ATIVIDADE_ESTRATEGICA("DESPESA", "Atividades Estrategicas"),
        INVESTIMENTO("DESPESA", "Atividades de Investimento"),
        FINANCIAMENTO("DESPESA", "Atividades de Financiamento"),
        TRANSFERENCIA_INTERNA("IGNORAR", "Transferencias Internas");

        private final String tipoLinha;
        private final String rotulo;

        DfcGrupo(String tipoLinha, String rotulo) {
            this.tipoLinha = tipoLinha;
            this.rotulo = rotulo;
        }

        boolean isReceita() {
            return this == FATURAMENTO || "RECEITA".equals(tipoLinha);
        }

        boolean isDespesa() {
            return "DESPESA".equals(tipoLinha);
        }

        String getTipoLinha() {
            return tipoLinha;
        }

        String getRotulo() {
            return rotulo;
        }
    }

    private static class LinhaDfcAccumulator {
        private final DfcGrupo grupo;
        private final String nome;
        private final String tipoLinha;
        private final double[] valores;
        private double total;

        LinhaDfcAccumulator(DfcGrupo grupo, String nome, int quantidadeMeses) {
            this.grupo = grupo;
            this.nome = nome;
            this.tipoLinha = grupo.getTipoLinha();
            this.valores = new double[quantidadeMeses];
        }

        void adicionarValor(int indiceMes, double valor) {
            valores[indiceMes] += valor;
            total += valor;
        }

        double getTotal() {
            return total;
        }

        DfcResponseDTO.Linha toDto() {
            return DfcResponseDTO.Linha.builder()
                    .nome(nome)
                    .tipo(tipoLinha)
                    .nivel(1)
                    .grupo(grupo.getRotulo())
                    .valores(converterArrayParaLista(valores))
                    .total(total)
                    .media(calcularMedia(valores))
                    .build();
        }
    }

    private boolean isLiquidado(Map<String, Object> movimentacao) {
        if (movimentacao == null) {
            return false;
        }

        Object dataQuitacao = movimentacao.get("DataQuitacao");
        if (dataQuitacao instanceof String && !((String) dataQuitacao).isBlank()) {
            return true;
        }
        if (dataQuitacao != null) {
            return true;
        }

        Object dataConciliacao = movimentacao.get("DataConciliacao");
        if (dataConciliacao instanceof String && !((String) dataConciliacao).isBlank()) {
            return true;
        }
        if (dataConciliacao != null) {
            return true;
        }

        Object pago = movimentacao.get("Pago");
        if (pago != null && converterParaBooleano(pago)) {
            return true;
        }

        Object quitado = movimentacao.get("Quitado");
        return quitado != null && converterParaBooleano(quitado);
    }

    private double extrairValor(Object valorObj) {
        if (valorObj instanceof Number number) {
            return number.doubleValue();
        }
        if (valorObj instanceof String valorStr) {
            try {
                String normalizado = valorStr.trim();
                if (normalizado.isEmpty()) {
                    return 0;
                }
                if (normalizado.contains(",")) {
                    normalizado = normalizado.replace(".", "").replace(",", ".");
                }
                return Double.parseDouble(normalizado);
            } catch (NumberFormatException e) {
                log.debug("Valor inválido recebido do Bom Controle: {}", valorStr);
            }
        }
        return 0;
    }

    private boolean converterParaBooleano(Object valor) {
        if (valor instanceof Boolean boolVal) {
            return boolVal;
        }
        if (valor instanceof Number numberVal) {
            return numberVal.intValue() != 0;
        }
        if (valor instanceof String texto) {
            String normalizado = texto.trim().toLowerCase(Locale.ROOT);
            if (normalizado.isEmpty()) {
                return false;
            }
            return normalizado.equals("true") || normalizado.equals("1") || normalizado.equals("sim");
        }
        return false;
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

        private DfcResponseDTO criarRespostaMockDFC(String dataInicio, String dataTermino) {
        LocalDate inicio = dataInicio != null && !dataInicio.isBlank()
            ? LocalDate.parse(dataInicio)
            : LocalDate.now().minusMonths(5).withDayOfMonth(1);
        LocalDate termino = dataTermino != null && !dataTermino.isBlank()
            ? LocalDate.parse(dataTermino)
            : LocalDate.now();
        List<YearMonth> intervalo = gerarIntervaloMensal(inicio, termino);
        if (intervalo.isEmpty()) {
            intervalo = List.of(YearMonth.from(inicio));
        }
        int meses = intervalo.size();
        java.util.Random random = new java.util.Random();

        double[] faturamento = gerarSerieAleatoria(meses, 80000, 140000, random);
        double[] receitasOperacionais = gerarSerieAleatoria(meses, 90000, 180000, random);
        double[] outrasEntradas = gerarSerieAleatoria(meses, 3000, 15000, random);
        double[] custos = gerarSerieAleatoria(meses, 35000, 65000, random);
        double[] despesasOperacionais = gerarSerieAleatoria(meses, 25000, 55000, random);
        double[] estrategicas = gerarSerieAleatoria(meses, 8000, 20000, random);
        double[] investimentos = gerarSerieAleatoria(meses, 5000, 15000, random);
        double[] financiamentos = gerarSerieAleatoria(meses, 4000, 12000, random);

        double[] receitasTotais = new double[meses];
        double[] despesasTotais = new double[meses];
        for (int i = 0; i < meses; i++) {
            receitasTotais[i] = faturamento[i] + receitasOperacionais[i] + outrasEntradas[i];
            despesasTotais[i] = custos[i] + despesasOperacionais[i] + estrategicas[i] + investimentos[i] + financiamentos[i];
        }

        List<DfcResponseDTO.Linha> linhas = new ArrayList<>();
        linhas.add(criarLinhaSecao("FATURAMENTO (NOVOS CONTRATOS)", meses));
        linhas.add(DfcResponseDTO.Linha.builder()
            .nome("NFs Emitidas (Conforme Recebimento)")
            .tipo("FATURAMENTO")
            .nivel(1)
            .valores(converterArrayParaLista(faturamento))
            .total(Arrays.stream(faturamento).sum())
            .media(calcularMedia(faturamento))
            .build());

        linhas.add(criarLinhaSecao("TOTAL RECEITAS", meses));
        linhas.add(DfcResponseDTO.Linha.builder()
            .nome("1. Receitas Operacionais")
            .tipo("RECEITA")
            .nivel(1)
            .valores(converterArrayParaLista(receitasOperacionais))
            .total(Arrays.stream(receitasOperacionais).sum())
            .media(calcularMedia(receitasOperacionais))
            .build());
        linhas.add(DfcResponseDTO.Linha.builder()
            .nome("2. Outras Entradas")
            .tipo("RECEITA")
            .nivel(1)
            .valores(converterArrayParaLista(outrasEntradas))
            .total(Arrays.stream(outrasEntradas).sum())
            .media(calcularMedia(outrasEntradas))
            .build());
        linhas.add(criarSubtotalLinha("Subtotal Receitas", "SUBTOTAL_RECEITA", receitasTotais));

        linhas.add(criarLinhaSecao("TOTAL DESPESAS", meses));
        linhas.add(DfcResponseDTO.Linha.builder()
            .nome("1. Custos Operacionais")
            .tipo("DESPESA")
            .nivel(1)
            .valores(converterArrayParaLista(custos))
            .total(Arrays.stream(custos).sum())
            .media(calcularMedia(custos))
            .build());
        linhas.add(DfcResponseDTO.Linha.builder()
            .nome("2. Despesas Operacionais")
            .tipo("DESPESA")
            .nivel(1)
            .valores(converterArrayParaLista(despesasOperacionais))
            .total(Arrays.stream(despesasOperacionais).sum())
            .media(calcularMedia(despesasOperacionais))
            .build());
        linhas.add(DfcResponseDTO.Linha.builder()
            .nome("3. Atividades Estratégicas")
            .tipo("DESPESA")
            .nivel(1)
            .valores(converterArrayParaLista(estrategicas))
            .total(Arrays.stream(estrategicas).sum())
            .media(calcularMedia(estrategicas))
            .build());
        linhas.add(DfcResponseDTO.Linha.builder()
            .nome("4. Atividades de Investimento")
            .tipo("DESPESA")
            .nivel(1)
            .valores(converterArrayParaLista(investimentos))
            .total(Arrays.stream(investimentos).sum())
            .media(calcularMedia(investimentos))
            .build());
        linhas.add(DfcResponseDTO.Linha.builder()
            .nome("5. Atividades de Financiamento")
            .tipo("DESPESA")
            .nivel(1)
            .valores(converterArrayParaLista(financiamentos))
            .total(Arrays.stream(financiamentos).sum())
            .media(calcularMedia(financiamentos))
            .build());
        linhas.add(criarSubtotalLinha("Subtotal Despesas", "SUBTOTAL_DESPESA", despesasTotais));
        linhas.add(criarResultadoLinha(receitasTotais, despesasTotais));

        double totalReceitas = Arrays.stream(receitasTotais).sum();
        double totalDespesas = Arrays.stream(despesasTotais).sum();

        return DfcResponseDTO.builder()
            .periodo(DfcResponseDTO.Periodo.builder()
                .dataInicio(inicio.toString())
                .dataTermino(termino.toString())
                .build())
            .meses(formatarMeses(intervalo))
            .linhas(linhas)
            .indicadores(DfcResponseDTO.Indicadores.builder()
                .faturamentoNovosContratos(Arrays.stream(faturamento).sum())
                .receitasOperacionais(Arrays.stream(receitasOperacionais).sum())
                .outrasEntradas(Arrays.stream(outrasEntradas).sum())
                .custosOperacionais(Arrays.stream(custos).sum())
                .despesasOperacionais(Arrays.stream(despesasOperacionais).sum())
                .atividadesEstrategicas(Arrays.stream(estrategicas).sum())
                .investimentos(Arrays.stream(investimentos).sum())
                .financiamentos(Arrays.stream(financiamentos).sum())
                .totalReceitas(totalReceitas)
                .totalDespesas(totalDespesas)
                .resultado(totalReceitas - totalDespesas)
                .margemPercentual(totalReceitas == 0 ? 0 : (totalReceitas - totalDespesas) / totalReceitas * 100d)
                .ticketMedio((totalReceitas) / Math.max(1, meses))
                .burnRateMensal(totalDespesas / meses)
                .build())
            .fonteDados("bom-controle/mock")
            .fallbackAtivo(false)
            .fallbackMetadata(Map.of())
            .totalMovimentacoesProcessadas(0)
            .totalMovimentacoesDisponiveis(0)
            .paginasProcessadas(0)
            .paginasEstimadas(0)
            .tempoProcessamentoMs(0)
            .usandoCache(false)
            .atualizadoEm(LocalDateTime.now().toString())
            .build();
        }

        private double[] gerarSerieAleatoria(int tamanho, int valorMinimo, int valorMaximo, java.util.Random random) {
        double[] valores = new double[tamanho];
        int amplitude = Math.max(1, valorMaximo - valorMinimo);
        for (int i = 0; i < tamanho; i++) {
            valores[i] = valorMinimo + random.nextInt(amplitude);
        }
        return valores;
        }
}
