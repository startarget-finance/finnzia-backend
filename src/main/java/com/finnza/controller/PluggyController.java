package com.finnza.controller;

import com.finnza.dto.request.PluggyConnectTokenRequest;
import com.finnza.dto.request.PluggyRegisterItemRequest;
import com.finnza.dto.request.PluggySyncRequest;
import com.finnza.dto.response.PluggyConexaoResponse;
import com.finnza.dto.response.PluggyConnectTokenResponse;
import com.finnza.dto.response.PluggyStatusResponse;
import com.finnza.dto.response.PluggySyncResponse;
import com.finnza.service.ErpFinanceiroService;
import com.finnza.service.PluggyService;
import com.finnza.service.PluggySyncService;
import com.finnza.service.UsuarioEmpresaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/pluggy")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class PluggyController {

    private final PluggyService pluggyService;
    private final PluggySyncService pluggySyncService;
    private final UsuarioEmpresaService usuarioEmpresaService;
    private final ErpFinanceiroService erpFinanceiroService;

    private Integer extrairEmpresaDoHeader(String headerEmpresaId) {
        if (headerEmpresaId != null && !headerEmpresaId.isBlank()) {
            try {
                Integer empresaId = Integer.parseInt(headerEmpresaId.trim());
                return empresaId > 0 ? empresaId : null;
            } catch (NumberFormatException e) {
                log.warn("X-Empresa-Id inválido: {}", headerEmpresaId);
            }
        }
        return null;
    }

    private boolean validarAcessoEmpresa(Integer empresaId) {
        if (empresaId == null || empresaId <= 0) {
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        String email = auth.getName();
        try {
            if (!usuarioEmpresaService.usuarioTemEmpresasAtivasPorEmail(email)) {
                return true;
            }
            return usuarioEmpresaService.validarAcessoUsuarioEmpresa(email, empresaId);
        } catch (Exception e) {
            log.error("Erro ao validar acesso à empresa {} para {}", empresaId, email, e);
            return false;
        }
    }

    private Integer resolverEmpresaId(String headerEmpresaId) {
        Integer idEmpresa = extrairEmpresaDoHeader(headerEmpresaId);
        if (idEmpresa != null) {
            return idEmpresa;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            String email = auth.getName();
            Integer empresaContexto = usuarioEmpresaService.obterIdEmpresaContextoPorEmail(email).orElse(null);
            if (empresaContexto != null && empresaContexto > 0) {
                return empresaContexto;
            }
        }
        return erpFinanceiroService.obterPrimeiraEmpresaDisponivelId().orElse(null);
    }

    @GetMapping("/status")
    public ResponseEntity<PluggyStatusResponse> status() {
        return ResponseEntity.ok(pluggyService.status());
    }

    @GetMapping("/conexoes")
    public ResponseEntity<List<PluggyConexaoResponse>> conexoes(Authentication authentication) {
        String email = authentication.getName();
        return ResponseEntity.ok(pluggyService.listarConexoes(email));
    }

    @PostMapping("/connect-token")
    public ResponseEntity<PluggyConnectTokenResponse> connectToken(
            Authentication authentication, @RequestBody(required = false) PluggyConnectTokenRequest body) {
        if (!pluggyService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        try {
            String itemId = body != null ? body.getItemId() : null;
            return ResponseEntity.ok(pluggyService.criarConnectToken(authentication.getName(), itemId));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    @PostMapping("/item")
    public ResponseEntity<PluggyConexaoResponse> registrarItem(
            Authentication authentication, @Valid @RequestBody PluggyRegisterItemRequest request) {
        try {
            return ResponseEntity.ok(pluggyService.registrarOuAtualizarItem(authentication.getName(), request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Busca contas e transações na Pluggy e grava em {@code bc_movimentacoes} com o mesmo fluxo de pré-aprovação
     * da conciliação OFX ({@code ofx_importacoes}, tipo {@code PLUGGY}).
     */
    @PostMapping("/conexoes/{id}/sync")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> sincronizarConexao(
            Authentication authentication,
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @PathVariable Long id,
            @RequestBody(required = false) PluggySyncRequest body
    ) {
        if (!pluggyService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "erro", true,
                    "mensagem", "Pluggy não configurado"
            ));
        }
        Integer idEmpresa = resolverEmpresaId(headerEmpresaId);
        if (idEmpresa == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Não foi possível identificar a empresa (envie X-Empresa-Id ou configure empresa padrão)."
            ));
        }
        if (!validarAcessoEmpresa(idEmpresa)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "erro", true,
                    "mensagem", "Sem permissão para esta empresa"
            ));
        }
        try {
            PluggySyncResponse res = pluggySyncService.sincronizarTransacoes(authentication.getName(), id, idEmpresa, body);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("erro", false);
            out.put("totalPluggy", res.getTotalPluggy());
            out.put("importadas", res.getImportadas());
            out.put("ignoradasDuplicadas", res.getIgnoradasDuplicadas());
            out.put("importacaoId", res.getImportacaoId());
            out.put("conta", res.getConta());
            out.put("periodoInicio", res.getPeriodoInicio() != null ? res.getPeriodoInicio().toString() : null);
            out.put("periodoFim", res.getPeriodoFim() != null ? res.getPeriodoFim().toString() : null);
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Requisição inválida";
            log.warn("Pluggy sync rejeitado (400): {}", msg);
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", msg
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "erro", true,
                    "mensagem", e.getMessage() != null ? e.getMessage() : "Falha na API Pluggy"
            ));
        }
    }
}
