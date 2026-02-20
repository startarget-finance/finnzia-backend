package com.finnza.controller;

import com.finnza.service.OmieService;
import com.finnza.service.UsuarioEmpresaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * Controller para integração com OMIE
 * Documentação: https://developer.omie.com.br/
 */
@Slf4j
@RestController
@RequestMapping("/api/omie")
@CrossOrigin(origins = "*")
public class OmieController {

    @Autowired
    private OmieService omieService;

    @Autowired
    private UsuarioEmpresaService usuarioEmpresaService;

    /**
     * Valida se o usuário tem acesso à empresa especificada
     * Retorna false se o usuário não tiver acesso ou se a empresa for nula/inválida
     */
    private boolean validarAcessoEmpresa(Integer empresaId) {
        if (empresaId == null) {
            return false;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        if (auth == null || !auth.isAuthenticated()) {
            log.warn("🔒 Tentativa de acesso sem autenticação válida");
            return false;
        }
        
        String email = auth.getName();
        boolean temAcesso = usuarioEmpresaService.validarAcessoUsuarioEmpresa(email, empresaId);
        
        if (temAcesso) {
            log.debug("✅ Usuário {} tem acesso à empresa {}", email, empresaId);
        } else {
            log.warn("🔒 Usuário {} NÃO tem acesso à empresa {}", email, empresaId);
        }
        
        return temAcesso;
    }

    /**
     * Extrai o ID da empresa do header X-Empresa-Id
     */
    private Integer extrairEmpresaDoHeader(String headerEmpresaId) {
        if (headerEmpresaId == null || headerEmpresaId.trim().isEmpty()) {
            return null;
        }
        
        try {
            return Integer.parseInt(headerEmpresaId.trim());
        } catch (NumberFormatException e) {
            log.warn("Header X-Empresa-Id inválido: {}", headerEmpresaId);
            return null;
        }
    }

    /**
     * Testa a conexão com a API do OMIE
     */
    @GetMapping("/testar")
    @PreAuthorize("hasPermission(null, 'CONFIGURACOES')")
    public ResponseEntity<Map<String, Object>> testarConexao() {
        log.info("Testando conexão com OMIE...");
        Map<String, Object> resultado = omieService.testarConexao();
        return ResponseEntity.ok(resultado);
    }

    /**
     * Lista empresas do OMIE
     * Nota: OMIE não possui endpoint direto de listagem de empresas
     */
    @GetMapping("/empresas")
    @PreAuthorize("hasPermission(null, 'CONFIGURACOES')")
    public ResponseEntity<Map<String, Object>> listarEmpresas() {
        log.info("Listando empresas do OMIE...");
        
        try {
            Map<String, Object> empresas = omieService.listarEmpresas();
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
     * Lista contas a pagar do OMIE
     * 
     * @param dataInicio Data inicial do período (formato: yyyy-MM-dd) (opcional)
     * @param dataFim Data final do período (formato: yyyy-MM-dd) (opcional)
     * @param pagina Número da página (opcional, padrão: 1)
     * @param registrosPorPagina Número de registros por página (opcional, padrão: 50, máximo: 500)
     */
    @GetMapping("/contas-pagar")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> listarContasPagar(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,
            @RequestParam(required = false, defaultValue = "1") Integer pagina,
            @RequestParam(required = false, defaultValue = "50") Integer registrosPorPagina) {
        
        // Se header X-Empresa-Id for enviado, validar
        Integer empresaFinal = extrairEmpresaDoHeader(headerEmpresaId);
        if (empresaFinal != null) {
            if (!validarAcessoEmpresa(empresaFinal)) {
                log.warn("🔒 ACESSO NEGADO: Usuário tentou listar contas a pagar da empresa {} sem permissão", empresaFinal);
                return ResponseEntity.status(403).body(Map.of(
                    "erro", true,
                    "mensagem", "Você não tem permissão de acessar esta empresa"
                ));
            }
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
        
        log.info("Listando contas a pagar do OMIE: dataInicio={}, dataFim={}, pagina={}, registrosPorPagina={}",
                dataInicio, dataFim, pagina, registrosPorPagina);
        
        try {
            int registrosFinal = Math.min(registrosPorPagina, 500);
            String dataInicioStr = dataInicio != null ? dataInicio.toString() : null;
            String dataFimStr = dataFim != null ? dataFim.toString() : null;
            
            Map<String, Object> resultado = omieService.listarContasPagar(
                    dataInicioStr, dataFimStr, pagina, registrosFinal);
            
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
     * Lista contas a receber do OMIE
     * 
     * @param dataInicio Data inicial do período (formato: yyyy-MM-dd) (opcional)
     * @param dataFim Data final do período (formato: yyyy-MM-dd) (opcional)
     * @param pagina Número da página (opcional, padrão: 1)
     * @param registrosPorPagina Número de registros por página (opcional, padrão: 50, máximo: 500)
     */
    @GetMapping("/contas-receber")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> listarContasReceber(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,
            @RequestParam(required = false, defaultValue = "1") Integer pagina,
            @RequestParam(required = false, defaultValue = "50") Integer registrosPorPagina) {
        
        // Validar acesso à empresa do usuário
        Integer empresaFinal = extrairEmpresaDoHeader(headerEmpresaId);
        if (empresaFinal != null) {
            if (!validarAcessoEmpresa(empresaFinal)) {
                log.warn("🔒 ACESSO NEGADO: Usuário tentou listar contas a receber da empresa {} sem permissão", empresaFinal);
                return ResponseEntity.status(403).body(Map.of(
                    "erro", true,
                    "mensagem", "Você não tem permissão de acessar esta empresa"
                ));
            }
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
        
        log.info("Listando contas a receber do OMIE: dataInicio={}, dataFim={}, pagina={}, registrosPorPagina={}",
                dataInicio, dataFim, pagina, registrosPorPagina);
        
        try {
            int registrosFinal = Math.min(registrosPorPagina, 500);
            String dataInicioStr = dataInicio != null ? dataInicio.toString() : null;
            String dataFimStr = dataFim != null ? dataFim.toString() : null;
            
            Map<String, Object> resultado = omieService.listarContasReceber(
                    dataInicioStr, dataFimStr, pagina, registrosFinal);
            
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
     * Pesquisa movimentações financeiras do OMIE (combina contas a pagar e receber)
     * 
     * @param idEmpresa ID da empresa (opcional)
     * @param dataInicio Data inicial do período (formato: yyyy-MM-dd) (opcional)
     * @param dataFim Data final do período (formato: yyyy-MM-dd) (opcional)
     * @param pagina Número da página (opcional, padrão: 1)
     * @param registrosPorPagina Número de registros por página (opcional, padrão: 50, máximo: 500)
     * @param tipo Filtro por tipo: 'receita' ou 'despesa' (opcional)
     * @param categoria Filtro por categoria (opcional)
     * @param textoPesquisa Filtro por texto (busca em nome, observação, etc) (opcional)
     */
    @GetMapping("/movimentacoes")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> pesquisarMovimentacoes(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam(required = false) String idEmpresa,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,
            @RequestParam(required = false, defaultValue = "1") Integer pagina,
            @RequestParam(required = false, defaultValue = "50") Integer registrosPorPagina,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String textoPesquisa) {
        
        // Se header X-Empresa-Id for enviado, sobrescreve o parâmetro idEmpresa
        Integer empresaFinal = extrairEmpresaDoHeader(headerEmpresaId);
        if (empresaFinal != null) {
            idEmpresa = empresaFinal.toString();
        } else if (idEmpresa != null) {
            // Se idEmpresa for passado como query param, validar
            try {
                empresaFinal = Integer.parseInt(idEmpresa);
            } catch (NumberFormatException e) {
                log.warn("Parâmetro idEmpresa inválido: {}", idEmpresa);
            }
        }
        
        // Validar acesso à empresa do usuário
        if (empresaFinal != null) {
            if (!validarAcessoEmpresa(empresaFinal)) {
                log.warn("🔒 ACESSO NEGADO: Usuário tentou pesquisar movimentações da empresa {} sem permissão", empresaFinal);
                return ResponseEntity.status(403).body(Map.of(
                    "erro", true,
                    "mensagem", "Você não tem permissão de acessar esta empresa"
                ));
            }
        }
        
        log.info("Pesquisando movimentações do OMIE: empresa={}, dataInicio={}, dataFim={}, pagina={}, registrosPorPagina={}, tipo={}, categoria={}, textoPesquisa={}",
                idEmpresa, dataInicio, dataFim, pagina, registrosPorPagina, tipo, categoria, textoPesquisa);
        
        try {
            // Limita registros por página a 500 (limite do OMIE)
            int registrosFinal = Math.min(registrosPorPagina, 500);
            
            String dataInicioStr = dataInicio != null ? dataInicio.toString() : null;
            String dataFimStr = dataFim != null ? dataFim.toString() : null;
            
            Map<String, Object> resultado = omieService.pesquisarMovimentacoes(
                    idEmpresa, dataInicioStr, dataFimStr, pagina, registrosFinal, tipo, categoria, textoPesquisa);
            
            return ResponseEntity.ok(resultado);
        } catch (Exception e) {
            log.error("Erro ao pesquisar movimentações", e);
            return ResponseEntity.status(500).body(Map.of(
                    "erro", true,
                    "mensagem", "Erro ao pesquisar movimentações: " + e.getMessage()
            ));
        }
    }

    /**
     * Retorna movimentações agrupadas por ano de emissão
     * Similar ao relatório do OMIE que mostra totais por ano
     * 
     * @param idEmpresa ID da empresa (opcional)
     * @param tipo Filtro por tipo: 'receita' ou 'despesa' (opcional)
     * @param categoria Filtro por categoria (opcional)
     * @param textoPesquisa Filtro por texto (opcional)
     */
    @GetMapping("/movimentacoes/agrupadas-por-ano")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> pesquisarMovimentacoesAgrupadasPorAno(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam(required = false) String idEmpresa,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String textoPesquisa) {
        
        // Se header X-Empresa-Id for enviado, sobrescreve o parâmetro idEmpresa
        Integer empresaFinal = extrairEmpresaDoHeader(headerEmpresaId);
        if (empresaFinal != null) {
            idEmpresa = empresaFinal.toString();
        } else if (idEmpresa != null) {
            // Se idEmpresa for passado como query param, validar
            try {
                empresaFinal = Integer.parseInt(idEmpresa);
            } catch (NumberFormatException e) {
                log.warn("Parâmetro idEmpresa inválido: {}", idEmpresa);
            }
        }
        
        // Validar acesso à empresa do usuário
        if (empresaFinal != null) {
            if (!validarAcessoEmpresa(empresaFinal)) {
                log.warn("🔒 ACESSO NEGADO: Usuário tentou pesquisar movimentações agrupadas por ano da empresa {} sem permissão", empresaFinal);
                return ResponseEntity.status(403).body(Map.of(
                    "erro", true,
                    "mensagem", "Você não tem permissão de acessar esta empresa"
                ));
            }
        }
        
        log.info("Pesquisando movimentações agrupadas por ano do OMIE: empresa={}, tipo={}, categoria={}, textoPesquisa={}",
                idEmpresa, tipo, categoria, textoPesquisa);
        
        try {
            Map<String, Object> resultado = omieService.pesquisarMovimentacoesAgrupadasPorAno(
                    idEmpresa, tipo, categoria, textoPesquisa);
            
            return ResponseEntity.ok(resultado);
        } catch (Exception e) {
            log.error("Erro ao pesquisar movimentações agrupadas por ano", e);
            return ResponseEntity.status(500).body(Map.of(
                    "erro", true,
                    "mensagem", "Erro ao pesquisar movimentações agrupadas por ano: " + e.getMessage()
            ));
        }
    }
}

