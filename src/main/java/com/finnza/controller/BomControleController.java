package com.finnza.controller;

import com.finnza.service.BomControleService;
import com.finnza.service.BomControleRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * Controller para integração com Bom Controle
 * Documentação: https://documenter.getpostman.com/view/1797561/SWT7BKWo
 */
@Slf4j
@RestController
@RequestMapping("/api/bomcontrole")
@CrossOrigin(origins = "*")
public class BomControleController {

    @Autowired
    private BomControleService bomControleService;
    
    @Autowired
    private BomControleRateLimiter rateLimiter;

    /**
     * Testa a conexão com a API do Bom Controle
     */
    @GetMapping("/testar")
    @PreAuthorize("hasPermission(null, 'CONFIGURACOES')")
    public ResponseEntity<Map<String, Object>> testarConexao() {
        log.info("Testando conexão com Bom Controle...");
        Map<String, Object> resultado = bomControleService.testarConexao();
        return ResponseEntity.ok(resultado);
    }

    /**
     * Lista empresas do Bom Controle
     */
    @GetMapping("/empresas")
    @PreAuthorize("hasPermission(null, 'FINANCEIRO')")
    public ResponseEntity<Map<String, Object>> listarEmpresas(
            @RequestParam(required = false) String pesquisa) {
        log.info("Listando empresas do Bom Controle: pesquisa={}", pesquisa);
        
        try {
            Map<String, Object> empresas = bomControleService.listarEmpresas(pesquisa);
            return ResponseEntity.ok(empresas);
        } catch (Exception e) {
            log.error("Erro ao listar empresas", e);
            return ResponseEntity.status(500).body(Map.of(
                    "erro", true,
                    "mensagem", "Erro ao listar empresas: " + e.getMessage()
            ));
        }
    }

    /**
     * Lista contas a pagar (movimentações com Debito=true)
     */
    @GetMapping("/contas-a-pagar")
    @PreAuthorize("hasPermission(null, 'FINANCEIRO')")
    public ResponseEntity<Map<String, Object>> listarContasPagar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataTermino,
            @RequestParam(required = false) String tipoData,
            @RequestParam(required = false) Integer idsEmpresa,
            @RequestParam(required = false) Integer idsCliente,
            @RequestParam(required = false) Integer idsFornecedor,
            @RequestParam(required = false) String textoPesquisa,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false, defaultValue = "50") Integer itensPorPagina,
            @RequestParam(required = false, defaultValue = "1") Integer numeroDaPagina) {
        
        log.info("Listando contas a pagar do Bom Controle: dataInicio={}, dataTermino={}, pagina={}",
                dataInicio, dataTermino, numeroDaPagina);
        
        try {
            String dataInicioStr = dataInicio != null ? dataInicio.toString() : null;
            String dataTerminoStr = dataTermino != null ? dataTermino.toString() : null;
            
            Map<String, Object> resultado = bomControleService.listarContasPagar(
                    dataInicioStr, dataTerminoStr, tipoData, idsEmpresa, idsCliente, idsFornecedor,
                    textoPesquisa, categoria, itensPorPagina, numeroDaPagina);
            
            return ResponseEntity.ok(resultado);
        } catch (Exception e) {
            log.error("Erro ao listar contas a pagar", e);
            return ResponseEntity.status(500).body(Map.of(
                    "erro", true,
                    "mensagem", "Erro ao listar contas a pagar: " + e.getMessage()
            ));
        }
    }

    /**
     * Lista contas a receber (movimentações com Debito=false)
     */
    @GetMapping("/contas-a-receber")
    @PreAuthorize("hasPermission(null, 'FINANCEIRO')")
    public ResponseEntity<Map<String, Object>> listarContasReceber(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataTermino,
            @RequestParam(required = false) String tipoData,
            @RequestParam(required = false) Integer idsEmpresa,
            @RequestParam(required = false) Integer idsCliente,
            @RequestParam(required = false) Integer idsFornecedor,
            @RequestParam(required = false) String textoPesquisa,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false, defaultValue = "50") Integer itensPorPagina,
            @RequestParam(required = false, defaultValue = "1") Integer numeroDaPagina) {
        
        log.info("Listando contas a receber do Bom Controle: dataInicio={}, dataTermino={}, pagina={}",
                dataInicio, dataTermino, numeroDaPagina);
        
        try {
            String dataInicioStr = dataInicio != null ? dataInicio.toString() : null;
            String dataTerminoStr = dataTermino != null ? dataTermino.toString() : null;
            
            Map<String, Object> resultado = bomControleService.listarContasReceber(
                    dataInicioStr, dataTerminoStr, tipoData, idsEmpresa, idsCliente, idsFornecedor,
                    textoPesquisa, categoria, itensPorPagina, numeroDaPagina);
            
            return ResponseEntity.ok(resultado);
        } catch (Exception e) {
            log.error("Erro ao listar contas a receber", e);
            return ResponseEntity.status(500).body(Map.of(
                    "erro", true,
                    "mensagem", "Erro ao listar contas a receber: " + e.getMessage()
            ));
        }
    }

    /**
     * Busca movimentações financeiras com filtros e paginação
     */
    @GetMapping("/movimentacoes")
    @PreAuthorize("hasPermission(null, 'FINANCEIRO')")
    public ResponseEntity<Map<String, Object>> buscarMovimentacoes(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataTermino,
            @RequestParam(required = false) String tipoData,
            @RequestParam(required = false) Integer idsEmpresa,
            @RequestParam(required = false) Integer idsCliente,
            @RequestParam(required = false) Integer idsFornecedor,
            @RequestParam(required = false) String textoPesquisa,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false, defaultValue = "50") Integer itensPorPagina,
            @RequestParam(required = false, defaultValue = "1") Integer numeroDaPagina) {
        
        log.info("Buscando movimentações do Bom Controle: dataInicio={}, dataTermino={}, tipo={}, pagina={}",
                dataInicio, dataTermino, tipo, numeroDaPagina);
        
        try {
            // Se não houver datas, usar mês atual como padrão
            LocalDate dataInicioFinal = dataInicio;
            LocalDate dataTerminoFinal = dataTermino;
            
            if (dataInicioFinal == null || dataTerminoFinal == null) {
                LocalDate hoje = LocalDate.now();
                if (dataInicioFinal == null) {
                    dataInicioFinal = hoje.withDayOfMonth(1); // Primeiro dia do mês
                }
                if (dataTerminoFinal == null) {
                    dataTerminoFinal = hoje.withDayOfMonth(hoje.lengthOfMonth()); // Último dia do mês
                }
            }
            
            String dataInicioStr = dataInicioFinal.toString();
            String dataTerminoStr = dataTerminoFinal.toString();
            
            Map<String, Object> resultado = bomControleService.buscarMovimentacoes(
                    dataInicioStr, dataTerminoStr, tipoData, idsEmpresa, idsCliente, idsFornecedor,
                    textoPesquisa, categoria, tipo, itensPorPagina, numeroDaPagina);
            
            return ResponseEntity.ok(resultado);
        } catch (IllegalArgumentException e) {
            log.warn("Parâmetros inválidos ao buscar movimentações: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Erro ao buscar movimentações", e);
            return ResponseEntity.status(500).body(Map.of(
                    "erro", true,
                    "mensagem", "Erro ao buscar movimentações: " + e.getMessage()
            ));
        }
    }

    /**
     * Pesquisa movimentações com filtros avançados
     */
    @GetMapping("/movimentacoes/pesquisar")
    @PreAuthorize("hasPermission(null, 'FINANCEIRO')")
    public ResponseEntity<Map<String, Object>> pesquisarMovimentacoes(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataTermino,
            @RequestParam(required = false) String tipoData,
            @RequestParam(required = false) Integer idsEmpresa,
            @RequestParam(required = false) Integer idsCliente,
            @RequestParam(required = false) Integer idsFornecedor,
            @RequestParam(required = false) String textoPesquisa,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false, defaultValue = "50") Integer itensPorPagina,
            @RequestParam(required = false, defaultValue = "1") Integer numeroDaPagina) {
        
        // Mesma lógica do buscarMovimentacoes, mas pode ter comportamento diferente no futuro
        return buscarMovimentacoes(dataInicio, dataTermino, tipoData, idsEmpresa, idsCliente, idsFornecedor,
                textoPesquisa, categoria, tipo, itensPorPagina, numeroDaPagina);
    }

    /**
     * Gera DFC (Demonstrativo de Fluxo de Caixa)
     */
    @GetMapping("/dfc")
    @PreAuthorize("hasPermission(null, 'FINANCEIRO')")
    public ResponseEntity<Map<String, Object>> gerarDFC(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataTermino,
            @RequestParam(required = false, defaultValue = "true") Boolean usarCache,
            @RequestParam(required = false, defaultValue = "false") Boolean forcarAtualizacao) {
        
        log.info("Gerando DFC do Bom Controle: dataInicio={}, dataTermino={}, usarCache={}, forcarAtualizacao={}",
                dataInicio, dataTermino, usarCache, forcarAtualizacao);
        
        try {
            String dataInicioStr = dataInicio.toString();
            String dataTerminoStr = dataTermino.toString();
            
            Map<String, Object> resultado = bomControleService.gerarDFC(
                    dataInicioStr, dataTerminoStr, usarCache, forcarAtualizacao);
            
            return ResponseEntity.ok(resultado);
        } catch (Exception e) {
            log.error("Erro ao gerar DFC", e);
            return ResponseEntity.status(500).body(Map.of(
                    "erro", true,
                    "mensagem", "Erro ao gerar DFC: " + e.getMessage()
            ));
        }
    }

    /**
     * Sincroniza movimentações de um período específico
     */
    @PostMapping("/sync/periodo")
    @PreAuthorize("hasPermission(null, 'CONFIGURACOES')")
    public ResponseEntity<Map<String, Object>> sincronizarPeriodo(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataTermino,
            @RequestParam(required = false, defaultValue = "6") Integer idEmpresa) {
        
        log.info("Sincronizando período do Bom Controle: dataInicio={}, dataTermino={}, idEmpresa={}",
                dataInicio, dataTermino, idEmpresa);
        
        try {
            String dataInicioStr = dataInicio != null ? dataInicio.toString() : null;
            String dataTerminoStr = dataTermino != null ? dataTermino.toString() : null;
            
            Map<String, Object> resultado = bomControleService.sincronizarPeriodo(
                    dataInicioStr, dataTerminoStr, idEmpresa);
            
            return ResponseEntity.ok(resultado);
        } catch (Exception e) {
            log.error("Erro ao sincronizar período", e);
            return ResponseEntity.status(500).body(Map.of(
                    "erro", true,
                    "mensagem", "Erro ao sincronizar período: " + e.getMessage()
            ));
        }
    }

    /**
     * Sincronização incremental - busca apenas movimentações modificadas
     */
    @PostMapping("/sync/incremental")
    @PreAuthorize("hasPermission(null, 'CONFIGURACOES')")
    public ResponseEntity<Map<String, Object>> sincronizarIncremental(
            @RequestParam(required = false, defaultValue = "6") Integer idEmpresa) {
        
        log.info("Sincronização incremental do Bom Controle: idEmpresa={}", idEmpresa);
        
        try {
            Map<String, Object> resultado = bomControleService.sincronizarIncremental(idEmpresa);
            return ResponseEntity.ok(resultado);
        } catch (Exception e) {
            log.error("Erro ao sincronizar incremental", e);
            return ResponseEntity.status(500).body(Map.of(
                    "erro", true,
                    "mensagem", "Erro ao sincronizar incremental: " + e.getMessage()
            ));
        }
    }

    /**
     * Obtém estatísticas do Rate Limiter
     */
    @GetMapping("/rate-limiter/stats")
    @PreAuthorize("hasPermission(null, 'CONFIGURACOES')")
    public ResponseEntity<Map<String, Object>> getRateLimiterStats() {
        log.info("Obtendo estatísticas do Rate Limiter...");
        Map<String, Object> stats = rateLimiter.getStats();
        return ResponseEntity.ok(stats);
    }
    
    /**
     * Limpa o cache do Rate Limiter
     */
    @PostMapping("/rate-limiter/clear-cache")
    @PreAuthorize("hasPermission(null, 'CONFIGURACOES')")
    public ResponseEntity<Map<String, Object>> clearRateLimiterCache() {
        log.info("Limpando cache do Rate Limiter...");
        rateLimiter.clearCache();
        return ResponseEntity.ok(Map.of(
                "sucesso", true,
                "mensagem", "Cache do Rate Limiter limpo com sucesso"
        ));
    }
    
    /**
     * Status do cache - informações sobre movimentações armazenadas
     */
    @GetMapping("/cache/status")
    @PreAuthorize("hasPermission(null, 'FINANCEIRO')")
    public ResponseEntity<Map<String, Object>> statusCache() {
        log.info("Consultando status do cache do Bom Controle...");
        
        try {
            Map<String, Object> resultado = bomControleService.statusCache();
            return ResponseEntity.ok(resultado);
        } catch (Exception e) {
            log.error("Erro ao consultar status do cache", e);
            return ResponseEntity.status(500).body(Map.of(
                    "erro", true,
                    "mensagem", "Erro ao consultar status do cache: " + e.getMessage()
            ));
        }
    }

    /**
     * Exporta movimentações para Excel
     */
    @GetMapping("/movimentacoes/exportar/excel")
    @PreAuthorize("hasPermission(null, 'FINANCEIRO')")
    public ResponseEntity<byte[]> exportarExcel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataTermino,
            @RequestParam(required = false) String tipoData,
            @RequestParam(required = false) Integer idsEmpresa,
            @RequestParam(required = false) Integer idsCliente,
            @RequestParam(required = false) Integer idsFornecedor,
            @RequestParam(required = false) String textoPesquisa) {
        
        log.info("Exportando movimentações para Excel do Bom Controle");
        
        try {
            // Por enquanto, retorna erro 501 (Not Implemented)
            // Implementar geração de Excel no futuro se necessário
            return ResponseEntity.status(501).build();
        } catch (Exception e) {
            log.error("Erro ao exportar Excel", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Exporta movimentações para PDF
     */
    @GetMapping("/movimentacoes/exportar/pdf")
    @PreAuthorize("hasPermission(null, 'FINANCEIRO')")
    public ResponseEntity<byte[]> exportarPDF(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataTermino,
            @RequestParam(required = false) String tipoData,
            @RequestParam(required = false) Integer idsEmpresa,
            @RequestParam(required = false) Integer idsCliente,
            @RequestParam(required = false) Integer idsFornecedor,
            @RequestParam(required = false) String textoPesquisa) {
        
        log.info("Exportando movimentações para PDF do Bom Controle");
        
        try {
            // Por enquanto, retorna erro 501 (Not Implemented)
            // Implementar geração de PDF no futuro se necessário
            return ResponseEntity.status(501).build();
        } catch (Exception e) {
            log.error("Erro ao exportar PDF", e);
            return ResponseEntity.status(500).build();
        }
    }
}
