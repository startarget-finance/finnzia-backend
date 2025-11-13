package com.finnza.controller;

import com.finnza.dto.request.AlterarSenhaRequest;
import com.finnza.dto.request.AtualizarPermissoesRequest;
import com.finnza.dto.request.AtualizarPerfilRequest;
import com.finnza.dto.request.AtualizarUsuarioRequest;
import com.finnza.dto.request.CriarUsuarioRequest;
import com.finnza.dto.request.UsuarioFiltroRequest;
import com.finnza.dto.response.UsuarioDTO;
import com.finnza.service.UsuarioService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;


/**
 * Controller para gerenciamento de usuários
 */
@RestController
@RequestMapping("/api/usuarios")
@CrossOrigin(origins = "*")
public class UsuarioController {

    @Autowired
    private UsuarioService usuarioService;

    /**
     * Cria o primeiro usuário admin (público, sem autenticação)
     * Só funciona se não houver usuários no sistema
     */
    @PostMapping("/primeiro-admin")
    public ResponseEntity<UsuarioDTO> criarPrimeiroAdmin(@Valid @RequestBody CriarUsuarioRequest request) {
        UsuarioDTO usuario = usuarioService.criarPrimeiroAdmin(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(usuario);
    }

    /**
     * Cria um novo usuário
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UsuarioDTO> criarUsuario(@Valid @RequestBody CriarUsuarioRequest request) {
        UsuarioDTO usuario = usuarioService.criarUsuario(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(usuario);
    }

    /**
     * Lista usuários com paginação (obrigatório)
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<UsuarioDTO>> listarTodos(
            @PageableDefault(size = 10, sort = "nome") Pageable pageable) {
        Page<UsuarioDTO> usuarios = usuarioService.listarTodos(pageable);
        return ResponseEntity.ok(usuarios);
    }

    /**
     * Lista usuários com filtros avançados
     */
    @PostMapping("/filtros")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<UsuarioDTO>> listarComFiltros(
            @Valid @RequestBody UsuarioFiltroRequest filtros) {
        Page<UsuarioDTO> usuarios = usuarioService.listarComFiltros(filtros);
        return ResponseEntity.ok(usuarios);
    }

    /**
     * Busca usuário por ID
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UsuarioDTO> buscarPorId(@PathVariable Long id) {
        UsuarioDTO usuario = usuarioService.buscarPorId(id);
        return ResponseEntity.ok(usuario);
    }

    /**
     * Atualiza um usuário
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UsuarioDTO> atualizarUsuario(
            @PathVariable Long id,
            @Valid @RequestBody AtualizarUsuarioRequest request) {
        UsuarioDTO usuario = usuarioService.atualizarUsuario(id, request);
        return ResponseEntity.ok(usuario);
    }

    /**
     * Atualiza permissões de um usuário
     */
    @PutMapping("/{id}/permissoes")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UsuarioDTO> atualizarPermissoes(
            @PathVariable Long id,
            @Valid @RequestBody AtualizarPermissoesRequest request) {
        UsuarioDTO usuario = usuarioService.atualizarPermissoes(id, request);
        return ResponseEntity.ok(usuario);
    }

    /**
     * Remove um usuário (soft delete)
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> removerUsuario(@PathVariable Long id) {
        usuarioService.removerUsuario(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Restaura um usuário deletado
     */
    @PutMapping("/{id}/restaurar")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UsuarioDTO> restaurarUsuario(@PathVariable Long id) {
        UsuarioDTO usuario = usuarioService.restaurarUsuario(id);
        return ResponseEntity.ok(usuario);
    }

    /**
     * Remove permanentemente um usuário (hard delete)
     */
    @DeleteMapping("/{id}/permanente")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> removerPermanentemente(@PathVariable Long id) {
        usuarioService.removerPermanentemente(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Busca o perfil do usuário logado
     */
    @GetMapping("/me")
    public ResponseEntity<UsuarioDTO> buscarMeuPerfil() {
        UsuarioDTO usuario = usuarioService.buscarMeuPerfil();
        return ResponseEntity.ok(usuario);
    }

    /**
     * Atualiza o perfil do usuário logado
     */
    @PutMapping("/me")
    public ResponseEntity<UsuarioDTO> atualizarMeuPerfil(@Valid @RequestBody AtualizarPerfilRequest request) {
        UsuarioDTO usuario = usuarioService.atualizarMeuPerfil(request);
        return ResponseEntity.ok(usuario);
    }

    /**
     * Altera a senha do usuário logado
     */
    @PutMapping("/me/senha")
    public ResponseEntity<Void> alterarMinhaSenha(@Valid @RequestBody AlterarSenhaRequest request) {
        usuarioService.alterarMinhaSenha(request);
        return ResponseEntity.ok().build();
    }
}

