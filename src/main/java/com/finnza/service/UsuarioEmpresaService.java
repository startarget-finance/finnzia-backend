package com.finnza.service;

import com.finnza.domain.entity.EmpresaUsuario;
import com.finnza.domain.entity.Usuario;
import com.finnza.dto.EmpresaUsuarioDTO;
import com.finnza.repository.EmpresaUsuarioRepository;
import com.finnza.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Serviço para gerenciamento de permissões de empresa por usuário
 * 
 * Responsabilidades:
 * - Atribuir/remover empresas de usuários
 * - Validar permissões
 * - Gerenciar empresa padrão
 * - Auditoria de mudanças
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class UsuarioEmpresaService {

    private final EmpresaUsuarioRepository empresaUsuarioRepository;
    private final UsuarioRepository usuarioRepository;

    /**
     * Obtém todas as empresas que um usuário tem acesso (ativas)
     */
    @Transactional(readOnly = true)
    public List<EmpresaUsuarioDTO> obterEmpresasDoUsuario(Long usuarioId) {
        log.info("Consultando empresas do usuário ID: {}", usuarioId);
        
        Usuario usuario = validarUsuarioExiste(usuarioId);
        
        List<EmpresaUsuario> empresas = empresaUsuarioRepository.findAllByUsuarioId(usuarioId);
        if (empresas.isEmpty()) {
            log.info("Usuário {} sem vínculos ativos em empresa_usuario; aplicando fallback single-tenant", usuarioId);
            return empresaFallbackSingleTenant(usuario)
                    .map(List::of)
                    .orElseGet(List::of);
        }

        log.info("Usuário {} tem {} empresas ativas", usuarioId, empresas.size());
        return empresas.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Obtém empresas do usuário com informações de inativas (auditoria)
     */
    @Transactional(readOnly = true)
    public List<EmpresaUsuarioDTO> obterEmpresasCompleto(Long usuarioId) {
        validarUsuarioExiste(usuarioId);
        
        return empresaUsuarioRepository.findAllByUsuarioIdIncludingInactive(usuarioId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Obtém empresa padrão do usuário
     */
    @Transactional(readOnly = true)
    public Optional<EmpresaUsuarioDTO> obterEmpresaPadrao(Long usuarioId) {
        Usuario usuario = validarUsuarioExiste(usuarioId);

        Optional<EmpresaUsuarioDTO> padraoReal = empresaUsuarioRepository.findEmpresaPadraoByUsuarioId(usuarioId)
                .map(this::toDTO);
        if (padraoReal.isPresent()) {
            return padraoReal;
        }

        // Fallback compatível com o modelo "1 usuário = 1 empresa".
        return empresaFallbackSingleTenant(usuario);
    }

    /**
     * Verifica se usuário tem acesso a uma empresa
     */
    @Transactional(readOnly = true)
    public boolean temAcesso(Long usuarioId, Integer idEmpresa) {
        if (usuarioId == null || idEmpresa == null || idEmpresa <= 0) {
            return false;
        }
        return empresaUsuarioRepository.temAcesso(usuarioId, idEmpresa);
    }

    /**
     * Atribui acesso a uma nova empresa ao usuário
     * 
     * Validações:
     * - Usuário deve existir
     * - Empresa deve ser válida no BOMControle
     * - Não pode duplicar acesso existente
     */
    public EmpresaUsuarioDTO atribuirEmpresa(Long usuarioId, Integer idEmpresa, String nomeEmpresa, Boolean padrao) {
        log.info("Atribuindo empresa {} ao usuário {}", idEmpresa, usuarioId);
        
        Usuario usuario = validarUsuarioExiste(usuarioId);
        
        if (idEmpresa == null || idEmpresa <= 0) {
            throw new IllegalArgumentException("ID da empresa inválido");
        }
        
        // Verifica se empresa já existe
        if (empresaUsuarioRepository.findByUsuarioIdAndIdEmpresa(usuarioId, idEmpresa).isPresent()) {
            log.warn("Usuário {} já tem acesso à empresa {}", usuarioId, idEmpresa);
            throw new IllegalArgumentException("Usuário já tem acesso a esta empresa");
        }
        
        // Se nomeEmpresa não foi informado, usa placeholder simples; em um ERP próprio,
        // o ideal é validar contra tabela de empresas do próprio sistema.
        if (nomeEmpresa == null || nomeEmpresa.isBlank()) {
            nomeEmpresa = "Empresa " + idEmpresa;
        }
        
        // Lógica automática de empresa padrão
        boolean deveSePadrao = false;
        
        if (padrao != null && padrao) {
            // Admin setou explicitamente como padrão
            deveSePadrao = true;
            empresaUsuarioRepository.findEmpresaPadraoByUsuarioId(usuarioId)
                    .ifPresent(eu -> eu.removerDePadrao());
        } else if (padrao == null) {
            // Padrão não foi especificado: marque como padrão se for a primeira empresa
            List<EmpresaUsuario> empresasAtuais = empresaUsuarioRepository.findAllByUsuarioId(usuarioId);
            boolean temPadraoDefinida = empresasAtuais.stream().anyMatch(EmpresaUsuario::getPadrao);
            
            if (empresasAtuais.isEmpty() || !temPadraoDefinida) {
                deveSePadrao = true;
                log.debug("Primeira empresa para usuário {} - marcada automaticamente como padrão", usuarioId);
            }
        }
        
        EmpresaUsuario novaAtribuicao = EmpresaUsuario.builder()
                .usuario(usuario)
                .idEmpresa(idEmpresa)
                .nomeEmpresa(nomeEmpresa)
                .padrao(deveSePadrao)
                .ativo(true)
                .build();
        
        EmpresaUsuario salva = empresaUsuarioRepository.save(novaAtribuicao);
        log.info("✅ Empresa {} atribuída ao usuário {} (padrão: {})", idEmpresa, usuarioId, deveSePadrao);
        
        return toDTO(salva);
    }

    /**
     * Remove acesso a uma empresa (soft delete)
     */
    public void removerAcessoEmpresa(Long usuarioId, Integer idEmpresa, String motivo, String removidoPor) {
        log.info("Removendo acesso da empresa {} do usuário {}", idEmpresa, usuarioId);
        
        validarUsuarioExiste(usuarioId);
        
        EmpresaUsuario empresa = empresaUsuarioRepository.findByUsuarioIdAndIdEmpresa(usuarioId, idEmpresa)
                .orElseThrow(() -> new IllegalArgumentException("Acesso não encontrado"));
        
        if (motivo == null || motivo.isBlank()) {
            motivo = "Removido pelo sistema";
        }
        
        if (removidoPor == null || removidoPor.isBlank()) {
            removidoPor = "SISTEMA";
        }
        
        empresa.remover(removidoPor, motivo);
        empresaUsuarioRepository.save(empresa);
        
        log.info("✅ Acesso à empresa {} removido do usuário {}", idEmpresa, usuarioId);
    }

    /**
     * Desativa acesso a todas as empresas de um usuário
     * Usado quando usuário é deletado ou status inativado
     */
    public int desativarTodasEmpresasDoUsuario(Long usuarioId, String motivo) {
        log.warn("Desativando TODAS as empresas do usuário {}", usuarioId);
        
        validarUsuarioExiste(usuarioId);
        
        int qtdDesativadas = empresaUsuarioRepository.desativarTodasEmpresasDoUsuario(
                usuarioId, 
                "SISTEMA", 
                motivo != null ? motivo : "Usuário inativado"
        );
        
        log.info("✅ {} empresas desativadas do usuário {}", qtdDesativadas, usuarioId);
        return qtdDesativadas;
    }

    /**
     * Define empresa padrão do usuário
     * (a empresa padrão é usada quando nenhuma é selecionada)
     */
    public void definirEmpresaPadrao(Long usuarioId, Integer idEmpresa) {
        log.info("Definindo empresa {} como padrão para usuário {}", idEmpresa, usuarioId);
        
        validarUsuarioExiste(usuarioId);
        
        // Remove padrão anterior
        empresaUsuarioRepository.findEmpresaPadraoByUsuarioId(usuarioId)
                .ifPresent(eu -> {
                    eu.removerDePadrao();
                    empresaUsuarioRepository.save(eu);
                });
        
        // Define nova padrão
        EmpresaUsuario empresa = empresaUsuarioRepository.findByUsuarioIdAndIdEmpresa(usuarioId, idEmpresa)
                .orElseThrow(() -> new IllegalArgumentException("Acesso não encontrado"));
        
        empresa.definirComoPadrao();
        empresaUsuarioRepository.save(empresa);
        log.info("✅ Empresa {} agora é padrão para usuário {}", idEmpresa, usuarioId);
    }

    /**
     * Atualização em bulk: recebe array de IDs de empresas e atualiza acesso
     * 
     * Lógica:
     * 1. Remove acesso às empresas que não estão no array
     * 2. Adiciona acesso às empresas novas
     * 3. Define empresa padrão se informado
     */
    public List<EmpresaUsuarioDTO> atualizarEmpresasDoUsuario(Long usuarioId, Integer[] idEmpresas, Integer idEmpresaPadrao) {
        log.info("Atualizando empresas do usuário {} - {} empresas", usuarioId, idEmpresas.length);
        
        Usuario usuario = validarUsuarioExiste(usuarioId);
        
        // Obtém empresas atuais (incluindo inativas)
        List<EmpresaUsuario> empresasAtuais = empresaUsuarioRepository.findAllByUsuarioIdIncludingInactive(usuarioId);
        Set<Integer> idsAtuais = empresasAtuais.stream()
                .map(EmpresaUsuario::getIdEmpresa)
                .collect(Collectors.toSet());
        
        Set<Integer> idsNovos = new HashSet<>(Arrays.asList(idEmpresas));
        
        // Remove empresas que não estão no novo array
        idsAtuais.forEach(idEmpresa -> {
            if (!idsNovos.contains(idEmpresa)) {
                log.debug("Removendo acesso empresa {}", idEmpresa);
                empresaUsuarioRepository.removerAcessoEmpresa(usuarioId, idEmpresa, "SISTEMA", "Atualização em bulk");
            }
        });
        
        // Monta um cache de nomes a partir do que já existe no banco (EmpresaUsuario).
        // Em um ERP próprio, o ideal é existir uma tabela de Empresas e buscarmos o nome por lá.
        Map<Integer, String> empresasCache = empresasAtuais.stream()
                .filter(eu -> eu.getIdEmpresa() != null)
                .filter(eu -> eu.getNomeEmpresa() != null && !eu.getNomeEmpresa().isBlank())
                .collect(Collectors.toMap(
                        EmpresaUsuario::getIdEmpresa,
                        EmpresaUsuario::getNomeEmpresa,
                        (a, b) -> a
                ));
        
        // Adiciona/ativa empresas novas
        for (Integer idEmpresa : idEmpresas) {
            // Se empresa já existe, ativa (restaura) se estava inativa
            Optional<EmpresaUsuario> existente = empresaUsuarioRepository.findByUsuarioIdAndIdEmpresaIncludingInactive(usuarioId, idEmpresa);
            
            if (existente.isPresent()) {
                EmpresaUsuario eu = existente.get();
                // Se estava inativa, restaura
                if (!eu.getAtivo()) {
                    log.debug("Restaurando acesso empresa {} para usuário {}", idEmpresa, usuarioId);
                    eu.restaurar();
                    empresaUsuarioRepository.save(eu);
                } else {
                    log.debug("Empresa {} já está ativa para usuário {}", idEmpresa, usuarioId);
                }
            } else {
                // Empresa não existe, adiciona
                log.debug("Adicionando acesso empresa {}", idEmpresa);
                String nomeEmpresa = empresasCache.getOrDefault(idEmpresa, "Empresa " + idEmpresa);
                atribuirEmpresaDirect(usuario, idEmpresa, nomeEmpresa, false);
            }
        }
        
        // Define empresa padrão (com lógica automática)
        if (idEmpresaPadrao != null && idEmpresaPadrao > 0) {
            // Admin forneceu explicitamente uma empresa padrão
            definirEmpresaPadrao(usuarioId, idEmpresaPadrao);
            log.info("✅ Empresa padrão definida explicitamente: {}", idEmpresaPadrao);
        } else {
            // Lógica automática: se usuário não tem empresa padrão, marque a primeira como padrão
            List<EmpresaUsuario> empresasAtualizadas = empresaUsuarioRepository.findAllByUsuarioId(usuarioId);
            boolean temPadraoDefinida = empresasAtualizadas.stream().anyMatch(EmpresaUsuario::getPadrao);
            
            if (!temPadraoDefinida && !empresasAtualizadas.isEmpty()) {
                // Marca a primeira empresa como padrão automaticamente
                Integer primeiraEmpresa = empresasAtualizadas.get(0).getIdEmpresa();
                definirEmpresaPadrao(usuarioId, primeiraEmpresa);
                log.info("✅ Primeira empresa ({}) marcada automaticamente como padrão para usuário {}", primeiraEmpresa, usuarioId);
            }
        }
        
        log.info("✅ Empresas do usuário {} atualizadas com sucesso", usuarioId);
        return obterEmpresasDoUsuario(usuarioId);
    }

    /**
     * Atribui empresa sem validação de duplicação (para uso interno)
     */
    private EmpresaUsuarioDTO atribuirEmpresaDirect(Usuario usuario, Integer idEmpresa, String nomeEmpresa, Boolean padrao) {
        EmpresaUsuario novaAtribuicao = EmpresaUsuario.builder()
                .usuario(usuario)
                .idEmpresa(idEmpresa)
                .nomeEmpresa(nomeEmpresa)
                .padrao(padrao != null && padrao)
                .ativo(true)
                .build();
        
        EmpresaUsuario salva = empresaUsuarioRepository.save(novaAtribuicao);
        log.info("✅ Empresa {} atribuída ao usuário {}", idEmpresa, usuario.getId());
        
        return toDTO(salva);
    }


    /**
     * Verifica se um usuário tem ao menos uma empresa ativa
     */
    @Transactional(readOnly = true)
    public boolean temEmpresasAtivas(Long usuarioId) {
        Usuario usuario = validarUsuarioExiste(usuarioId);
        boolean temEmpresas = empresaUsuarioRepository.usuarioTemEmpresasAtivas(usuarioId);
        return temEmpresas || empresaFallbackSingleTenant(usuario).isPresent();
    }

    /**
     * Conta quantas empresas um usuário tem acesso
     */
    @Transactional(readOnly = true)
    public long contarEmpresasAtivas(Long usuarioId) {
        Usuario usuario = validarUsuarioExiste(usuarioId);
        long total = empresaUsuarioRepository.countAtivasByUsuarioId(usuarioId);
        if (total > 0) {
            return total;
        }
        return empresaFallbackSingleTenant(usuario).isPresent() ? 1L : 0L;
    }

    // ==================== Helpers ====================

    /**
     * Valida se um usuário (por email) tem acesso a uma empresa
     * Usado para validação de segurança no controller
     * Admin tem acesso a todas as empresas
     * 
     * @param email Email do usuário
     * @param idEmpresa ID da empresa
     * @return true se tem acesso, false caso contrário
     */
    @Transactional(readOnly = true)
    public boolean validarAcessoUsuarioEmpresa(String email, Integer idEmpresa) {
        if (email == null || email.isBlank() || idEmpresa == null || idEmpresa <= 0) {
            log.warn("⚠️ Validação de acesso com parâmetros inválidos: email={}, idEmpresa={}", email, idEmpresa);
            return false;
        }

        // Buscar usuário por email
        Optional<Usuario> usuarioOpt = usuarioRepository.findByEmail(email);
        if (usuarioOpt.isEmpty()) {
            log.warn("⚠️ Usuário não encontrado: {}", email);
            return false;
        }

        Usuario usuario = usuarioOpt.get();

        // Admin tem acesso a todas as empresas
        if (usuario.getRole() == Usuario.Role.ADMIN) {
            log.debug("✅ Admin {} tem acesso a todas as empresas", email);
            return true;
        }

        // Cliente: verifica se tem acesso específico à empresa
        boolean temAcesso = temAcesso(usuario.getId(), idEmpresa);
        
        if (temAcesso) {
            log.debug("✅ Usuário {} tem acesso à empresa {}", email, idEmpresa);
        } else {
            // Listar as empresas que o usuário TEM acesso (para debugar)
            List<EmpresaUsuario> empresasDoUsuario = empresaUsuarioRepository.findAllByUsuarioId(usuario.getId());
            String empresasDisponiveis = empresasDoUsuario.stream()
                .map(eu -> eu.getIdEmpresa() + " (" + eu.getNomeEmpresa() + ")")
                .collect(Collectors.joining(", "));
            
            if (empresasDoUsuario.isEmpty()) {
                log.warn("🔒 Usuário {} tentou acessar empresa {} mas NÃO tem NENHUMA empresa associada", email, idEmpresa);
            } else {
                log.warn("🔒 Usuário {} tentou acessar empresa {} mas tem acesso apenas a: [{}]", email, idEmpresa, empresasDisponiveis);
            }
        }
        
        return temAcesso;
    }

    /**
     * Verifica se usuário é admin (por email)
     * Retorna true se é admin, false caso contrário
     */
    @Transactional(readOnly = true)
    public boolean isAdmin(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }

        Optional<Usuario> usuarioOpt = usuarioRepository.findByEmail(email);
        if (usuarioOpt.isEmpty()) {
            return false;
        }

        return usuarioOpt.get().getRole() == Usuario.Role.ADMIN;
    }

    /**
     * Informa se o usuário possui ao menos uma empresa ativa vinculada.
     */
    @Transactional(readOnly = true)
    public boolean usuarioTemEmpresasAtivasPorEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        Optional<Usuario> usuarioOpt = usuarioRepository.findByEmail(email);
        if (usuarioOpt.isEmpty()) {
            return false;
        }
        return empresaUsuarioRepository.usuarioTemEmpresasAtivas(usuarioOpt.get().getId());
    }

    /**
     * Obtém o ID da empresa padrão do usuário pelo email.
     */
    @Transactional(readOnly = true)
    public Optional<Integer> obterIdEmpresaPadraoPorEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        Optional<Usuario> usuarioOpt = usuarioRepository.findByEmail(email);
        if (usuarioOpt.isEmpty()) {
            return Optional.empty();
        }
        return empresaUsuarioRepository.findEmpresaPadraoByUsuarioId(usuarioOpt.get().getId())
                .map(EmpresaUsuario::getIdEmpresa)
                .filter(id -> id != null && id > 0);
    }

    /**
     * Resolve o contexto de empresa por email com fallback para single-tenant:
     * 1) empresa padrão; 2) primeira empresa ativa; 3) id do próprio usuário.
     */
    @Transactional(readOnly = true)
    public Optional<Integer> obterIdEmpresaContextoPorEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }

        Optional<Usuario> usuarioOpt = usuarioRepository.findByEmail(email);
        if (usuarioOpt.isEmpty()) {
            return Optional.empty();
        }

        Usuario usuario = usuarioOpt.get();

        Optional<Integer> empresaPadrao = empresaUsuarioRepository.findEmpresaPadraoByUsuarioId(usuario.getId())
                .map(EmpresaUsuario::getIdEmpresa)
                .filter(id -> id != null && id > 0);
        if (empresaPadrao.isPresent()) {
            return empresaPadrao;
        }

        Optional<Integer> primeiraAtiva = empresaUsuarioRepository.findAllByUsuarioId(usuario.getId()).stream()
                .map(EmpresaUsuario::getIdEmpresa)
                .filter(id -> id != null && id > 0)
                .findFirst();
        if (primeiraAtiva.isPresent()) {
            return primeiraAtiva;
        }

        // Fluxo single-tenant: cada usuário corresponde à sua própria empresa.
        long usuarioId = usuario.getId() != null ? usuario.getId() : -1L;
        if (usuarioId > 0 && usuarioId <= Integer.MAX_VALUE) {
            return Optional.of((int) usuarioId);
        }

        return Optional.empty();
    }


    private Usuario validarUsuarioExiste(Long usuarioId) {
        if (usuarioId == null || usuarioId <= 0) {
            throw new IllegalArgumentException("ID do usuário inválido");
        }
        
        return usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> {
                    log.error("Usuário não encontrado: {}", usuarioId);
                    return new IllegalArgumentException("Usuário não encontrado");
                });
    }

    private Optional<EmpresaUsuarioDTO> empresaFallbackSingleTenant(Usuario usuario) {
        if (usuario == null || usuario.getId() == null) {
            return Optional.empty();
        }
        long usuarioId = usuario.getId();
        if (usuarioId <= 0 || usuarioId > Integer.MAX_VALUE) {
            return Optional.empty();
        }

        String nomeEmpresa = usuario.getNome() != null && !usuario.getNome().isBlank()
                ? usuario.getNome().trim()
                : (usuario.getEmail() != null && !usuario.getEmail().isBlank()
                    ? usuario.getEmail().trim()
                    : "Empresa " + usuarioId);

        return Optional.of(EmpresaUsuarioDTO.builder()
                .id(null)
                .idEmpresa((int) usuarioId)
                .nomeEmpresa(nomeEmpresa)
                .padrao(true)
                .ativo(true)
                .build());
    }

    private EmpresaUsuarioDTO toDTO(EmpresaUsuario entity) {
        return EmpresaUsuarioDTO.builder()
                .id(entity.getId())
                .idEmpresa(entity.getIdEmpresa())
                .nomeEmpresa(entity.getNomeEmpresa())
                .padrao(entity.getPadrao())
                .ativo(entity.getAtivo())
                .dataCriacao(entity.getDataCriacao())
                .dataAtualizacao(entity.getDataAtualizacao())
                .removidoPor(entity.getRemovidoPor())
                .motivoRemocao(entity.getMotivoRemocao())
                .dataRemocao(entity.getDataRemocao())
                .build();
    }
}
