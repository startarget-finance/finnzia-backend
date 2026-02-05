package com.finnza.config;

import com.finnza.service.BomControleRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Agendador para limpeza periódica do cache do Rate Limiter
 */
@Slf4j
@Component
public class RateLimiterScheduler {
    
    @Autowired
    private BomControleRateLimiter rateLimiter;
    
    /**
     * Limpa cache expirado a cada 10 minutos
     */
    @Scheduled(fixedRate = 10 * 60 * 1000) // 10 minutos
    public void cleanupExpiredCache() {
        try {
            rateLimiter.cleanupExpiredCache();
            log.debug("🧹 Limpeza automática de cache expirado concluída");
        } catch (Exception e) {
            log.error("Erro ao limpar cache expirado", e);
        }
    }
}
