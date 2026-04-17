package com.finnza.controller;

import com.finnza.service.FaturaCartaoService;
import com.finnza.service.ErpFinanceiroService;
import com.finnza.service.UsuarioEmpresaService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/fatura-cartao")
@CrossOrigin(origins = "*")
public class FaturaCartaoController {

    private final FaturaCartaoService faturaCartaoService;
    private final UsuarioEmpresaService usuarioEmpresaService;
    private final ErpFinanceiroService erpFinanceiroService;

    public FaturaCartaoController(
            FaturaCartaoService faturaCartaoService,
            UsuarioEmpresaService usuarioEmpresaService,
            ErpFinanceiroService erpFinanceiroService) {
        this.faturaCartaoService = faturaCartaoService;
        this.usuarioEmpresaService = usuarioEmpresaService;
        this.erpFinanceiroService = erpFinanceiroService;
    }

    @GetMapping("/cartoes")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> listarCartoes(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam(required = false) Integer idsEmpresa) {
        Integer empresaFinal = extrairEmpresa(headerEmpresaId, idsEmpresa);
        if (empresaFinal == null) {
            return ResponseEntity.ok(Map.of("cartoes", List.of()));
        }
        if (!validarAcessoEmpresa(empresaFinal)) {
            return ResponseEntity.status(403).body(Map.of("erro", true, "mensagem", "Sem permissao para empresa."));
        }
        return ResponseEntity.ok(Map.of("cartoes", faturaCartaoService.listarCartoesResumo(empresaFinal)));
    }

    @PostMapping("/importar-csv")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> importarCsv(@RequestBody ImportarFaturaRequest request) {
        if (request == null || request.csvContent() == null || request.csvContent().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Arquivo CSV vazio."
            ));
        }
        return ResponseEntity.ok(faturaCartaoService.importarCsv(request.csvContent()));
    }

    @PostMapping("/gerar-contas-pagar")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> gerarContasPagar(@RequestBody GerarContasPagarRequest request) {
        if (request == null || request.lancamentos() == null || request.lancamentos().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Nao ha lancamentos para processar."
            ));
        }
        return ResponseEntity.ok(faturaCartaoService.gerarContasPagar(request.nomeCartao(), request.lancamentos()));
    }

    public record ImportarFaturaRequest(String csvContent) {}

    public record GerarContasPagarRequest(String nomeCartao, List<Map<String, Object>> lancamentos) {}

    private Integer extrairEmpresa(String headerEmpresaId, Integer idsEmpresa) {
        if (headerEmpresaId != null && !headerEmpresaId.isBlank()) {
            try {
                return Integer.parseInt(headerEmpresaId.trim());
            } catch (Exception ignored) {
            }
        }
        if (idsEmpresa != null && idsEmpresa > 0) {
            return idsEmpresa;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        String email = auth.getName();
        Integer empresaPadrao = usuarioEmpresaService.obterIdEmpresaPadraoPorEmail(email).orElse(null);
        if (empresaPadrao != null && empresaPadrao > 0) {
            return empresaPadrao;
        }
        return erpFinanceiroService.obterPrimeiraEmpresaDisponivelId().orElse(null);
    }

    private boolean validarAcessoEmpresa(Integer empresaId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        String email = auth.getName();
        if (!usuarioEmpresaService.usuarioTemEmpresasAtivasPorEmail(email)) {
            return true;
        }
        return usuarioEmpresaService.validarAcessoUsuarioEmpresa(email, empresaId);
    }
}

