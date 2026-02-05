package com.finnza.controller;

import com.finnza.domain.entity.Contrato;
import com.finnza.dto.request.CriarContratoRequest;
import com.finnza.dto.response.ContratoDTO;
import com.finnza.service.ContratoService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller para gerenciamento de contratos
 */
@RestController
@RequestMapping("/api/contratos")
@CrossOrigin(origins = "*")
public class ContratoController {

    @Autowired
    private ContratoService contratoService;

    /**
     * Cria um novo contrato
     */
    @PostMapping
    @PreAuthorize("hasPermission(null, 'CONTRATOS')")
    public ResponseEntity<ContratoDTO> criarContrato(@Valid @RequestBody CriarContratoRequest request) {
        ContratoDTO contrato = contratoService.criarContrato(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(contrato);
    }

    /**
     * Lista contratos com paginação
     */
    @GetMapping
    @PreAuthorize("hasPermission(null, 'CONTRATOS')")
    public ResponseEntity<Page<ContratoDTO>> listarTodos(
            @PageableDefault(size = 10, sort = "dataCriacao", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
        Page<ContratoDTO> contratos = contratoService.listarTodos(pageable);
        return ResponseEntity.ok(contratos);
    }

    /**
     * Busca contrato por ID
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CONTRATOS')")
    public ResponseEntity<ContratoDTO> buscarPorId(@PathVariable Long id) {
        ContratoDTO contrato = contratoService.buscarPorId(id);
        return ResponseEntity.ok(contrato);
    }

    /**
     * Busca contratos por cliente
     */
    @GetMapping("/cliente/{clienteId}")
    @PreAuthorize("hasPermission(null, 'CONTRATOS')")
    public ResponseEntity<Page<ContratoDTO>> buscarPorCliente(
            @PathVariable Long clienteId,
            @PageableDefault(size = 10) Pageable pageable) {
        Page<ContratoDTO> contratos = contratoService.buscarPorCliente(clienteId, pageable);
        return ResponseEntity.ok(contratos);
    }

    /**
     * Busca contratos por status
     */
    @GetMapping("/status/{status}")
    @PreAuthorize("hasPermission(null, 'CONTRATOS')")
    public ResponseEntity<Page<ContratoDTO>> buscarPorStatus(
            @PathVariable Contrato.StatusContrato status,
            @PageableDefault(size = 10) Pageable pageable) {
        Page<ContratoDTO> contratos = contratoService.buscarPorStatus(status, pageable);
        return ResponseEntity.ok(contratos);
    }

    /**
     * Busca contratos com filtros (igual ao Asaas)
     */
    @GetMapping("/filtros")
    @PreAuthorize("hasPermission(null, 'CONTRATOS')")
    public ResponseEntity<Page<ContratoDTO>> buscarComFiltros(
            @RequestParam(required = false) Long clienteId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String termo,
            @RequestParam(required = false) String billingType,
            @RequestParam(required = false) String dueDateGe,
            @RequestParam(required = false) String dueDateLe,
            @RequestParam(required = false) String paymentDateGe,
            @RequestParam(required = false) String paymentDateLe,
            @PageableDefault(size = 10) Pageable pageable) {
        Page<ContratoDTO> contratos = contratoService.buscarComFiltros(
                clienteId, status, termo, billingType, dueDateGe, dueDateLe, paymentDateGe, paymentDateLe, pageable);
        return ResponseEntity.ok(contratos);
    }

    /**
     * Remove contrato (soft delete)
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CONTRATOS')")
    public ResponseEntity<Void> removerContrato(@PathVariable Long id) {
        contratoService.removerContrato(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Sincroniza status de um contrato com o Asaas
     */
    @PostMapping("/{id}/sincronizar")
    @PreAuthorize("hasPermission(null, 'CONTRATOS')")
    public ResponseEntity<ContratoDTO> sincronizarStatusComAsaas(@PathVariable Long id) {
        ContratoDTO contrato = contratoService.sincronizarStatusComAsaas(id);
        return ResponseEntity.ok(contrato);
    }

    /**
     * Importa contratos do Asaas para o banco de dados
     * Útil para sincronizar dados quando o banco está vazio
     */
    @PostMapping("/importar-asaas")
    @PreAuthorize("hasPermission(null, 'CONTRATOS')")
    public ResponseEntity<Map<String, Object>> importarContratosDoAsaas() {
        int contratosImportados = contratoService.importarContratosDoAsaas();
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("contratosImportados", contratosImportados);
        response.put("mensagem", String.format("%d contratos importados com sucesso do Asaas", contratosImportados));
        return ResponseEntity.ok(response);
    }

    /**
     * Retorna totais por categoria de contratos
     */
    @GetMapping("/totais-categorias")
    @PreAuthorize("hasPermission(null, 'CONTRATOS')")
    public ResponseEntity<Map<String, Object>> getTotaisPorCategoria() {
        Map<String, Object> totais = contratoService.getTotaisPorCategoria();
        return ResponseEntity.ok(totais);
    }
}

