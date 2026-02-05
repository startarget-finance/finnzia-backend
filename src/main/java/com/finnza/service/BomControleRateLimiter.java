package com.finnza.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Gerenciador profissional de Rate Limiting para API do Bom Controle
 * Implementa cache multi-camadas, retry com backoff exponencial e throttling
 */
@Slf4j
@Component
public class BomControleRateLimiter {
    
    // Cache de respostas com TTL
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    
    // Semáforo para limitar requisições simultâneas
    private final Semaphore requestSemaphore;
    
    // Fila de requisições para evitar sobrecarga
    private final BlockingQueue<Runnable> requestQueue;
    
    // Executor para processar requisições da fila
    private final ExecutorService executorService;
    
    // Lock para sincronização
    private final ReentrantLock lock = new ReentrantLock();
    
    // Estatísticas
    private volatile long totalRequests = 0;
    private volatile long cachedRequests = 0;
    private volatile long rateLimitedRequests = 0;
    private volatile long lastRateLimitTime = 0;
    
    // Configurações
    private static final int MAX_CONCURRENT_REQUESTS = 3; // Máximo de requisições simultâneas
    private static final int MAX_QUEUE_SIZE = 50; // Tamanho máximo da fila
    private static final long DEFAULT_CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutos
    private static final long RATE_LIMIT_COOLDOWN_MS = 60 * 1000; // 1 minuto após rate limit
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_RETRY_DELAY_MS = 1000; // 1 segundo
    
    public BomControleRateLimiter() {
        this.requestSemaphore = new Semaphore(MAX_CONCURRENT_REQUESTS, true); // Fair semaphore
        this.requestQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
        this.executorService = Executors.newFixedThreadPool(MAX_CONCURRENT_REQUESTS, r -> {
            Thread t = new Thread(r, "BomControle-RateLimiter");
            t.setDaemon(true);
            return t;
        });
        
        // Iniciar processamento da fila
        startQueueProcessor();
        
        log.info("✅ BomControleRateLimiter inicializado - Max concurrent: {}, Queue size: {}", 
                MAX_CONCURRENT_REQUESTS, MAX_QUEUE_SIZE);
    }
    
    /**
     * Executa uma requisição com rate limiting, cache e retry automático
     */
    public <T> T executeWithRateLimit(String cacheKey, long cacheTtlMs, 
                                     java.util.function.Supplier<T> requestSupplier,
                                     java.util.function.Supplier<T> fallbackSupplier) {
        // 1. Verificar cache primeiro
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired(cacheTtlMs)) {
            cachedRequests++;
            log.debug("📦 Cache hit para: {}", cacheKey);
            return (T) cached.value;
        }
        
        // 2. Verificar se estamos em cooldown após rate limit
        long now = System.currentTimeMillis();
        if (lastRateLimitTime > 0 && (now - lastRateLimitTime) < RATE_LIMIT_COOLDOWN_MS) {
            long waitTime = RATE_LIMIT_COOLDOWN_MS - (now - lastRateLimitTime);
            log.warn("⏳ Em cooldown após rate limit. Aguardando {}ms...", waitTime);
            
            // Retornar cache mesmo que expirado se disponível
            if (cached != null) {
                log.info("📦 Retornando cache expirado durante cooldown: {}", cacheKey);
                return (T) cached.value;
            }
            
            // Ou usar fallback
            if (fallbackSupplier != null) {
                return fallbackSupplier.get();
            }
            
            throw new RateLimitException("Rate limit ativo. Tente novamente em alguns segundos.");
        }
        
        // 3. Executar com retry e backoff exponencial
        return executeWithRetry(cacheKey, cacheTtlMs, requestSupplier, fallbackSupplier, 0);
    }
    
    /**
     * Executa requisição com retry e backoff exponencial
     */
    private <T> T executeWithRetry(String cacheKey, long cacheTtlMs,
                                   java.util.function.Supplier<T> requestSupplier,
                                   java.util.function.Supplier<T> fallbackSupplier,
                                   int attempt) {
        totalRequests++;
        
        try {
            // Adquirir permissão do semáforo (com timeout)
            if (!requestSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                log.warn("⏳ Timeout ao adquirir semáforo. Retornando cache ou fallback.");
                CacheEntry cached = cache.get(cacheKey);
                if (cached != null) {
                    return (T) cached.value;
                }
                if (fallbackSupplier != null) {
                    return fallbackSupplier.get();
                }
                throw new RateLimitException("Sistema ocupado. Tente novamente.");
            }
            
            try {
                // Executar requisição
                T result = requestSupplier.get();
                
                // Armazenar no cache
                if (result != null) {
                    cache.put(cacheKey, new CacheEntry(result, System.currentTimeMillis()));
                }
                
                return result;
                
            } catch (RateLimitException e) {
                rateLimitedRequests++;
                lastRateLimitTime = System.currentTimeMillis();
                
                // Retry com backoff exponencial
                if (attempt < MAX_RETRIES) {
                    long delay = INITIAL_RETRY_DELAY_MS * (1L << attempt); // Exponential backoff
                    log.warn("🔄 Rate limit detectado (tentativa {}/{}). Retry em {}ms...", 
                            attempt + 1, MAX_RETRIES, delay);
                    
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrompido durante retry", ie);
                    }
                    
                    return executeWithRetry(cacheKey, cacheTtlMs, requestSupplier, 
                                          fallbackSupplier, attempt + 1);
                } else {
                    log.error("❌ Máximo de tentativas atingido. Retornando cache ou fallback.");
                    CacheEntry cached = cache.get(cacheKey);
                    if (cached != null) {
                        return (T) cached.value;
                    }
                    if (fallbackSupplier != null) {
                        return fallbackSupplier.get();
                    }
                    throw e;
                }
            } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
                int statusCode = e.getStatusCode() != null ? e.getStatusCode().value() : 0;
                
                // Erro 401 Unauthorized - API key inválida ou não configurada
                if (statusCode == 401) {
                    log.error("❌ ERRO 401 UNAUTHORIZED: API Key do Bom Controle está inválida, expirada ou não configurada!");
                    log.error("   Verifique a variável de ambiente BOMCONTROLE_API_KEY");
                    log.error("   Resposta da API: {}", e.getResponseBodyAsString());
                    
                    // Não fazer retry para 401 - não adianta tentar novamente
                    // Retornar fallback ou lançar exceção clara
                    if (fallbackSupplier != null) {
                        log.warn("   Retornando fallback devido a erro de autenticação");
                        return fallbackSupplier.get();
                    }
                    throw new RuntimeException("API Key do Bom Controle inválida ou não configurada. Verifique BOMCONTROLE_API_KEY.", e);
                }
                
                // Verificar se é rate limit (429)
                if (statusCode == 429) {
                    rateLimitedRequests++;
                    lastRateLimitTime = System.currentTimeMillis();
                    
                    // Retry com backoff exponencial
                    if (attempt < MAX_RETRIES) {
                        long delay = INITIAL_RETRY_DELAY_MS * (1L << attempt);
                        log.warn("🔄 Rate limit 429 detectado (tentativa {}/{}). Retry em {}ms...", 
                                attempt + 1, MAX_RETRIES, delay);
                        
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrompido durante retry", ie);
                        }
                        
                        return executeWithRetry(cacheKey, cacheTtlMs, requestSupplier, 
                                              fallbackSupplier, attempt + 1);
                    } else {
                        log.error("❌ Máximo de tentativas atingido após 429. Retornando cache ou fallback.");
                        CacheEntry cached = cache.get(cacheKey);
                        if (cached != null) {
                            return (T) cached.value;
                        }
                        if (fallbackSupplier != null) {
                            return fallbackSupplier.get();
                        }
                        throw new RateLimitException("Rate limit atingido após " + MAX_RETRIES + " tentativas");
                    }
                } else {
                    // Outro erro HTTP - propagar
                    log.error("❌ Erro HTTP {} na requisição: {}", statusCode, e.getMessage());
                    throw e;
                }
                
            } finally {
                requestSemaphore.release();
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrompido", e);
        } catch (Exception e) {
            // Outros erros - retornar cache ou fallback
            log.warn("⚠️ Erro na requisição: {}. Tentando cache ou fallback.", e.getMessage());
            CacheEntry cached = cache.get(cacheKey);
            if (cached != null) {
                return (T) cached.value;
            }
            if (fallbackSupplier != null) {
                return fallbackSupplier.get();
            }
            throw new RuntimeException("Erro na requisição: " + e.getMessage(), e);
        }
    }
    
    /**
     * Inicia processador da fila de requisições
     */
    private void startQueueProcessor() {
        executorService.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Runnable task = requestQueue.take();
                    executorService.submit(task);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }
    
    /**
     * Limpa cache expirado periodicamente
     */
    public void cleanupExpiredCache() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            cache.entrySet().removeIf(entry -> entry.getValue().isExpired(DEFAULT_CACHE_TTL_MS));
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Limpa todo o cache
     */
    public void clearCache() {
        lock.lock();
        try {
            cache.clear();
            log.info("🗑️ Cache limpo");
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Obtém estatísticas
     */
    public Map<String, Object> getStats() {
        return Map.of(
            "totalRequests", totalRequests,
            "cachedRequests", cachedRequests,
            "rateLimitedRequests", rateLimitedRequests,
            "cacheSize", cache.size(),
            "availablePermits", requestSemaphore.availablePermits(),
            "queueSize", requestQueue.size(),
            "lastRateLimitTime", lastRateLimitTime
        );
    }
    
    /**
     * Entrada de cache
     */
    private static class CacheEntry {
        final Object value;
        final long timestamp;
        
        CacheEntry(Object value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
        
        boolean isExpired(long ttlMs) {
            return (System.currentTimeMillis() - timestamp) > ttlMs;
        }
    }
    
    /**
     * Exceção para rate limit
     */
    public static class RateLimitException extends RuntimeException {
        public RateLimitException(String message) {
            super(message);
        }
    }
}
