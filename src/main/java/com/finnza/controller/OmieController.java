package com.finnza.controller;

import com.finnza.service.OmieService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
    @PreAuthorize("hasPermission(null, 'FINANCEIRO')")
    public ResponseEntity<Map<String, Object>> listarContasPagar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,
            @RequestParam(required = false, defaultValue = "1") Integer pagina,
            @RequestParam(required = false, defaultValue = "50") Integer registrosPorPagina) {
        
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
    @PreAuthorize("hasPermission(null, 'FINANCEIRO')")
    public ResponseEntity<Map<String, Object>> listarContasReceber(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,
            @RequestParam(required = false, defaultValue = "1") Integer pagina,
            @RequestParam(required = false, defaultValue = "50") Integer registrosPorPagina) {
        
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
    @PreAuthorize("hasPermission(null, 'FINANCEIRO')")
    public ResponseEntity<Map<String, Object>> pesquisarMovimentacoes(
            @RequestParam(required = false) String idEmpresa,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,
            @RequestParam(required = false, defaultValue = "1") Integer pagina,
            @RequestParam(required = false, defaultValue = "50") Integer registrosPorPagina,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String textoPesquisa) {
        
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
}

