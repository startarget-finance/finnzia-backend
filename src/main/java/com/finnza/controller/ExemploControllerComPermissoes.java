package com.finnza.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * EXEMPLO de Controller com proteção por permissões
 * 
 * Este arquivo serve como referência de como aplicar permissões nos controllers.
 * Você pode deletar este arquivo após entender o padrão.
 */
@RestController
@RequestMapping("/api/exemplo")
@CrossOrigin(origins = "*")
public class ExemploControllerComPermissoes {

    /**
     * Exemplo 1: Proteger endpoint com permissão específica
     * Só usuários com permissão DASHBOARD podem acessar
     */
    @GetMapping("/dashboard")
    @PreAuthorize("hasPermission(null, 'DASHBOARD')")
    public ResponseEntity<String> getDashboard() {
        return ResponseEntity.ok("Dados do dashboard");
    }

    /**
     * Exemplo 2: Proteger endpoint com permissão RELATORIO
     */
    @GetMapping("/relatorio")
    @PreAuthorize("hasPermission(null, 'RELATORIO')")
    public ResponseEntity<String> getRelatorio() {
        return ResponseEntity.ok("Dados do relatório");
    }

    /**
     * Exemplo 3: Proteger endpoint com permissão MOVIMENTACOES
     */
    @GetMapping("/movimentacoes")
    @PreAuthorize("hasPermission(null, 'MOVIMENTACOES')")
    public ResponseEntity<String> getMovimentacoes() {
        return ResponseEntity.ok("Dados de movimentações");
    }

    /**
     * Exemplo 4: Proteger endpoint com permissão FLUXO_CAIXA
     */
    @GetMapping("/fluxo-caixa")
    @PreAuthorize("hasPermission(null, 'FLUXO_CAIXA')")
    public ResponseEntity<String> getFluxoCaixa() {
        return ResponseEntity.ok("Dados de fluxo de caixa");
    }

    /**
     * Exemplo 5: Proteger endpoint com permissão CONTRATOS
     */
    @GetMapping("/contratos")
    @PreAuthorize("hasPermission(null, 'CONTRATOS')")
    public ResponseEntity<String> getContratos() {
        return ResponseEntity.ok("Dados de contratos");
    }

    /**
     * Exemplo 6: Proteger endpoint com permissão CHAT
     */
    @GetMapping("/chat")
    @PreAuthorize("hasPermission(null, 'CHAT')")
    public ResponseEntity<String> getChat() {
        return ResponseEntity.ok("Dados do chat");
    }

    /**
     * Exemplo 7: Proteger endpoint com permissão ASSINATURA
     */
    @GetMapping("/assinatura")
    @PreAuthorize("hasPermission(null, 'ASSINATURA')")
    public ResponseEntity<String> getAssinatura() {
        return ResponseEntity.ok("Dados de assinatura");
    }

    /**
     * Exemplo 8: Proteger endpoint com permissão GERENCIAR_ACESSOS
     * (ou usar hasRole('ADMIN') como já está no UsuarioController)
     */
    @GetMapping("/gerenciar-acessos")
    @PreAuthorize("hasPermission(null, 'GERENCIAR_ACESSOS')")
    public ResponseEntity<String> getGerenciarAcessos() {
        return ResponseEntity.ok("Dados de gerenciar acessos");
    }

    /**
     * Exemplo 9: Combinar permissões (usuário precisa ter AMBAS)
     */
    @GetMapping("/combinado")
    @PreAuthorize("hasPermission(null, 'DASHBOARD') and hasPermission(null, 'RELATORIO')")
    public ResponseEntity<String> getCombinado() {
        return ResponseEntity.ok("Dados que requerem múltiplas permissões");
    }

    /**
     * Exemplo 10: Permitir ADMIN ou usuário com permissão específica
     */
    @GetMapping("/admin-ou-permissao")
    @PreAuthorize("hasRole('ADMIN') or hasPermission(null, 'DASHBOARD')")
    public ResponseEntity<String> getAdminOuPermissao() {
        return ResponseEntity.ok("Acessível por admin ou quem tem permissão");
    }
}

