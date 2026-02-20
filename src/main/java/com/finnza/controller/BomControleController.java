package com.finnza.controller;

import com.finnza.service.BomControleService;
import com.finnza.service.BomControleRateLimiter;
import com.finnza.service.UsuarioEmpresaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * Controller para integração com Bom Controle
 * Documentação: https://documenter.getpostman.com/view/1797561/SWT7BKWo
 * 
 * Suporta filtragem por empresa via header X-Empresa-Id:
 * Quando presente, sobrescreve o parâmetro idsEmpresa e força uso dessa empresa
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

    @Autowired
    private UsuarioEmpresaService usuarioEmpresaService;

    /**
     * Extrai o ID da empresa do header X-Empresa-Id
     * Este header é adicionado automaticamente pelo CompanyInterceptor
     * 
     * @param headerEmpresaId valor do header X-Empresa-Id
     * @return Integer com o ID da empresa, ou null se não informado
     */
    private Integer extrairEmpresaDoHeader(String headerEmpresaId) {
        if (headerEmpresaId != null && !headerEmpresaId.isBlank()) {
            try {
                Integer empresaId = Integer.parseInt(headerEmpresaId.trim());
                if (empresaId > 0) {
                    log.debug("📤 Usando X-Empresa-Id do header: {}", empresaId);
                    return empresaId;
                }
            } catch (NumberFormatException e) {
                log.warn("⚠️ X-Empresa-Id inválido: {}", headerEmpresaId);
            }
        }
        return null;
    }

    /**
     * Valida se o usuário autenticado tem permissão de acessar a empresa solicitada
     * Se o usuário é ADMIN, tem acesso a todas as empresas
     * Se for cliente, valida se a empresa está na lista de empresas permitidas
     * 
     * @param empresaId ID da empresa a validar
     * @return true se tem acesso, false caso contrário
     */
    private boolean validarAcessoEmpresa(Integer empresaId) {
        if (empresaId == null || empresaId <= 0) {
            return false;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            log.warn("⚠️ Usuário não autenticado ao validar acesso à empresa {}", empresaId);
            return false;
        }

        String email = auth.getName();
        
        try {
            // Verifica se o usuário tem acesso a esta empresa
            boolean temAcesso = usuarioEmpresaService.validarAcessoUsuarioEmpresa(email, empresaId);
            
            if (temAcesso) {
                log.info("✅ Usuário {} tem acesso à empresa {}", email, empresaId);
            } else {
                log.warn("🔒 ACESSO NEGADO: Usuário {} tentou acessar empresa {} sem permissão", email, empresaId);
            }
            
            return temAcesso;
        } catch (Exception e) {
            log.error("❌ Erro ao validar acesso à empresa {} para usuário {}:", empresaId, email, e);
            return false;
        }
    }

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
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
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
     * Suporta header X-Empresa-Id para filtragem automática por empresa
     */
    @GetMapping("/contas-a-pagar")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> listarContasPagar(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
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
        
        // Se header X-Empresa-Id for enviado, sobrescreve o parâmetro idsEmpresa
        Integer empresaFinal = extrairEmpresaDoHeader(headerEmpresaId);
        if (empresaFinal != null) {
            idsEmpresa = empresaFinal;
        } else {
            // Se não foi fornecido header X-Empresa-Id, verificar se é obrigatório
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) {
                String email = auth.getName();
                if (!usuarioEmpresaService.isAdmin(email)) {
                    log.warn("⚠️ Usuário {} fez requisição sem X-Empresa-Id - requerido para usuários com acesso limitado", email);
                    return ResponseEntity.status(400).body(Map.of(
                            "erro", true,
                            "mensagem", "X-Empresa-Id header é obrigatório para esta requisição"
                    ));
                }
            }
        }
        
        // Validar acesso à empresa do usuário
        if (empresaFinal != null) {
            if (!validarAcessoEmpresa(empresaFinal)) {
                log.warn("🔒 ACESSO NEGADO: Usuário tentou listar contas a pagar da empresa {} sem permissão", empresaFinal);
                return ResponseEntity.status(403).body(Map.of(
                    "erro", true,
                    "mensagem", "Você não tem permissão de acessar esta empresa"
                ));
            }
        }
        
        log.info("Listando contas a pagar do Bom Controle: dataInicio={}, dataTermino={}, empresa={}, pagina={}",
                dataInicio, dataTermino, idsEmpresa, numeroDaPagina);
        
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
     * Suporta header X-Empresa-Id para filtragem automática por empresa
     */
    @GetMapping("/contas-a-receber")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> listarContasReceber(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
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
        
        // Se header X-Empresa-Id for enviado, sobrescreve o parâmetro idsEmpresa
        Integer empresaFinal = extrairEmpresaDoHeader(headerEmpresaId);
        if (empresaFinal != null) {
            idsEmpresa = empresaFinal;
        } else {
            // Se não foi fornecido header X-Empresa-Id, verificar se é obrigatório
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) {
                String email = auth.getName();
                if (!usuarioEmpresaService.isAdmin(email)) {
                    log.warn("⚠️ Usuário {} fez requisição sem X-Empresa-Id - requerido para usuários com acesso limitado", email);
                    return ResponseEntity.status(400).body(Map.of(
                            "erro", true,
                            "mensagem", "X-Empresa-Id header é obrigatório para esta requisição"
                    ));
                }
            }
        }
        
        // Validar acesso à empresa do usuário
        if (empresaFinal != null) {
            if (!validarAcessoEmpresa(empresaFinal)) {
                log.warn("🔒 ACESSO NEGADO: Usuário tentou listar contas a receber da empresa {} sem permissão", empresaFinal);
                return ResponseEntity.status(403).body(Map.of(
                    "erro", true,
                    "mensagem", "Você não tem permissão de acessar esta empresa"
                ));
            }
        }
        
        log.info("Listando contas a receber do Bom Controle: dataInicio={}, dataTermino={}, empresa={}, pagina={}",
                dataInicio, dataTermino, idsEmpresa, numeroDaPagina);
        
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
     * 
     * Suporta header X-Empresa-Id para filtragem automática por empresa
     */
    @GetMapping("/movimentacoes")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> buscarMovimentacoes(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
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
        
        // Se header X-Empresa-Id for enviado, sobrescreve o parâmetro idsEmpresa
        Integer empresaFinal = extrairEmpresaDoHeader(headerEmpresaId);
        if (empresaFinal != null) {
            log.info("Buscando movimentações com X-Empresa-Id do header: {}", empresaFinal);
            
            // VALIDAÇÃO DE SEGURANÇA: Verifica se o usuário tem permissão para acessar esta empresa
            if (!validarAcessoEmpresa(empresaFinal)) {
                log.error("🔒 ACESSO NEGADO: Usuário tentou acessar empresa {} sem permissão", empresaFinal);
                return ResponseEntity.status(403).body(Map.of(
                        "erro", true,
                        "mensagem", "Você não tem permissão de acessar esta empresa"
                ));
            }
            
            idsEmpresa = empresaFinal;
        } else {
            // Se não foi fornecido header X-Empresa-Id, verificar se é obrigatório
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) {
                String email = auth.getName();
                // Se usuário não é Admin, DEVE fornecer X-Empresa-Id
                if (!usuarioEmpresaService.isAdmin(email)) {
                    log.warn("⚠️ Usuário {} fez requisição sem X-Empresa-Id - requerido para usuários com acesso limitado", email);
                    return ResponseEntity.status(400).body(Map.of(
                            "erro", true,
                            "mensagem", "X-Empresa-Id header é obrigatório para esta requisição"
                    ));
                }
            }
        }
        
        log.info("Buscando movimentações do Bom Controle: dataInicio={}, dataTermino={}, empresa={}, pagina={}",
                dataInicio, dataTermino, idsEmpresa, numeroDaPagina);
        
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
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> pesquisarMovimentacoes(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
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
        return buscarMovimentacoes(headerEmpresaId, dataInicio, dataTermino, tipoData, idsEmpresa, idsCliente, idsFornecedor,
                textoPesquisa, categoria, tipo, itensPorPagina, numeroDaPagina);
    }

    /**
     * Gera DFC (Demonstrativo de Fluxo de Caixa)
     */
    @GetMapping("/dfc")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
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
        
        // Validar acesso à empresa do usuário
        if (idEmpresa != null) {
            if (!validarAcessoEmpresa(idEmpresa)) {
                log.warn("🔒 ACESSO NEGADO: Usuário tentou sincronizar período da empresa {} sem permissão", idEmpresa);
                return ResponseEntity.status(403).body(Map.of(
                    "erro", true,
                    "mensagem", "Você não tem permissão de acessar esta empresa"
                ));
            }
        }
        
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
        
        // Validar acesso à empresa do usuário
        if (idEmpresa != null) {
            if (!validarAcessoEmpresa(idEmpresa)) {
                log.warn("🔒 ACESSO NEGADO: Usuário tentou sincronizar incrementalmente a empresa {} sem permissão", idEmpresa);
                return ResponseEntity.status(403).body(Map.of(
                    "erro", true,
                    "mensagem", "Você não tem permissão de acessar esta empresa"
                ));
            }
        }
        
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
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
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

    // Endpoints de exportação (exportarExcel e exportarPDF) removidos
    // Implementar futuramente usando bibliotecas como Apache POI (Excel) ou iText (PDF)
    // se a funcionalidade for necessária
}
