package com.finnza.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finnza.domain.entity.MovimentacaoFinanceira;
import com.finnza.domain.entity.SyncStatus;
import com.finnza.dto.response.DfcResponseDTO;
import com.finnza.dto.response.ResumoFinanceiroDTO;
import com.finnza.dto.response.ResumoFinanceiroPeriodosDTO;
import com.finnza.repository.EmpresaUsuarioRepository;
import com.finnza.repository.MovimentacaoFinanceiraRepository;
import com.finnza.repository.SyncStatusRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * Mecanismo de sincronização entre o Bom Controle e o banco local.
 *
 * <p>Fluxo:
 * <pre>
 *   API Bom Controle → BomControleSyncService (jobs agendados) → bc_movimentacoes (PostgreSQL)
 *                                                                    ↑
 *                                                    Telas leem daqui (sem rate limit)
 * </pre>
 *
 * <p>Jobs:
 * <ul>
 *   <li>A cada 20 min  → sincroniza o mês atual (dados recentes)</li>
 *   <li>Diariamente às 03h → sincroniza os últimos 3 meses</li>
 *   <li>Semanalmente às 04h (domingo) → sincroniza até 12 meses históricos</li>
 * </ul>
 */
@Slf4j
@Service
public class BomControleSyncService {

    // ── Dependências ──────────────────────────────────────────────────────────

    private final MovimentacaoFinanceiraRepository movimentacaoRepo;
    private final SyncStatusRepository syncStatusRepo;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    @Autowired
    private BomControleService bomControleService;

    @Autowired
    private EmpresaUsuarioRepository empresaUsuarioRepo;

    // ── Configuração ──────────────────────────────────────────────────────────

    @Value("${bomcontrole.default.empresa.id:1}")
    private Integer defaultEmpresaId;

    // ── Controle de bootstrap de empresas novas ──────────────────────────────

    /**
     * Empresas já conhecidas (com dados no banco ou cuja sincronização já foi disparada).
     * Persiste durante a vida da aplicação — detecta novas empresas sem precisar reiniciar.
     */
    private final Set<Integer> empresasBootstrapped = ConcurrentHashMap.newKeySet();

    /** Mínimo de registros para considerar empresa já com dados */
    @Value("${bomcontrole.sync.startup.min-registros:50}")
    private long minRegistrosParaBootstrap;

    /** Quantos meses históricos buscar ao detectar empresa nova */
    @Value("${bomcontrole.sync.startup.meses:18}")
    private int mesesHistoricoBootstrap;

    /** Delay entre meses no bootstrap de empresa nova (ms) */
    @Value("${bomcontrole.sync.startup.delay-entre-meses-ms:10000}")
    private long delayBootstrapEntreMesesMs;

    /**
     * Semáforo global: garante que apenas UMA thread faz chamadas à API do Bom Controle por vez.
     * Evita rate limit causado por jobs agendados + startup runner rodando simultaneamente.
     */
    private static final Semaphore API_SYNC_LOCK = new Semaphore(1, true);

    /** ms entre páginas para respeitar o rate limit */
    private static final long DELAY_ENTRE_PAGINAS_MS = 700;

    /** ms de espera ao receber 429 */
    private static final long ESPERA_RATE_LIMIT_MS = 65_000;

    private static final int ITENS_POR_PAGINA = 100;

    // ── Construtor ────────────────────────────────────────────────────────────

    @Autowired
    public BomControleSyncService(
            MovimentacaoFinanceiraRepository movimentacaoRepo,
            SyncStatusRepository syncStatusRepo,
            ObjectMapper objectMapper,
            @Value("${bomcontrole.api.key:}") String apiKey,
            @Value("${bomcontrole.api.url:https://apinewintegracao.bomcontrole.com.br}") String baseUrl,
            @Value("${bomcontrole.mock.enabled:false}") boolean mockEnabled) {

        this.movimentacaoRepo = movimentacaoRepo;
        this.syncStatusRepo   = syncStatusRepo;
        this.objectMapper     = objectMapper;

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(15 * 1024 * 1024))
                .build();

        String authHeader = (mockEnabled || apiKey == null || apiKey.isBlank())
                ? "ApiKey mock"
                : "ApiKey " + apiKey;

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, authHeader)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .exchangeStrategies(strategies)
                .build();

        log.info("✅ BomControleSyncService inicializado — baseUrl={}, mock={}", baseUrl, mockEnabled);
    }

    // =========================================================================
    // JOBS AGENDADOS
    // =========================================================================

    /**
     * A cada 20 min: sincroniza o mês atual para que os dados estejam quase em tempo real.
     * initialDelay = 35 min para não colidir com o startup runner que roda logo após a inicialização.
     */
    @Scheduled(fixedDelay = 20 * 60 * 1000L, initialDelay = 35 * 60 * 1000L)
    public void jobSyncMesAtual() {
        LocalDate hoje = LocalDate.now();
        List<Integer> empresas = getEmpresasParaSync();

        // Sincroniza mês atual + próximos 6 meses (para capturar pendentes futuros)
        for (int offset = 0; offset <= 6; offset++) {
            YearMonth ym = YearMonth.from(hoje).plusMonths(offset);
            String inicio = ym.atDay(1).toString();
            String fim    = ym.atEndOfMonth().toString();
            log.info("⏰ [JOB] sync {} ({} a {}) — {} empresa(s)",
                    offset == 0 ? "mês atual" : "mês +" + offset, inicio, fim, empresas.size());
            for (Integer idEmpresa : empresas) {
                try {
                    sincronizarPeriodo(inicio, fim, idEmpresa, false);
                    Thread.sleep(5_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.error("❌ Erro no job syncMesAtual empresa={} periodo={}", idEmpresa, ym, e);
                }
            }
        }
    }

    /**
     * A cada 2 min: verifica se existe alguma empresa nova (sem dados no banco).
     * É só uma query leve — não chama API. Se detectar empresa nova, dispara o sync histórico
     * em background sem esperar o job de 20 min.
     * initialDelay = 60s (deixar o startup runner começar primeiro).
     */
    @Scheduled(fixedDelay = 2 * 60 * 1000L, initialDelay = 60_000L)
    public void jobDetectarEmpresasNovas() {
        try {
            List<Integer> empresas = getEmpresasParaSync();
            detectarEBootstrapEmpresasNovas(empresas);
        } catch (Exception e) {
            log.warn("⚠️ Erro no job de detecção de empresas novas: {}", e.getMessage());
        }
    }

    /**
     * Para cada empresa da lista, verifica se já tem dados no banco.
     * Se não tiver (empresa nova cadastrada em produção), dispara sync histórico em background.
     * Opera com um Set em memória para não re-disparar a cada execução do job.
     */
    private void detectarEBootstrapEmpresasNovas(List<Integer> empresas) {
        for (Integer idEmpresa : empresas) {
            if (empresasBootstrapped.contains(idEmpresa)) {
                continue; // já conhecida, pular
            }
            try {
                LocalDate h = LocalDate.now();
                long count = movimentacaoRepo.countByIdEmpresaAndDataVencimentoBetween(
                        idEmpresa, h.minusYears(3), h.plusMonths(1));
                if (count >= minRegistrosParaBootstrap) {
                    empresasBootstrapped.add(idEmpresa); // existente, marcar como conhecida
                    log.debug("🏢 Empresa={} já possui {} registros — marcada como conhecida", idEmpresa, count);
                } else {
                    // empresa nova ou vazia — marca ANTES de disparar para não repetir
                    empresasBootstrapped.add(idEmpresa);
                    log.info("🆕 [AUTO-BOOTSTRAP] Empresa={} detectada com {} registros — disparando sync histórico em background", idEmpresa, count);
                    dispararBootstrapBackground(idEmpresa);
                }
            } catch (Exception e) {
                log.warn("⚠️ Erro ao verificar registros para empresa={}: {}", idEmpresa, e.getMessage());
            }
        }
    }

    /**
     * Dispara o sync histórico de 18 meses para uma empresa em thread separada.
     * Usado quando uma nova empresa é detectada durante um job agendado.
     */
    private void dispararBootstrapBackground(Integer idEmpresa) {
        Thread t = new Thread(() -> {
            YearMonth atual = YearMonth.now();
            log.info("🚀 [AUTO-BOOTSTRAP] Iniciando sync histórico de {} meses para empresa={}",
                    mesesHistoricoBootstrap, idEmpresa);
            int sucesso = 0;
            for (int i = 0; i < mesesHistoricoBootstrap; i++) {
                YearMonth ym = atual.minusMonths(i);
                try {
                    sincronizarPeriodo(ym.atDay(1).toString(), ym.atEndOfMonth().toString(), idEmpresa, true);
                    sucesso++;
                    Thread.sleep(delayBootstrapEntreMesesMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("❌ [AUTO-BOOTSTRAP] Erro ao sincronizar {} empresa={}: {}", ym, idEmpresa, e.getMessage());
                }
            }
            // Sincroniza também os próximos 6 meses para capturar pendentes futuros
            for (int i = 1; i <= 6; i++) {
                YearMonth ym = atual.plusMonths(i);
                try {
                    sincronizarPeriodo(ym.atDay(1).toString(), ym.atEndOfMonth().toString(), idEmpresa, false);
                    sucesso++;
                    Thread.sleep(delayBootstrapEntreMesesMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("❌ [AUTO-BOOTSTRAP] Erro ao sincronizar futuro {} empresa={}: {}", ym, idEmpresa, e.getMessage());
                }
            }
            log.info("🏁 [AUTO-BOOTSTRAP] Concluído para empresa={} — {} períodos sincronizados", idEmpresa, sucesso);
        }, "auto-bootstrap-empresa-" + idEmpresa);
        t.setDaemon(true);
        t.start();
    }

    /**
     * Diariamente às 03h: sincroniza os 3 meses anteriores ao atual para todas as empresas.
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void jobSyncMesesRecentes() {
        LocalDate hoje = LocalDate.now();
        List<Integer> empresas = getEmpresasParaSync();
        log.info("⏰ [JOB] syncMesesRecentes — {} empresa(s)", empresas.size());
        for (Integer idEmpresa : empresas) {
            // 3 meses passados + mês atual + 6 meses futuros (cobre todos os pendentes)
            for (int i = -3; i <= 6; i++) {
                YearMonth ym = YearMonth.from(hoje).plusMonths(i);
                String inicio = ym.atDay(1).toString();
                String fim    = ym.atEndOfMonth().toString();
                log.info("⏰ [JOB] syncMesesRecentes {} empresa={}", ym, idEmpresa);
                try {
                    sincronizarPeriodo(inicio, fim, idEmpresa, i < 0);
                    Thread.sleep(8_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.error("❌ Erro no job syncMesesRecentes empresa={} period={}", idEmpresa, ym, e);
                }
            }
        }
    }

    /**
     * Domingo às 04h: sincroniza até 12 meses históricos para todas as empresas (pula meses já completos).
     */
    @Scheduled(cron = "0 0 4 ? * SUN")
    public void jobSyncHistorico() {
        LocalDate hoje = LocalDate.now();
        List<Integer> empresas = getEmpresasParaSync();
        log.info("⏰ [JOB] syncHistorico — {} empresa(s)", empresas.size());
        for (Integer idEmpresa : empresas) {
            for (int i = 4; i <= 24; i++) {
                YearMonth ym = YearMonth.from(hoje).minusMonths(i);
                String key   = buildKey(ym.toString(), idEmpresa);
                Optional<SyncStatus> statusOpt = syncStatusRepo.findByPeriodoEmpresaKey(key);

                if (statusOpt.isPresent()
                        && "completo".equals(statusOpt.get().getStatus())
                        && statusOpt.get().getTotalRegistros() != null
                        && statusOpt.get().getTotalRegistros() > 0) {
                    log.debug("⏭️  Período {} empresa={} já sincronizado, pulando histórico", ym, idEmpresa);
                    continue;
                }

                String inicio = ym.atDay(1).toString();
                String fim    = ym.atEndOfMonth().toString();
                log.info("⏰ [JOB] sync histórico {} empresa={}", ym, idEmpresa);
                try {
                    sincronizarPeriodo(inicio, fim, idEmpresa, false);
                    Thread.sleep(12_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.error("❌ Erro no job syncHistorico empresa={} period={}", idEmpresa, ym, e);
                }
            }
        }
    }

    // =========================================================================
    // HELPERS INTERNOS
    // =========================================================================

    /**
     * Retorna a lista de empresas a sincronizar.
     * Busca automaticamente todos os idEmpresa distintos com usuários ativos no banco.
     * Se a tabela estiver vazia ou houver erro (ex: banco offline), usa o defaultEmpresaId como fallback.
     */
    private List<Integer> getEmpresasParaSync() {
        try {
            List<Integer> ids = empresaUsuarioRepo.findAllActiveEmpresaIds();
            if (ids != null && !ids.isEmpty()) {
                log.debug("🏢 Empresas para sync: {}", ids);
                return ids;
            }
        } catch (Exception e) {
            log.warn("⚠️ Não foi possível listar empresas ativas — usando default={}: {}", defaultEmpresaId, e.getMessage());
        }
        return List.of(defaultEmpresaId);
    }

    // =========================================================================
    // API PÚBLICA — sincronização
    // =========================================================================

    /**
     * Sincroniza todas as movimentações de um período, buscando página por página
     * com delay entre as chamadas para respeitar o rate limit.
     *
     * @param dataInicio   "yyyy-MM-dd"
     * @param dataTermino  "yyyy-MM-dd"
     * @param idEmpresa    ID da empresa no Bom Controle
     * @param skipIfRecent se {@code true}, pula se o período já foi sincronizado há menos de 1 hora
     * @return mapa com resultado da operação
     */
    @Transactional
    public Map<String, Object> sincronizarPeriodo(
            String dataInicio, String dataTermino, Integer idEmpresa, boolean skipIfRecent) {

        String periodo = dataInicio.substring(0, 7); // yyyy-MM
        String key     = buildKey(periodo, idEmpresa);

        // Verifica se já está sincronizando
        Optional<SyncStatus> existente = syncStatusRepo.findByPeriodoEmpresaKey(key);
        if (existente.isPresent() && "sincronizando".equals(existente.get().getStatus())) {
            log.info("⚠️  Período {} já está sendo sincronizado", key);
            return Map.of("sucesso", false, "mensagem", "Período já em sincronização", "periodoKey", key);
        }

        // Pula se foi sincronizado recentemente
        if (skipIfRecent && existente.isPresent()
                && "completo".equals(existente.get().getStatus())
                && existente.get().getUltimaSync() != null) {
            long mins = Duration.between(existente.get().getUltimaSync(), LocalDateTime.now()).toMinutes();
            if (mins < 60) {
                log.debug("⏭️  Período {} sincronizado há {} min — pulando", key, mins);
                return Map.of("sucesso", true, "mensagem", "Sync recente, não necessário", "periodoKey", key);
            }
        }

        // Marca como "sincronizando"
        SyncStatus syncStatus = existente.orElse(
                SyncStatus.builder()
                        .periodo(periodo)
                        .idEmpresa(idEmpresa)
                        .periodoEmpresaKey(key)
                        .totalRegistros(0)
                        .build());
        syncStatus.setStatus("sincronizando");
        syncStatus.setMensagemErro(null);
        syncStatusRepo.save(syncStatus);

        log.info("🔄 Iniciando sync — key={} período={} a {}", key, dataInicio, dataTermino);

        try {
            // Aguarda permissão para chamar a API (evita colisão entre jobs e startup runner)
            boolean adquiriu = API_SYNC_LOCK.tryAcquire();
            if (!adquiriu) {
                log.info("⏳ Outro sync está chamando a API agora. Aguardando liberação para key={}", key);
                API_SYNC_LOCK.acquire(); // bloqueia até liberar
            }

            List<Map<String, Object>> movimentacoes;
            try {
                movimentacoes = buscarTodasPaginas(dataInicio, dataTermino, idEmpresa);
            } finally {
                API_SYNC_LOCK.release();
            }

            int salvos = salvarMovimentacoes(movimentacoes, idEmpresa);

            syncStatus.setStatus("completo");
            syncStatus.setUltimaSync(LocalDateTime.now());
            syncStatus.setTotalRegistros(salvos);
            syncStatus.setMensagemErro(null);
            syncStatusRepo.save(syncStatus);

            log.info("✅ Sync concluído — key={} salvos={}", key, salvos);
            return Map.of(
                    "sucesso", true,
                    "periodoKey", key,
                    "totalSalvo", salvos,
                    "mensagem", "Sincronização concluída com sucesso");

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("⚠️  Sync interrompido aguardando lock da API: {}", key);
            syncStatus.setStatus("erro");
            syncStatus.setMensagemErro("Interrompido aguardando lock");
            syncStatusRepo.save(syncStatus);
            return Map.of("sucesso", false, "mensagem", "Sync interrompido", "periodoKey", key);
        } catch (Exception e) {
            log.error("❌ Erro ao sincronizar {}", key, e);
            syncStatus.setStatus("erro");
            syncStatus.setMensagemErro(e.getMessage());
            syncStatusRepo.save(syncStatus);
            throw new RuntimeException("Erro ao sincronizar período: " + e.getMessage(), e);
        }
    }

    /**
     * Sincronização incremental: busca movimentações alteradas nas últimas 24 horas
     * (usa o campo "UltimaAlteracao" da API) e atualiza/insere no banco.
     */
    @Transactional
    public Map<String, Object> sincronizarIncremental(Integer idEmpresa) {
        String dataInicio = LocalDate.now().minusDays(1).toString();
        String dataTermino = LocalDate.now().toString();

        log.info("🔄 Sync incremental — empresa={} de {} a {}", idEmpresa, dataInicio, dataTermino);
        try {
            List<Map<String, Object>> movimentacoes =
                    buscarTodasPaginasTipoData(dataInicio, dataTermino, idEmpresa, "UltimaAlteracao");
            int salvos = salvarMovimentacoes(movimentacoes, idEmpresa);

            log.info("✅ Sync incremental concluído — empresa={} atualizados={}", idEmpresa, salvos);
            return Map.of("sucesso", true, "totalAtualizado", salvos,
                    "mensagem", "Sincronização incremental concluída");
        } catch (Exception e) {
            log.error("❌ Erro no sync incremental", e);
            throw new RuntimeException("Erro no sync incremental: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // API PÚBLICA — leitura do banco local
    // =========================================================================

    /**
     * Verifica se o período solicitado possui dados sincronizados no banco.
     */
    public boolean periodoEstaSync(LocalDate dataInicio, LocalDate dataTermino, Integer idEmpresa) {
        return movimentacaoRepo.existsByIdEmpresaAndDataVencimentoBetween(idEmpresa, dataInicio, dataTermino);
    }

    /**
     * Busca movimentações do banco local no mesmo formato esperado pelo controller.
     * orderBy: "data" | "valor" | "status" | "tipo" (campo da entidade: dataVencimento, valor, statusPagamento, debito)
     * orderDirection: "asc" | "desc"
     */
    public Map<String, Object> buscarMovimentacoesDoDb(
            LocalDate dataInicio, LocalDate dataTermino,
            String tipoData,
            Integer idEmpresa,
            Boolean debito,           // null = todos; true = despesas; false = receitas
            String statusPagamento,   // null | "pendente" | "pago"
            String orderBy,          // null = data; "data" | "valor" | "status" | "tipo"
            String orderDirection,   // null = desc; "asc" | "desc"
            int itensPorPagina,
            int numeroDaPagina) {

        String sortField = "dataVencimento";
        if (orderBy != null && !orderBy.isBlank()) {
            switch (orderBy.trim().toLowerCase()) {
                case "valor" -> sortField = "valor";
                case "status" -> sortField = "statusPagamento";
                case "tipo" -> sortField = "debito";
                case "data" -> sortField = "dataVencimento";
                default -> { }
            }
        }
        Sort.Direction direction = "asc".equalsIgnoreCase(orderDirection != null ? orderDirection.trim() : "") 
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort sort = Sort.by(direction, sortField);
        PageRequest pageable = PageRequest.of(numeroDaPagina - 1, itensPorPagina, sort);

        Page<MovimentacaoFinanceira> page;

        boolean useCompetencia = "DataCompetencia".equalsIgnoreCase(tipoData);

        if (debito != null && statusPagamento != null) {
            // debito + statusPagamento — só suportado por vencimento para simplicidade
            page = movimentacaoRepo
                    .findByIdEmpresaAndDebitoAndStatusPagamentoAndDataVencimentoBetween(
                            idEmpresa, debito, statusPagamento, dataInicio, dataTermino, pageable);
        } else if (debito != null) {
            if (useCompetencia) {
                page = movimentacaoRepo
                        .findByIdEmpresaAndDebitoAndDataCompetenciaBetween(
                                idEmpresa, debito, dataInicio, dataTermino, pageable);
            } else {
                page = movimentacaoRepo
                        .findByIdEmpresaAndDebitoAndDataVencimentoBetween(
                                idEmpresa, debito, dataInicio, dataTermino, pageable);
            }
        } else {
            if (useCompetencia) {
                page = movimentacaoRepo
                        .findByIdEmpresaAndDataCompetenciaBetween(
                                idEmpresa, dataInicio, dataTermino, pageable);
            } else {
                page = movimentacaoRepo
                        .findByIdEmpresaAndDataVencimentoBetween(
                                idEmpresa, dataInicio, dataTermino, pageable);
            }
        }

        BigDecimal somaReceitas = movimentacaoRepo.sumValorByEmpresaAndDebitoAndVencimento(
                idEmpresa, false, dataInicio, dataTermino);
        BigDecimal somaDespesas = movimentacaoRepo.sumValorByEmpresaAndDebitoAndVencimento(
                idEmpresa, true, dataInicio, dataTermino);

        List<Map<String, Object>> itens = page.getContent().stream()
                .map(this::entityToMap)
                .collect(Collectors.toList());

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("movimentacoes", itens);
        resultado.put("total", page.getTotalElements());
        resultado.put("totalReceitas", somaReceitas != null ? somaReceitas.doubleValue() : 0.0);
        resultado.put("totalDespesas", somaDespesas != null ? somaDespesas.doubleValue() : 0.0);
        resultado.put("saldoLiquido", (somaReceitas != null ? somaReceitas : BigDecimal.ZERO)
                .subtract(somaDespesas != null ? somaDespesas : BigDecimal.ZERO).doubleValue());
        resultado.put("dataInicio", dataInicio.toString());
        resultado.put("dataTermino", dataTermino.toString());
        resultado.put("tipoData", tipoData != null ? tipoData : "DataVencimento");
        resultado.put("endpointUsado", "banco-local");
        resultado.put("fonteDados", "banco-local");
        resultado.put("usandoCache", true);
        resultado.put("atualizadoEm", LocalDateTime.now().toString());
        resultado.put("paginacao", Map.of(
                "itensPorPagina", itensPorPagina,
                "numeroDaPagina", numeroDaPagina,
                "totalItens", page.getTotalElements()
        ));
        return resultado;
    }

    /**
     * Gera DFC usando os dados do banco local (sem chamar a API).
     * Busca todas as movimentações do período e delega o cálculo ao BomControleService.
     */
    public DfcResponseDTO gerarDFCDoDb(LocalDate dataInicio, LocalDate dataTermino, Integer idEmpresa) {
        long inicio = System.currentTimeMillis();

        List<MovimentacaoFinanceira> entidades =
                movimentacaoRepo.findAllByIdEmpresaAndDataVencimentoBetween(idEmpresa, dataInicio, dataTermino);

        List<Map<String, Object>> movimentacoes = entidades.stream()
                .map(this::entityToMap)
                .collect(Collectors.toList());

        log.info("📦 DFC do banco: {} registros para empresa={} de {} a {}",
                movimentacoes.size(), idEmpresa, dataInicio, dataTermino);

        long tempoMs = System.currentTimeMillis() - inicio;

        return bomControleService.montarDfcResponse(
                movimentacoes,
                dataInicio,
                dataTermino,
                false,          // fallbackAtivo = false — dados reais do banco
                null,           // fallbackMetadata
                1L,             // paginasProcessadas
                1L,             // paginasEstimadas
                movimentacoes.size(),
                tempoMs)
                .toBuilder()
                .fonteDados("banco-local")
                .usandoCache(true)
                .atualizadoEm(LocalDateTime.now().toString())
                .build();
    }

    /**
     * Gera ResumoFinanceiroDTO a partir dos dados do banco local.
     */
    public ResumoFinanceiroDTO gerarResumoDoDb(LocalDate dataInicio, LocalDate dataTermino, Integer idEmpresa) {
        List<MovimentacaoFinanceira> all =
                movimentacaoRepo.findAllByIdEmpresaAndDataVencimentoBetween(idEmpresa, dataInicio, dataTermino);

        ResumoFinanceiroDTO.BlocoResumo blocoReceber = calcularBlocoResumo(
                all.stream().filter(m -> Boolean.FALSE.equals(m.getDebito())).collect(Collectors.toList()));
        ResumoFinanceiroDTO.BlocoResumo blocoPagar = calcularBlocoResumo(
                all.stream().filter(m -> Boolean.TRUE.equals(m.getDebito())).collect(Collectors.toList()));

        double saldoDisponivel = blocoReceber.getTotalLiquidado() - blocoPagar.getTotalLiquidado();
        double saldoProjetado  = blocoReceber.getTotalGeral()     - blocoPagar.getTotalGeral();

        log.info("📦 Resumo do banco: {} registros para empresa={} de {} a {}",
                all.size(), idEmpresa, dataInicio, dataTermino);

        return ResumoFinanceiroDTO.builder()
                .periodo(ResumoFinanceiroDTO.PeriodoResumo.builder()
                        .dataInicio(dataInicio.toString())
                        .dataTermino(dataTermino.toString())
                        .build())
                .contasReceber(blocoReceber)
                .contasPagar(blocoPagar)
                .saldoDisponivel(saldoDisponivel)
                .saldoProjetado(saldoProjetado)
                .totalMovimentacoes(all.size())
                .usandoCache(true)
                .fonteDados("banco-local")
                .atualizadoEm(LocalDateTime.now().toString())
                .fallbackAtivo(false)
                .build();
    }

    private ResumoFinanceiroDTO.BlocoResumo calcularBlocoResumo(List<MovimentacaoFinanceira> movs) {
        double totalGeral = movs.stream()
                .mapToDouble(m -> m.getValor() != null ? m.getValor().doubleValue() : 0.0)
                .sum();
        List<MovimentacaoFinanceira> pagos = movs.stream()
                .filter(m -> "pago".equalsIgnoreCase(m.getStatusPagamento()))
                .collect(Collectors.toList());
        List<MovimentacaoFinanceira> pendentes = movs.stream()
                .filter(m -> !"pago".equalsIgnoreCase(m.getStatusPagamento()))
                .collect(Collectors.toList());
        double totalLiquidado = pagos.stream()
                .mapToDouble(m -> m.getValor() != null ? m.getValor().doubleValue() : 0.0).sum();
        double totalPendente  = pendentes.stream()
                .mapToDouble(m -> m.getValor() != null ? m.getValor().doubleValue() : 0.0).sum();
        return ResumoFinanceiroDTO.BlocoResumo.builder()
                .totalGeral(totalGeral)
                .totalLiquidado(totalLiquidado)
                .totalPendente(totalPendente)
                .totalContas(movs.size())
                .contasPendentes(pendentes.size())
                .build();
    }

    /**
     * Retorna o status de sincronização de todos os períodos de uma empresa.
     */
    public Map<String, Object> statusSync(Integer idEmpresa) {
        List<SyncStatus> statuses = syncStatusRepo.findByIdEmpresaOrderByPeriodoDesc(idEmpresa);
        long totalNoBanco = movimentacaoRepo.countByIdEmpresaAndDataVencimentoBetween(
                idEmpresa,
                LocalDate.now().minusYears(2),
                LocalDate.now().plusYears(1));

        List<Map<String, Object>> periodos = statuses.stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("periodo",         s.getPeriodo());
                    m.put("status",          s.getStatus());
                    m.put("ultimaSync",      s.getUltimaSync() != null ? s.getUltimaSync().toString() : null);
                    m.put("totalRegistros",  s.getTotalRegistros() != null ? s.getTotalRegistros() : 0);
                    m.put("mensagemErro",    s.getMensagemErro());
                    return m;
                })
                .collect(Collectors.toList());

        return Map.of(
                "cacheAtivo",              true,
                "totalMovimentacoesNoBanco", totalNoBanco,
                "totalPeriodosSincronizados", statuses.stream().filter(s -> "completo".equals(s.getStatus())).count(),
                "periodos",                periodos,
                "mensagem", "Dados sendo servidos do banco local PostgreSQL"
        );
    }

    // =========================================================================
    // INTERNOS — busca paginada da API
    // =========================================================================

    private List<Map<String, Object>> buscarTodasPaginas(
            String dataInicio, String dataTermino, Integer idEmpresa) throws InterruptedException {
        return buscarTodasPaginasTipoData(dataInicio, dataTermino, idEmpresa, "Vencimento");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buscarTodasPaginasTipoData(
            String dataInicio, String dataTermino, Integer idEmpresa, String tipoData)
            throws InterruptedException {

        List<Map<String, Object>> todas   = new ArrayList<>();
        int pagina      = 1;
        int totalPaginas = 1;

        while (pagina <= totalPaginas) {
            final int paginaAtual = pagina;
            log.debug("📄 Buscando página {}/{} — {} a {} empresa={}", pagina, totalPaginas, dataInicio, dataTermino, idEmpresa);

            try {
                Map<String, Object> response = webClient.get()
                        .uri(uri -> {
                            uri.path("/integracao/Financeiro/Pesquisar");
                            uri.queryParam("dataInicio",  dataInicio + " 00:00:00");
                            uri.queryParam("dataTermino", dataTermino + " 23:59:59");
                            uri.queryParam("tipoData",    tipoData);
                            if (idEmpresa != null) uri.queryParam("idsEmpresa", idEmpresa);
                            uri.queryParam("paginacao.itensPorPagina",  ITENS_POR_PAGINA);
                            uri.queryParam("paginacao.numeroDaPagina",  paginaAtual);
                            return uri.build();
                        })
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();

                if (response == null) break;

                List<Map<String, Object>> itens = (List<Map<String, Object>>) response.get("Itens");
                if (itens != null) todas.addAll(itens);

                Object totalItensObj = response.get("TotalItens");
                if (totalItensObj instanceof Number) {
                    int totalItens = ((Number) totalItensObj).intValue();
                    totalPaginas = (int) Math.ceil((double) totalItens / ITENS_POR_PAGINA);
                }

                pagina++;
                if (pagina <= totalPaginas) {
                    Thread.sleep(DELAY_ENTRE_PAGINAS_MS);
                }

            } catch (WebClientResponseException.TooManyRequests e) {
                log.warn("⚠️  Rate limit atingido na página {}. Aguardando {}s...", pagina, ESPERA_RATE_LIMIT_MS / 1000);
                Thread.sleep(ESPERA_RATE_LIMIT_MS);
                // Retenta a mesma página

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;

            } catch (Exception e) {
                log.error("❌ Erro ao buscar página {} da API: {}", pagina, e.getMessage());
                throw e;
            }
        }

        log.info("📦 Total buscado da API: {} itens em {} páginas", todas.size(), totalPaginas);
        return todas;
    }

    // =========================================================================
    // INTERNOS — persistência
    // =========================================================================

    private int salvarMovimentacoes(List<Map<String, Object>> itens, Integer idEmpresa) {
        int count = 0;
        for (Map<String, Object> item : itens) {
            try {
                MovimentacaoFinanceira entity = mapToEntity(item, idEmpresa);
                movimentacaoRepo.save(entity);
                count++;
            } catch (Exception e) {
                log.warn("⚠️  Erro ao salvar movimentação {}: {}",
                        item.get("IdMovimentacaoFinanceiraParcela"), e.getMessage());
            }
        }
        return count;
    }

    private MovimentacaoFinanceira mapToEntity(Map<String, Object> item, Integer idEmpresa) {
        LocalDate dataQuitacao = parseDate(item.get("DataQuitacao"));

        return MovimentacaoFinanceira.builder()
                .idBomControle     (String.valueOf(item.get("IdMovimentacaoFinanceiraParcela")))
                .idEmpresa         (idEmpresa != null ? idEmpresa : parseInt(item.get("IdEmpresa")))
                .debito            ((Boolean) item.get("Debito"))
                .dataVencimento    (parseDate(item.get("DataVencimento")))
                .dataCompetencia   (parseDate(item.get("DataCompetencia")))
                .dataQuitacao      (dataQuitacao)
                .dataConciliacao   (parseDate(item.get("DataConciliacao")))
                .valor             (parseBigDecimal(item.get("Valor")))
                .formaPagamento    (parseInt(item.get("FormaPagamento")))
                .nomeFormaPagamento((String) item.get("NomeFormaPagamento"))
                .tipoMovimentacao  (parseInt(item.get("TipoMovimentacao")))
                .nomeTipoMovimentacao((String) item.get("NomeTipoMovimentacao"))
                .nome              (truncate((String) item.get("Nome"), 500))
                .observacao        ((String) item.get("Observacao"))
                .numeroParcela     (parseInt(item.get("NumeroParcela")))
                .quantidadeParcela (parseInt(item.get("QuantidadeParcela")))
                .idCategoriaFinanceira(parseInt(item.get("IdCategoriaFinanceira")))
                .nomeCategoriaFinanceira((String) item.get("NomeCategoriaFinanceira"))
                .idContaFinanceira (parseInt(item.get("IdContaFinanceira")))
                .nomeContaFinanceira((String) item.get("NomeContaFinanceira"))
                .nomeEmpresa       ((String) item.get("NomeEmpresa"))
                .idCliente         (parseInt(item.get("IdCliente")))
                .idFornecedor      (parseInt(item.get("IdFornecedor")))
                .nomeClienteFornecedor(truncate((String) item.get("NomeClienteFornecedor"), 500))
                .statusPagamento   (dataQuitacao != null ? "pago" : "pendente")
                .dadosRaw          (toJson(item))
                .sincronizadoEm    (LocalDateTime.now())
                .build();
    }

    // =========================================================================
    // INTERNOS — conversão entity → Map para o controller
    // =========================================================================

    private Map<String, Object> entityToMap(MovimentacaoFinanceira m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("IdMovimentacaoFinanceiraParcela", m.getIdBomControle());
        map.put("Debito",                    m.getDebito());
        map.put("DataVencimento",            m.getDataVencimento() != null ? m.getDataVencimento().toString() : null);
        map.put("DataCompetencia",           m.getDataCompetencia() != null ? m.getDataCompetencia().toString() : null);
        map.put("DataQuitacao",              m.getDataQuitacao() != null ? m.getDataQuitacao().toString() : null);
        map.put("DataConciliacao",           m.getDataConciliacao() != null ? m.getDataConciliacao().toString() : null);
        map.put("Valor",                     m.getValor() != null ? m.getValor().doubleValue() : 0.0);
        map.put("FormaPagamento",            m.getFormaPagamento());
        map.put("NomeFormaPagamento",        m.getNomeFormaPagamento());
        map.put("TipoMovimentacao",          m.getTipoMovimentacao());
        map.put("NomeTipoMovimentacao",      m.getNomeTipoMovimentacao());
        map.put("Nome",                      m.getNome());
        map.put("Observacao",                m.getObservacao());
        map.put("NumeroParcela",             m.getNumeroParcela());
        map.put("QuantidadeParcela",         m.getQuantidadeParcela());
        map.put("IdCategoriaFinanceira",     m.getIdCategoriaFinanceira());
        map.put("NomeCategoriaFinanceira",   m.getNomeCategoriaFinanceira());
        map.put("IdContaFinanceira",         m.getIdContaFinanceira());
        map.put("NomeContaFinanceira",       m.getNomeContaFinanceira());
        map.put("NomeEmpresa",               m.getNomeEmpresa());
        map.put("IdCliente",                 m.getIdCliente());
        map.put("IdFornecedor",              m.getIdFornecedor());
        map.put("NomeClienteFornecedor",     m.getNomeClienteFornecedor());
        return map;
    }

    // =========================================================================
    // UTILITÁRIOS
    // =========================================================================

    private LocalDate parseDate(Object value) {
        if (value == null) return null;
        try {
            String s = value.toString();
            if (s.contains("T")) s = s.substring(0, s.indexOf('T'));
            if (s.length() >= 10)  return LocalDate.parse(s.substring(0, 10));
        } catch (Exception ignored) {}
        return null;
    }

    private BigDecimal parseBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        try { return new BigDecimal(value.toString()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private Integer parseInt(Object value) {
        if (value == null) return null;
        try { return ((Number) value).intValue(); } catch (Exception e) { return null; }
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private String toJson(Map<String, Object> item) {
        try { return objectMapper.writeValueAsString(item); } catch (JsonProcessingException e) { return null; }
    }

    private String buildKey(String periodo, Integer idEmpresa) {
        return periodo + "_" + idEmpresa;
    }
}
