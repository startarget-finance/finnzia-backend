package com.finnza.config;

import com.finnza.repository.EmpresaConfigRepository;
import com.finnza.service.ContratoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sincronização periódica de contratos/cobranças do Asaas por empresa.
 * Garante que novas cobranças no Asaas cheguem ao sistema automaticamente.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsaasSyncScheduler {

    private final EmpresaConfigRepository empresaConfigRepository;
    private final ContratoService contratoService;

    @Value("${asaas.sync.enabled:true}")
    private boolean syncEnabled;

    @Scheduled(cron = "${asaas.sync.cron:0 */20 * * * *}")
    public void sincronizarAsaasPeriodicamente() {
        if (!syncEnabled) {
            return;
        }

        var empresas = empresaConfigRepository.findAllByAsaasApiKeyIsNotNull().stream()
                .filter(c -> c.getAsaasApiKey() != null && !c.getAsaasApiKey().isBlank())
                .toList();

        for (var config : empresas) {
            Integer idEmpresa = config.getIdEmpresa();
            if (idEmpresa == null || idEmpresa <= 0) {
                continue;
            }

            try {
                EmpresaContextHolder.setIdEmpresa(idEmpresa);
                int importados = contratoService.importarContratosDoAsaas();
                var syncResumo = contratoService.sincronizarTodosComAsaas();
                log.info("Sync Asaas automático concluído para empresa {}: importados={}, resumo={}",
                        idEmpresa, importados, syncResumo);
            } catch (Exception e) {
                log.warn("Falha no sync automático do Asaas para empresa {}: {}", idEmpresa, e.getMessage());
            } finally {
                EmpresaContextHolder.clear();
            }
        }
    }
}
