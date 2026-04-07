package com.finnza.service;

import com.finnza.domain.entity.SyncStatus;
import com.finnza.repository.SyncStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Isola leituras/escritas em {@code bc_sync_status} em transações curtas (REQUIRES_NEW)
 * e serializa por {@code periodo_empresa_key}, evitando corrida entre o startup runner,
 * jobs e {@code AUTO-BOOTSTRAP} ao inserir a mesma linha no H2/Postgres.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BomControleSyncStatusHelper {

    private final SyncStatusRepository syncStatusRepo;
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();

    @Lazy
    @Autowired
    private BomControleSyncStatusHelper self;

    public BeginSyncResult prepareBegin(String periodo, Integer idEmpresa, String key, boolean skipIfRecent) {
        Object lock = keyLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            return self.beginOrSkipInNewTransaction(periodo, idEmpresa, key, skipIfRecent);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BeginSyncResult beginOrSkipInNewTransaction(String periodo, Integer idEmpresa, String key, boolean skipIfRecent) {
        Optional<SyncStatus> existente = syncStatusRepo.findByPeriodoEmpresaKey(key);
        if (existente.isPresent() && "sincronizando".equals(existente.get().getStatus())) {
            log.info("⚠️  Período {} já está sendo sincronizado", key);
            return BeginSyncResult.abort(Map.of(
                    "sucesso", false,
                    "mensagem", "Período já em sincronização",
                    "periodoKey", key));
        }
        if (skipIfRecent && existente.isPresent()
                && "completo".equals(existente.get().getStatus())
                && existente.get().getUltimaSync() != null) {
            long mins = Duration.between(existente.get().getUltimaSync(), LocalDateTime.now()).toMinutes();
            if (mins < 60) {
                log.debug("⏭️  Período {} sincronizado há {} min — pulando", key, mins);
                return BeginSyncResult.abort(Map.of(
                        "sucesso", true,
                        "mensagem", "Sync recente, não necessário",
                        "periodoKey", key));
            }
        }
        SyncStatus syncStatus = existente.orElseGet(() -> SyncStatus.builder()
                .periodo(periodo)
                .idEmpresa(idEmpresa)
                .periodoEmpresaKey(key)
                .totalRegistros(0)
                .build());
        syncStatus.setStatus("sincronizando");
        syncStatus.setMensagemErro(null);
        SyncStatus saved = syncStatusRepo.save(syncStatus);
        return BeginSyncResult.ok(saved.getId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markComplete(Long syncStatusId, int salvos) {
        SyncStatus syncStatus = syncStatusRepo.findById(syncStatusId)
                .orElseThrow(() -> new IllegalStateException("bc_sync_status id=" + syncStatusId + " não encontrado"));
        syncStatus.setStatus("completo");
        syncStatus.setUltimaSync(LocalDateTime.now());
        syncStatus.setTotalRegistros(salvos);
        syncStatus.setMensagemErro(null);
        syncStatusRepo.save(syncStatus);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markError(Long syncStatusId, String mensagemErro) {
        syncStatusRepo.findById(syncStatusId).ifPresent(syncStatus -> {
            syncStatus.setStatus("erro");
            syncStatus.setMensagemErro(mensagemErro);
            syncStatusRepo.save(syncStatus);
        });
    }

    public static final class BeginSyncResult {
        public final Map<String, Object> earlyResponse;
        public final Long syncStatusId;

        private BeginSyncResult(Map<String, Object> earlyResponse, Long syncStatusId) {
            this.earlyResponse = earlyResponse;
            this.syncStatusId = syncStatusId;
        }

        static BeginSyncResult abort(Map<String, Object> response) {
            return new BeginSyncResult(response, null);
        }

        static BeginSyncResult ok(Long id) {
            return new BeginSyncResult(null, id);
        }

        public boolean isAbort() {
            return earlyResponse != null;
        }
    }
}
