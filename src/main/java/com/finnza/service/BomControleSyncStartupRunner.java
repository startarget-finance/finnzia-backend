package com.finnza.service;

import com.finnza.repository.EmpresaUsuarioRepository;
import com.finnza.repository.MovimentacaoFinanceiraRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Dispara a sincronização histórica do Bom Controle automaticamente na inicialização.
 *
 * <p>Lógica:
 * <ol>
 *   <li>Conta quantos registros existem no banco para a empresa configurada.</li>
 *   <li>Se o banco estiver vazio (ou com poucos registros), inicia o sync histórico em background,
 *       mês a mês, com delay entre cada mês para respeitar o rate limit.</li>
 *   <li>Se o banco já tiver dados, não faz nada — os jobs agendados ({@link BomControleSyncService})
 *       cuidam das atualizações incrementais.</li>
 * </ol>
 *
 * <p>Pode ser desabilitado via: {@code bomcontrole.sync.startup.enabled=false}
 */
@Slf4j
@Component
public class BomControleSyncStartupRunner {

    @Autowired
    private BomControleSyncService syncService;

    @Autowired
    private MovimentacaoFinanceiraRepository movimentacaoRepo;

    @Autowired
    private EmpresaUsuarioRepository empresaUsuarioRepo;

    @Value("${bomcontrole.default.empresa.id:1}")
    private Integer defaultEmpresaId;

    /** Quantos meses para trás buscar no sync inicial */
    @Value("${bomcontrole.sync.startup.meses:18}")
    private int mesesHistorico;

    /** Mínimo de registros no banco para considerar que já está populado */
    @Value("${bomcontrole.sync.startup.min-registros:50}")
    private long minRegistrosParaConsiderarPopulado;

    /** Permite desabilitar o sync automático de startup */
    @Value("${bomcontrole.sync.startup.enabled:true}")
    private boolean enabled;

    /** Delay entre a busca de cada mês (ms) — não altere abaixo de 5 000 */
    @Value("${bomcontrole.sync.startup.delay-entre-meses-ms:10000}")
    private long delayEntreMesesMs;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            log.info("⏭️  Sync de startup desabilitado (bomcontrole.sync.startup.enabled=false)");
            return;
        }

        // Descobre todas as empresas ativas no sistema
        List<Integer> todasEmpresas = getEmpresasAtivas();
        log.info("🏢 Empresas ativas encontradas para sync: {}", todasEmpresas);

        // Filtra apenas as que ainda não têm dados suficientes no banco
        List<Integer> empresasSemDados = new ArrayList<>();
        for (Integer idEmpresa : todasEmpresas) {
            long total = contarRegistrosNoBanco(idEmpresa);
            if (total < minRegistrosParaConsiderarPopulado) {
                log.info("⚠️  Empresa={} tem {} registros (mínimo: {}) — será sincronizada",
                        idEmpresa, total, minRegistrosParaConsiderarPopulado);
                empresasSemDados.add(idEmpresa);
            } else {
                log.info("✅ Empresa={} já possui {} movimentações — sync não necessário", idEmpresa, total);
            }
        }

        if (empresasSemDados.isEmpty()) {
            log.info("✅ Todas as empresas já possuem dados — sync de startup não necessário.");
            return;
        }

        List<String[]> periodos = montarPeriodos();

        // Dispara em thread separada para não travar o startup da aplicação
        Thread thread = new Thread(() -> {
            for (Integer idEmpresa : empresasSemDados) {
                executarSyncHistorico(periodos, idEmpresa);
            }
        }, "sync-startup-historico");
        thread.setDaemon(true);
        thread.start();
    }

    // =========================================================================
    // PRIVADOS
    // =========================================================================

    /**
     * Retorna todos os idEmpresa distintos com usuários ativos.
     * Usa defaultEmpresaId como fallback se a tabela estiver vazia ou houver erro.
     */
    private List<Integer> getEmpresasAtivas() {
        try {
            List<Integer> ids = empresaUsuarioRepo.findAllActiveEmpresaIds();
            if (ids != null && !ids.isEmpty()) {
                return ids;
            }
        } catch (Exception e) {
            log.warn("⚠️  Não foi possível listar empresas ativas, usando default={}: {}", defaultEmpresaId, e.getMessage());
        }
        return List.of(defaultEmpresaId);
    }

    private long contarRegistrosNoBanco(Integer idEmpresa) {
        try {
            LocalDate dataInicio  = LocalDate.now().minusYears(3);
            LocalDate dataTermino = LocalDate.now().plusMonths(1);
            return movimentacaoRepo.countByIdEmpresaAndDataVencimentoBetween(
                    idEmpresa, dataInicio, dataTermino);
        } catch (Exception e) {
            log.warn("⚠️  Não foi possível contar registros para empresa={}: {}", idEmpresa, e.getMessage());
            return 0;
        }
    }

    private List<String[]> montarPeriodos() {
        List<String[]> periodos = new ArrayList<>();
        YearMonth atual = YearMonth.now();

        for (int i = 0; i < mesesHistorico; i++) {
            YearMonth ym = atual.minusMonths(i);
            periodos.add(new String[]{
                    ym.atDay(1).toString(),
                    ym.atEndOfMonth().toString()
            });
        }
        return periodos;
    }

    private void executarSyncHistorico(List<String[]> periodos, Integer idEmpresa) {
        log.info("🚀 Sync histórico startup — {} períodos a sincronizar para empresa={}",
                periodos.size(), idEmpresa);

        int sucesso = 0;
        int falha   = 0;

        for (String[] periodo : periodos) {
            String dataInicio  = periodo[0];
            String dataTermino = periodo[1];
            try {
                log.info("📅 Sincronizando {} a {} — empresa={}", dataInicio, dataTermino, idEmpresa);
                syncService.sincronizarPeriodo(dataInicio, dataTermino, idEmpresa, true);
                sucesso++;
                Thread.sleep(delayEntreMesesMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("⚠️  Sync de startup interrompido após {} períodos.", sucesso);
                break;
            } catch (Exception e) {
                falha++;
                log.error("❌ Erro ao sincronizar {} a {} empresa={}: {}", dataInicio, dataTermino, idEmpresa, e.getMessage());
                try { Thread.sleep(delayEntreMesesMs * 2); } catch (InterruptedException ie2) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("🏁 Sync histórico startup empresa={} — sucesso={} falha={}", idEmpresa, sucesso, falha);
    }
}
