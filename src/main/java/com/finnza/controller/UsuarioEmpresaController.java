package com.finnza.controller;

import com.finnza.dto.EmpresaUsuarioDTO;
import com.finnza.service.UsuarioEmpresaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Controller para gerenciamento de empresas por usuário
 * Endpoints para CRUD de permissões de acesso a empresas
 * 
 * Segurança:
 * - Apenas ADMIN pode gerenciar empresas de outros usuários
 * - Usuários podem visualizar suas próprias empresas
 * - DELETE requer permissão ADMIN
 */
@RestController
@RequestMapping("/api/usuarios/{usuarioId}/empresas")
@RequiredArgsConstructor
@Slf4j
public class UsuarioEmpresaController {

    private final UsuarioEmpresaService usuarioEmpresaService;

    /**
     * GET /api/usuarios/{usuarioId}/empresas
     * 
     * Lista todas as empresas que um usuário tem acesso (ativas)
     * 
     * @param usuarioId ID do usuário
     * @return 200 OK com lista de empresas
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'CLIENTE')")
    public ResponseEntity<List<EmpresaUsuarioDTO>> listarEmpresas(@PathVariable Long usuarioId) {
        log.info("GET /usuarios/{}/empresas", usuarioId);
        
        try {
            List<EmpresaUsuarioDTO> empresas = usuarioEmpresaService.obterEmpresasDoUsuario(usuarioId);
            return ResponseEntity.ok(empresas);
        } catch (IllegalArgumentException e) {
            log.error("Erro ao listar empresas: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * GET /api/usuarios/{usuarioId}/empresas/completo
     * 
     * Lista todas as empresas do usuário (incluindo inativas)
     * Apenas para auditoria/administração
     * 
     * @param usuarioId ID do usuário
     * @return 200 OK com lista completa
     */
    @GetMapping("/completo")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<EmpresaUsuarioDTO>> listarEmpresasCompleto(@PathVariable Long usuarioId) {
        log.info("GET /usuarios/{}/empresas/completo (ADMIN)", usuarioId);
        
        try {
            List<EmpresaUsuarioDTO> empresas = usuarioEmpresaService.obterEmpresasCompleto(usuarioId);
            return ResponseEntity.ok(empresas);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * GET /api/usuarios/{usuarioId}/empresas/padrao
     * 
     * Obtém a empresa padrão do usuário
     * 
     * @param usuarioId ID do usuário
     * @return 200 OK com empresa padrão ou 204 No Content
     */
    @GetMapping("/padrao")
    @PreAuthorize("hasAnyRole('ADMIN', 'CLIENTE')")
    public ResponseEntity<EmpresaUsuarioDTO> obterEmpresaPadrao(@PathVariable Long usuarioId) {
        log.info("GET /usuarios/{}/empresas/padrao", usuarioId);
        
        try {
            Optional<EmpresaUsuarioDTO> empresa = usuarioEmpresaService.obterEmpresaPadrao(usuarioId);
            return empresa.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * POST /api/usuarios/{usuarioId}/empresas
     * 
     * Atribui nova empresa ao usuário
     * 
     * Request body:
     * {
     *   "idEmpresa": 123,
     *   "nomeEmpresa": "Empresa LTDA",  // opcional
     *   "padrao": false
     * }
     * 
     * @param usuarioId ID do usuário
     * @param request DTO com dados da empresa
     * @return 201 Created com empresa atribuída
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> atribuirEmpresa(
            @PathVariable Long usuarioId,
            @RequestBody Map<String, Object> request) {
        log.info("POST /usuarios/{}/empresas - Atribuindo empresa", usuarioId);
        
        try {
            Integer idEmpresa = ((Number) request.get("idEmpresa")).intValue();
            String nomeEmpresa = (String) request.get("nomeEmpresa");
            Boolean padrao = (Boolean) request.getOrDefault("padrao", false);
            
            EmpresaUsuarioDTO result = usuarioEmpresaService.atribuirEmpresa(
                    usuarioId, 
                    idEmpresa, 
                    nomeEmpresa, 
                    padrao
            );
            
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (IllegalArgumentException e) {
            log.error("Erro ao atribuir empresa: {}", e.getMessage());
            Map<String, String> erro = new HashMap<>();
            erro.put("erro", e.getMessage());
            return ResponseEntity.badRequest().body(erro);
        } catch (Exception e) {
            log.error("Erro inesperado: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * PUT /api/usuarios/{usuarioId}/empresas
     * 
     * Atualização em BULK: recebe array de IDs de empresas
     * Remove empresas não mencionadas, adiciona novas
     * 
     * Request body:
     * {
     *   "empresaIds": [123, 456, 789],
     *   "idEmpresaPadrao": 123
     * }
     * 
     * @param usuarioId ID do usuário
     * @param request DTO com array de IDs
     * @return 200 OK com lista atualizada
     */
    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> atualizarEmpresas(
            @PathVariable Long usuarioId,
            @RequestBody Map<String, Object> request) {
        log.info("PUT /usuarios/{}/empresas - Atualizando empresas em BULK", usuarioId);
        
        try {
            Object empresasObj = request.get("empresaIds");
            Integer idEmpresaPadrao = null;
            
            if (request.get("idEmpresaPadrao") != null) {
                idEmpresaPadrao = ((Number) request.get("idEmpresaPadrao")).intValue();
            }
            
            Integer[] idEmpresas;
            if (empresasObj instanceof java.util.List) {
                List<?> list = (List<?>) empresasObj;
                idEmpresas = list.stream()
                        .map(o -> ((Number) o).intValue())
                        .toArray(Integer[]::new);
            } else if (empresasObj instanceof Integer[]) {
                idEmpresas = (Integer[]) empresasObj;
            } else {
                throw new IllegalArgumentException("empresaIds deve ser um array de números");
            }
            
            List<EmpresaUsuarioDTO> result = usuarioEmpresaService.atualizarEmpresasDoUsuario(
                    usuarioId,
                    idEmpresas,
                    idEmpresaPadrao
            );
            
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.error("Erro ao atualizar empresas: {}", e.getMessage());
            Map<String, String> erro = new HashMap<>();
            erro.put("erro", e.getMessage());
            return ResponseEntity.badRequest().body(erro);
        } catch (Exception e) {
            log.error("Erro inesperado: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * PUT /api/usuarios/{usuarioId}/empresas/{idEmpresa}/padrao
     * 
     * Define uma empresa como padrão para o usuário
     * 
     * @param usuarioId ID do usuário
     * @param idEmpresa ID da empresa (BOMControle)
     * @return 200 OK
     */
    @PutMapping("/{idEmpresa}/padrao")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> definirEmpresaPadrao(
            @PathVariable Long usuarioId,
            @PathVariable Integer idEmpresa) {
        log.info("PUT /usuarios/{}/empresas/{}/padrao", usuarioId, idEmpresa);
        
        try {
            usuarioEmpresaService.definirEmpresaPadrao(usuarioId, idEmpresa);
            
            Map<String, String> response = new HashMap<>();
            response.put("mensagem", "Empresa definida como padrão");
            response.put("idEmpresa", idEmpresa.toString());
            
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.error("Erro: {}", e.getMessage());
            Map<String, String> erro = new HashMap<>();
            erro.put("erro", e.getMessage());
            return ResponseEntity.badRequest().body(erro);
        }
    }

    /**
     * DELETE /api/usuarios/{usuarioId}/empresas/{idEmpresa}
     * 
     * Remove acesso a uma empresa para o usuário (soft delete)
     * 
     * Query params opcionais:
     * - motivo: motivo da remoção
     * 
     * @param usuarioId ID do usuário
     * @param idEmpresa ID da empresa (BOMControle)
     * @param motivo Motivo opcional da remoção
     * @return 204 No Content na sucesso
     */
    @DeleteMapping("/{idEmpresa}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> removerAcessoEmpresa(
            @PathVariable Long usuarioId,
            @PathVariable Integer idEmpresa,
            @RequestParam(required = false) String motivo) {
        log.info("DELETE /usuarios/{}/empresas/{}", usuarioId, idEmpresa);
        
        try {
            usuarioEmpresaService.removerAcessoEmpresa(usuarioId, idEmpresa, motivo, "ADMIN");
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.error("Erro ao remover acesso: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Erro inesperado: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * GET /api/usuarios/{usuarioId}/empresas/count/total
     * 
     * Conta quantas empresas o usuário tem acesso
     * 
     * @param usuarioId ID do usuário
     * @return 200 OK com contador
     */
    @GetMapping("/count/total")
    @PreAuthorize("hasAnyRole('ADMIN', 'CLIENTE')")
    public ResponseEntity<Map<String, Object>> contarEmpresas(@PathVariable Long usuarioId) {
        log.info("GET /usuarios/{}/empresas/count/total", usuarioId);
        
        try {
            long count = usuarioEmpresaService.contarEmpresasAtivas(usuarioId);
            boolean temEmpresas = usuarioEmpresaService.temEmpresasAtivas(usuarioId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("total", count);
            response.put("temEmpresasAtivas", temEmpresas);
            
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * POST /api/usuarios/{usuarioId}/empresas/{idEmpresa}/validar
     * 
     * Valida se um usuário tem acesso a uma empresa específica
     * 
     * @param usuarioId ID do usuário
     * @param idEmpresa ID da empresa
     * @return 200 OK com resultado
     */
    @PostMapping("/{idEmpresa}/validar")
    @PreAuthorize("hasAnyRole('ADMIN', 'CLIENTE')")
    public ResponseEntity<Map<String, Object>> validarAcesso(
            @PathVariable Long usuarioId,
            @PathVariable Integer idEmpresa) {
        log.info("POST /usuarios/{}/empresas/{}/validar", usuarioId, idEmpresa);
        
        boolean temAcesso = usuarioEmpresaService.temAcesso(usuarioId, idEmpresa);
        
        Map<String, Object> response = new HashMap<>();
        response.put("usuarioId", usuarioId);
        response.put("idEmpresa", idEmpresa);
        response.put("temAcesso", temAcesso);
        response.put("timestamp", System.currentTimeMillis());
        
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/usuarios/{usuarioId}/empresas/debug/info
     * 
     * ENDPOINT DE DEBUG: Lista todas as empresas do usuário com informações detalhadas
     * Útil para diagnosticar problemas de acesso sem usar SQL
     * 
     * @param usuarioId ID do usuário
     * @return 200 OK com informações detalhadas
     */
    @GetMapping("/debug/info")
    @PreAuthorize("hasAnyRole('ADMIN', 'CLIENTE')")
    public ResponseEntity<Map<String, Object>> debugInfoEmpresas(@PathVariable Long usuarioId) {
        log.info("🔍 DEBUG: GET /usuarios/{}/empresas/debug/info", usuarioId);
        
        try {
            List<EmpresaUsuarioDTO> empresas = usuarioEmpresaService.obterEmpresasDoUsuario(usuarioId);
            Optional<EmpresaUsuarioDTO> empresaPadrao = empresas.stream()
                    .filter(EmpresaUsuarioDTO::getPadrao)
                    .findFirst();
            
            Map<String, Object> response = new HashMap<>();
            response.put("usuarioId", usuarioId);
            response.put("totalEmpresas", empresas.size());
            response.put("empresas", empresas);
            response.put("empresaPadrao", empresaPadrao.orElse(null));
            response.put("temEmpresaPadraoDefinida", empresaPadrao.isPresent());
            response.put("todasAtivas", empresas.stream().allMatch(EmpresaUsuarioDTO::getAtivo));
            response.put("timestamp", System.currentTimeMillis());
            
            log.info("🔍 DEBUG: Usuário {} tem {} empresas (padrão: {})", 
                    usuarioId, 
                    empresas.size(),
                    empresaPadrao.map(EmpresaUsuarioDTO::getNomeEmpresa).orElse("NENHUMA"));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("❌ ERRO ao obter debug info do usuário {}: {}", usuarioId, e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("erro", e.getMessage());
            error.put("usuarioId", usuarioId);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }}