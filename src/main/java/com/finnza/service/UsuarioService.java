package com.finnza.service;

import com.finnza.domain.entity.Permissao;
import com.finnza.domain.entity.Usuario;
import com.finnza.dto.request.AlterarSenhaRequest;
import com.finnza.dto.request.AtualizarPermissoesRequest;
import com.finnza.dto.request.AtualizarPerfilRequest;
import com.finnza.dto.request.AtualizarUsuarioRequest;
import com.finnza.dto.request.CriarUsuarioRequest;
import com.finnza.dto.request.UsuarioFiltroRequest;
import com.finnza.dto.response.UsuarioDTO;
import com.finnza.repository.PermissaoRepository;
import com.finnza.repository.UsuarioRepository;
import com.finnza.repository.specification.UsuarioSpecification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Serviço para gerenciamento de usuários
 */
@Service
@Transactional
public class UsuarioService {

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private PermissaoRepository permissaoRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * Cria um novo usuário
     */
    public UsuarioDTO criarUsuario(CriarUsuarioRequest request) {
        if (usuarioRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email já está em uso");
        }

        Usuario usuario = Usuario.builder()
                .nome(request.getNome())
                .email(request.getEmail())
                .senha(passwordEncoder.encode(request.getSenha()))
                .role(request.getRole())
                .status(Usuario.StatusUsuario.ATIVO)
                .omieAppKey(request.getOmieAppKey())
                .omieAppSecret(request.getOmieAppSecret())
                .build();

        // Criar permissões padrão baseadas no role
        criarPermissoesPadrao(usuario);

        usuario = usuarioRepository.save(usuario);

        return UsuarioDTO.fromEntity(usuario);
    }

    /**
     * Cria o primeiro usuário admin (sem autenticação)
     * Só funciona se não houver usuários no sistema
     */
    public UsuarioDTO criarPrimeiroAdmin(CriarUsuarioRequest request) {
        // Verificar se já existe algum usuário
        long totalUsuarios = usuarioRepository.count();
        if (totalUsuarios > 0) {
            throw new RuntimeException("Já existem usuários no sistema. Use o endpoint de criação normal com autenticação.");
        }

        if (usuarioRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email já está em uso");
        }

        // Criar usuário com role ADMIN forçado
        Usuario usuario = Usuario.builder()
                .nome(request.getNome())
                .email(request.getEmail())
                .senha(passwordEncoder.encode(request.getSenha()))
                .role(Usuario.Role.ADMIN) // Sempre ADMIN para o primeiro usuário
                .status(Usuario.StatusUsuario.ATIVO)
                .build();

        // Salvar usuário primeiro para ter ID
        usuario = usuarioRepository.save(usuario);

        // Criar permissões padrão baseadas no role
        criarPermissoesPadrao(usuario);

        // Salvar novamente com permissões
        usuario = usuarioRepository.save(usuario);

        return UsuarioDTO.fromEntity(usuario);
    }

    /**
     * Busca usuário por ID
     */
    @Transactional(readOnly = true)
    public UsuarioDTO buscarPorId(Long id) {
        Usuario usuario = usuarioRepository.findByIdWithPermissoes(id)
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));
        return UsuarioDTO.fromEntity(usuario);
    }

    /**
     * Lista usuários com paginação (apenas não deletados)
     * Valida tamanho máximo de página para evitar sobrecarga
     */
    @Transactional(readOnly = true)
    public Page<UsuarioDTO> listarTodos(Pageable pageable) {
        // Valida e limita tamanho máximo de página (máximo 100 registros)
        int pageSize = Math.min(pageable.getPageSize(), 100);
        int pageNumber = pageable.getPageNumber();
        
        // Cria novo Pageable com tamanho validado
        Pageable validatedPageable = PageRequest.of(
            pageNumber,
            pageSize,
            pageable.getSort().isSorted() ? pageable.getSort() : Sort.by("nome")
        );
        
        Specification<Usuario> spec = UsuarioSpecification.comFiltros(
                UsuarioFiltroRequest.builder()
                        .incluirDeletados(false)
                        .build()
        );
        
        // Usa JOIN FETCH para evitar N+1 queries
        return usuarioRepository.findAll(spec, validatedPageable)
                .map(UsuarioDTO::fromEntity);
    }

    /**
     * Lista usuários com filtros e paginação
     * Valida tamanho máximo de página para evitar sobrecarga
     */
    @Transactional(readOnly = true)
    public Page<UsuarioDTO> listarComFiltros(UsuarioFiltroRequest filtros) {
        Specification<Usuario> spec = UsuarioSpecification.comFiltros(filtros);
        
        // Valida e limita tamanho máximo de página (máximo 100 registros)
        int pageSize = Math.min(filtros.getSize() != null ? filtros.getSize() : 10, 100);
        int pageNumber = filtros.getPage() != null ? filtros.getPage() : 0;
        
        // Configurar ordenação
        Sort sort = Sort.by(
                filtros.getSortDirection() != null && filtros.getSortDirection().equalsIgnoreCase("DESC") 
                        ? Sort.Direction.DESC 
                        : Sort.Direction.ASC,
                filtros.getSortBy() != null ? filtros.getSortBy() : "nome"
        );
        
        // Configurar paginação com tamanho validado
        Pageable pageable = PageRequest.of(
                pageNumber,
                pageSize,
                sort
        );
        
        return usuarioRepository.findAll(spec, pageable)
                .map(UsuarioDTO::fromEntity);
    }

    /**
     * Atualiza um usuário
     */
    public UsuarioDTO atualizarUsuario(Long id, AtualizarUsuarioRequest request) {
        Usuario usuario = usuarioRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));

        if (request.getNome() != null) {
            usuario.setNome(request.getNome());
        }
        if (request.getEmail() != null && !request.getEmail().equals(usuario.getEmail())) {
            if (usuarioRepository.existsByEmail(request.getEmail())) {
                throw new RuntimeException("Email já está em uso");
            }
            usuario.setEmail(request.getEmail());
        }
        if (request.getRole() != null) {
            usuario.setRole(request.getRole());
        }
        if (request.getStatus() != null) {
            usuario.setStatus(request.getStatus());
        }
        if (request.getOmieAppKey() != null) {
            usuario.setOmieAppKey(request.getOmieAppKey());
        }
        if (request.getOmieAppSecret() != null) {
            usuario.setOmieAppSecret(request.getOmieAppSecret());
        }

        usuario = usuarioRepository.save(usuario);
        return UsuarioDTO.fromEntity(usuario);
    }

    /**
     * Atualiza permissões de um usuário
     */
    public UsuarioDTO atualizarPermissoes(Long id, AtualizarPermissoesRequest request) {
        Usuario usuario = usuarioRepository.findByIdWithPermissoes(id)
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));

        // Mapa para rastrear permissões que devem existir
        Map<Permissao.Modulo, Boolean> permissoesDesejadas = new java.util.HashMap<>();
        for (Map.Entry<String, Boolean> entry : request.getPermissions().entrySet()) {
            Permissao.Modulo modulo = converterChaveParaModulo(entry.getKey());
            if (modulo != null) {
                permissoesDesejadas.put(modulo, entry.getValue());
            }
        }

        // Mapa das permissões existentes por módulo
        Map<Permissao.Modulo, Permissao> permissoesExistentes = new java.util.HashMap<>();
        for (Permissao permissao : usuario.getPermissoes()) {
            permissoesExistentes.put(permissao.getModulo(), permissao);
        }

        // Atualizar ou criar permissões
        for (Map.Entry<Permissao.Modulo, Boolean> entry : permissoesDesejadas.entrySet()) {
            Permissao.Modulo modulo = entry.getKey();
            Boolean habilitado = entry.getValue();

            if (permissoesExistentes.containsKey(modulo)) {
                // Atualizar permissão existente
                Permissao permissao = permissoesExistentes.get(modulo);
                permissao.setHabilitado(habilitado);
            } else {
                // Criar nova permissão
                Permissao permissao = Permissao.builder()
                        .usuario(usuario)
                        .modulo(modulo)
                        .habilitado(habilitado)
                        .build();
                usuario.adicionarPermissao(permissao);
            }
        }

        // Remover permissões que não estão mais na requisição
        java.util.Iterator<Permissao> iterator = usuario.getPermissoes().iterator();
        while (iterator.hasNext()) {
            Permissao permissao = iterator.next();
            if (!permissoesDesejadas.containsKey(permissao.getModulo())) {
                iterator.remove();
                permissaoRepository.delete(permissao);
            }
        }

        usuario = usuarioRepository.save(usuario);
        return UsuarioDTO.fromEntity(usuario);
    }

    /**
     * Remove um usuário (soft delete)
     */
    public void removerUsuario(Long id) {
        Usuario usuario = usuarioRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));
        
        if (usuario.isDeleted()) {
            throw new RuntimeException("Usuário já foi deletado");
        }
        
        usuario.softDelete();
        usuarioRepository.save(usuario);
    }

    /**
     * Restaura um usuário deletado
     */
    public UsuarioDTO restaurarUsuario(Long id) {
        Usuario usuario = usuarioRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));
        
        if (!usuario.isDeleted()) {
            throw new RuntimeException("Usuário não está deletado");
        }
        
        usuario.restaurar();
        usuario = usuarioRepository.save(usuario);
        return UsuarioDTO.fromEntity(usuario);
    }

    /**
     * Remove permanentemente um usuário (hard delete) - apenas para admin
     */
    public void removerPermanentemente(Long id) {
        Usuario usuario = usuarioRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));
        
        usuarioRepository.delete(usuario);
    }

    /**
     * Busca o perfil do usuário logado
     */
    @Transactional(readOnly = true)
    public UsuarioDTO buscarMeuPerfil() {
        String email = getEmailUsuarioLogado();
        Usuario usuario = usuarioRepository.findByEmailWithPermissoes(email)
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));
        return UsuarioDTO.fromEntity(usuario);
    }

    /**
     * Atualiza o perfil do usuário logado
     */
    public UsuarioDTO atualizarMeuPerfil(AtualizarPerfilRequest request) {
        String email = getEmailUsuarioLogado();
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));

        if (request.getNome() != null) {
            usuario.setNome(request.getNome());
        }

        usuario = usuarioRepository.save(usuario);
        return UsuarioDTO.fromEntity(usuario);
    }

    /**
     * Altera a senha do usuário logado
     */
    public void alterarMinhaSenha(AlterarSenhaRequest request) {
        String email = getEmailUsuarioLogado();
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));

        // Verificar senha atual
        if (!passwordEncoder.matches(request.getSenhaAtual(), usuario.getSenha())) {
            throw new RuntimeException("Senha atual incorreta");
        }

        // Atualizar senha
        usuario.setSenha(passwordEncoder.encode(request.getNovaSenha()));
        usuarioRepository.save(usuario);
    }

    /**
     * Obtém o email do usuário logado
     */
    private String getEmailUsuarioLogado() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("Usuário não autenticado");
        }
        return authentication.getName();
    }

    /**
     * Converte chave do frontend (camelCase) para enum Modulo
     */
    private Permissao.Modulo converterChaveParaModulo(String chave) {
        // Converte camelCase para SNAKE_CASE
        String moduloStr = chave;
        if (chave.equals("fluxoCaixa")) {
            moduloStr = "FLUXO_CAIXA";
        } else if (chave.equals("gerenciarAcessos")) {
            moduloStr = "GERENCIAR_ACESSOS";
        } else {
            moduloStr = chave.toUpperCase();
        }
        
        try {
            return Permissao.Modulo.valueOf(moduloStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Cria permissões padrão baseadas no role
     */
    private void criarPermissoesPadrao(Usuario usuario) {
        if (usuario.getRole() == Usuario.Role.ADMIN) {
            // Admin tem todas as permissões habilitadas
            for (Permissao.Modulo modulo : Permissao.Modulo.values()) {
                Permissao permissao = Permissao.builder()
                        .usuario(usuario)
                        .modulo(modulo)
                        .habilitado(true)
                        .build();
                usuario.adicionarPermissao(permissao);
            }
        } else {
            // Cliente tem permissões limitadas
            for (Permissao.Modulo modulo : Permissao.Modulo.values()) {
                boolean habilitado = modulo != Permissao.Modulo.GERENCIAR_ACESSOS;
                Permissao permissao = Permissao.builder()
                        .usuario(usuario)
                        .modulo(modulo)
                        .habilitado(habilitado)
                        .build();
                usuario.adicionarPermissao(permissao);
            }
        }
    }
}

