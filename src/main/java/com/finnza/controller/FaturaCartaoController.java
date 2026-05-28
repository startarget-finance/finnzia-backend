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

    @PostMapping("/preview-importacao")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> previewImportacao(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam(required = false) Integer idsEmpresa,
            @RequestBody ImportarFaturaRequest request) {
        return processarImportacaoPreview(headerEmpresaId, idsEmpresa, request);
    }

    @PostMapping("/importar-csv")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> importarCsv(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam(required = false) Integer idsEmpresa,
            @RequestBody ImportarFaturaRequest request) {
        return processarImportacaoPreview(headerEmpresaId, idsEmpresa, request);
    }

    @PostMapping("/confirmar-importacao")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> confirmarImportacao(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam(required = false) Integer idsEmpresa,
            @RequestBody ConfirmarImportacaoRequest request) {
        if (request == null || request.lancamentos() == null || request.lancamentos().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Selecione ao menos um lançamento para importar."
            ));
        }
        Integer empresaFinal = extrairEmpresa(headerEmpresaId, idsEmpresa);
        if (empresaFinal == null) {
            return ResponseEntity.badRequest().body(Map.of("erro", true, "mensagem", "Empresa não identificada."));
        }
        if (!validarAcessoEmpresa(empresaFinal)) {
            return ResponseEntity.status(403).body(Map.of("erro", true, "mensagem", "Sem permissao para empresa."));
        }
        try {
            return ResponseEntity.ok(
                    faturaCartaoService.confirmarImportacao(empresaFinal, request.cartaoId(), request.lancamentos())
            );
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("erro", true, "mensagem", e.getMessage()));
        }
    }

    @GetMapping("/regras-texto")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> listarRegrasTexto(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam(required = false) Long cartaoId) {
        Integer empresaFinal = extrairEmpresa(headerEmpresaId, null);
        if (empresaFinal == null) {
            return ResponseEntity.badRequest().body(Map.of("erro", true, "mensagem", "Empresa não identificada."));
        }
        if (!validarAcessoEmpresa(empresaFinal)) {
            return ResponseEntity.status(403).body(Map.of("erro", true, "mensagem", "Sem permissao para empresa."));
        }
        return ResponseEntity.ok(Map.of("itens", faturaCartaoService.listarRegrasTexto(empresaFinal, cartaoId)));
    }

    @PostMapping("/regras-texto")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> criarRegraTexto(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestBody Map<String, Object> payload) {
        Integer empresaFinal = extrairEmpresa(headerEmpresaId, null);
        if (empresaFinal == null) {
            return ResponseEntity.badRequest().body(Map.of("erro", true, "mensagem", "Empresa não identificada."));
        }
        if (!validarAcessoEmpresa(empresaFinal)) {
            return ResponseEntity.status(403).body(Map.of("erro", true, "mensagem", "Sem permissao para empresa."));
        }
        try {
            return ResponseEntity.ok(faturaCartaoService.criarRegraTexto(empresaFinal, payload));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("erro", true, "mensagem", e.getMessage()));
        }
    }

    @DeleteMapping("/regras-texto/{id}")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> removerRegraTexto(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @PathVariable Long id) {
        Integer empresaFinal = extrairEmpresa(headerEmpresaId, null);
        if (empresaFinal == null) {
            return ResponseEntity.badRequest().body(Map.of("erro", true, "mensagem", "Empresa não identificada."));
        }
        if (!validarAcessoEmpresa(empresaFinal)) {
            return ResponseEntity.status(403).body(Map.of("erro", true, "mensagem", "Sem permissao para empresa."));
        }
        try {
            faturaCartaoService.removerRegraTexto(empresaFinal, id);
            return ResponseEntity.ok(Map.of("erro", false));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("erro", true, "mensagem", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> processarImportacaoPreview(
            String headerEmpresaId,
            Integer idsEmpresa,
            ImportarFaturaRequest request
    ) {
        if (request == null || request.csvContent() == null || request.csvContent().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Arquivo CSV vazio."
            ));
        }
        Integer empresaFinal = extrairEmpresa(headerEmpresaId, idsEmpresa);
        if (empresaFinal == null) {
            return ResponseEntity.badRequest().body(Map.of("erro", true, "mensagem", "Empresa não identificada."));
        }
        if (!validarAcessoEmpresa(empresaFinal)) {
            return ResponseEntity.status(403).body(Map.of("erro", true, "mensagem", "Sem permissao para empresa."));
        }
        try {
            return ResponseEntity.ok(
                    faturaCartaoService.previewImportacao(empresaFinal, request.csvContent(), request.cartaoId())
            );
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("erro", true, "mensagem", e.getMessage()));
        }
    }

    @PostMapping("/gerar-contas-pagar")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> gerarContasPagar(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam(required = false) Integer idsEmpresa,
            @RequestBody GerarContasPagarRequest request) {
        if (request == null || request.lancamentos() == null || request.lancamentos().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", true,
                    "mensagem", "Nao ha lancamentos para processar."
            ));
        }
        Integer empresaFinal = extrairEmpresa(headerEmpresaId, idsEmpresa);
        if (empresaFinal == null) {
            return ResponseEntity.badRequest().body(Map.of("erro", true, "mensagem", "Empresa não identificada."));
        }
        if (!validarAcessoEmpresa(empresaFinal)) {
            return ResponseEntity.status(403).body(Map.of("erro", true, "mensagem", "Sem permissao para empresa."));
        }
        try {
            return ResponseEntity.ok(
                    faturaCartaoService.gerarContasPagar(
                            empresaFinal,
                            request.cartaoId(),
                            request.nomeCartao(),
                            request.lancamentos()
                    )
            );
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("erro", true, "mensagem", e.getMessage()));
        }
    }

    @GetMapping("/importados-recentes")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> listarImportadosRecentes(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam(required = false) Integer idsEmpresa,
            @RequestParam(required = false) Long cartaoId) {
        Integer empresaFinal = extrairEmpresa(headerEmpresaId, idsEmpresa);
        if (empresaFinal == null) {
            return ResponseEntity.badRequest().body(Map.of("erro", true, "mensagem", "Empresa não identificada."));
        }
        if (!validarAcessoEmpresa(empresaFinal)) {
            return ResponseEntity.status(403).body(Map.of("erro", true, "mensagem", "Sem permissao para empresa."));
        }
        try {
            return ResponseEntity.ok(faturaCartaoService.listarPainelImportacao(empresaFinal, cartaoId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("erro", true, "mensagem", e.getMessage()));
        }
    }

    @GetMapping("/cadastros")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> listarCadastros(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestParam(required = false) Integer idsEmpresa) {
        Integer empresaFinal = extrairEmpresa(headerEmpresaId, idsEmpresa);
        if (empresaFinal == null) {
            return ResponseEntity.ok(Map.of("itens", List.of()));
        }
        if (!validarAcessoEmpresa(empresaFinal)) {
            return ResponseEntity.status(403).body(Map.of("erro", true, "mensagem", "Sem permissao para empresa."));
        }
        return ResponseEntity.ok(Map.of("itens", faturaCartaoService.listarCartoesCadastrados(empresaFinal)));
    }

    @PostMapping("/cadastros")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> criarCadastro(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @RequestBody Map<String, Object> payload) {
        Integer empresaFinal = extrairEmpresa(headerEmpresaId, null);
        if (empresaFinal == null) {
            return ResponseEntity.badRequest().body(Map.of("erro", true, "mensagem", "Empresa não identificada."));
        }
        if (!validarAcessoEmpresa(empresaFinal)) {
            return ResponseEntity.status(403).body(Map.of("erro", true, "mensagem", "Sem permissao para empresa."));
        }
        try {
            return ResponseEntity.ok(Map.of("erro", false, "item", faturaCartaoService.criarCartao(empresaFinal, payload)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("erro", true, "mensagem", e.getMessage()));
        }
    }

    @PutMapping("/cadastros/{id}")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> atualizarCadastro(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload) {
        Integer empresaFinal = extrairEmpresa(headerEmpresaId, null);
        if (empresaFinal == null) {
            return ResponseEntity.badRequest().body(Map.of("erro", true, "mensagem", "Empresa não identificada."));
        }
        if (!validarAcessoEmpresa(empresaFinal)) {
            return ResponseEntity.status(403).body(Map.of("erro", true, "mensagem", "Sem permissao para empresa."));
        }
        try {
            faturaCartaoService.atualizarCartao(empresaFinal, id, payload);
            return ResponseEntity.ok(Map.of("erro", false));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("erro", true, "mensagem", e.getMessage()));
        }
    }

    @DeleteMapping("/cadastros/{id}")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<Map<String, Object>> removerCadastro(
            @RequestHeader(value = "X-Empresa-Id", required = false) String headerEmpresaId,
            @PathVariable Long id) {
        Integer empresaFinal = extrairEmpresa(headerEmpresaId, null);
        if (empresaFinal == null) {
            return ResponseEntity.badRequest().body(Map.of("erro", true, "mensagem", "Empresa não identificada."));
        }
        if (!validarAcessoEmpresa(empresaFinal)) {
            return ResponseEntity.status(403).body(Map.of("erro", true, "mensagem", "Sem permissao para empresa."));
        }
        try {
            faturaCartaoService.removerCartao(empresaFinal, id);
            return ResponseEntity.ok(Map.of("erro", false));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("erro", true, "mensagem", e.getMessage()));
        }
    }

    public record ImportarFaturaRequest(String csvContent, Long cartaoId) {}

    public record ConfirmarImportacaoRequest(Long cartaoId, List<Map<String, Object>> lancamentos) {}

    public record GerarContasPagarRequest(Long cartaoId, String nomeCartao, List<Map<String, Object>> lancamentos) {}

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
        Integer empresaPadrao = usuarioEmpresaService.obterIdEmpresaContextoPorEmail(email).orElse(null);
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

