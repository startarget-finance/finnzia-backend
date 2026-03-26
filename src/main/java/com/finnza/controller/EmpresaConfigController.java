package com.finnza.controller;

import com.finnza.config.EmpresaContextHolder;
import com.finnza.domain.entity.EmpresaConfig;
import com.finnza.repository.EmpresaConfigRepository;
import com.finnza.service.ContratoService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuração por empresa (ex.: API key do Asaas por empresa).
 * Cada empresa do Bom Controle pode ter sua própria conta Asaas.
 * Ao salvar a chave, dispara importação automática de contratos do Asaas para essa empresa.
 */
@Slf4j
@RestController
@RequestMapping("/api/empresas/config")
@RequiredArgsConstructor
public class EmpresaConfigController {

    private final EmpresaConfigRepository empresaConfigRepository;
    private final ContratoService contratoService;

    /**
     * Retorna a configuração Asaas da empresa (sem expor a key completa no log).
     */
    @GetMapping("/{idEmpresa}")
    @PreAuthorize("hasPermission(null, 'GERENCIAR_ACESSOS') or hasPermission(null, 'CONTRATOS')")
    public ResponseEntity<?> getConfig(@PathVariable Integer idEmpresa) {
        return empresaConfigRepository.findByIdEmpresa(idEmpresa)
                .map(c -> ResponseEntity.ok(Map.of(
                        "idEmpresa", c.getIdEmpresa(),
                        "asaasConfigurado", c.getAsaasApiKey() != null && !c.getAsaasApiKey().isBlank(),
                        "asaasBaseUrl", c.getAsaasBaseUrl() != null ? c.getAsaasBaseUrl() : ""
                )))
                .orElse(ResponseEntity.ok(Map.of(
                        "idEmpresa", idEmpresa,
                        "asaasConfigurado", false,
                        "asaasBaseUrl", ""
                )));
    }

    /**
     * Salva ou atualiza a configuração Asaas da empresa.
     * Body: { "asaasApiKey": "sua_chave", "asaasBaseUrl": "https://..." opcional }
     * Após salvar, se a empresa tiver chave configurada, importa contratos do Asaas automaticamente.
     */
    @PutMapping("/{idEmpresa}")
    @PreAuthorize("hasPermission(null, 'GERENCIAR_ACESSOS')")
    public ResponseEntity<?> saveConfig(@PathVariable Integer idEmpresa, @Valid @RequestBody EmpresaConfigRequest request) {
        EmpresaConfig config = empresaConfigRepository.findByIdEmpresa(idEmpresa)
                .orElse(EmpresaConfig.builder()
                        .idEmpresa(idEmpresa)
                        .build());
        String key = request.getAsaasApiKey() != null ? request.getAsaasApiKey().trim() : null;
        if (key != null && !key.isBlank()) {
            config.setAsaasApiKey(key);
        }
        config.setAsaasBaseUrl(request.getAsaasBaseUrl() != null && !request.getAsaasBaseUrl().isBlank()
                ? request.getAsaasBaseUrl().trim() : null);
        empresaConfigRepository.save(config);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idEmpresa", idEmpresa);
        body.put("message", "Configuração Asaas salva para a empresa.");

        if (config.getAsaasApiKey() != null && !config.getAsaasApiKey().isBlank()) {
            EmpresaContextHolder.setIdEmpresa(idEmpresa);
            try {
                int contratosImportados = contratoService.importarContratosDoAsaas();
                body.put("contratosImportados", contratosImportados);
                log.info("Importação automática após salvar chave: empresa {} -> {} contratos importados do Asaas", idEmpresa, contratosImportados);
            } catch (Exception e) {
                log.warn("Erro ao importar contratos do Asaas após salvar chave da empresa {}: {}", idEmpresa, e.getMessage());
                body.put("importacaoErro", e.getMessage());
            } finally {
                EmpresaContextHolder.clear();
            }
        }

        return ResponseEntity.ok(body);
    }

    @Data
    public static class EmpresaConfigRequest {
        private String asaasApiKey;
        private String asaasBaseUrl;
    }
}
