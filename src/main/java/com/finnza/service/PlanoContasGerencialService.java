package com.finnza.service;

import com.finnza.domain.entity.EmpresaUsuario;
import com.finnza.domain.entity.PlanoContasGerencial;
import com.finnza.domain.entity.Usuario;
import com.finnza.dto.request.PlanoContasGerencialRequest;
import com.finnza.dto.response.PlanoContasEmpresaNomeDTO;
import com.finnza.dto.response.PlanoContasGerencialDTO;
import com.finnza.repository.EmpresaUsuarioRepository;
import com.finnza.repository.PlanoContasGerencialRepository;
import com.finnza.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class PlanoContasGerencialService {

    private final PlanoContasGerencialRepository repository;
    private final UsuarioRepository usuarioRepository;
    private final EmpresaUsuarioRepository empresaUsuarioRepository;
    private final UsuarioEmpresaService usuarioEmpresaService;

    @Transactional(readOnly = true)
    public List<PlanoContasGerencialDTO> listar(String emailUsuario, Integer filtroIdEmpresa) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));

        List<PlanoContasGerencial> todos = repository.findAllByDeletedFalseOrderByNomeAsc();
        List<PlanoContasGerencial> visiveis = todos.stream()
                .filter(p -> usuarioPodeVerPlano(usuario, p))
                .filter(p -> passaFiltroEmpresa(p, filtroIdEmpresa))
                .collect(Collectors.toList());

        Map<Integer, String> nomes = carregarNomesEmpresas(visiveis);
        return visiveis.stream()
                .map(p -> toDto(p, nomes))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PlanoContasGerencialDTO buscar(String emailUsuario, Long id) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        PlanoContasGerencial p = repository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new IllegalArgumentException("Plano não encontrado"));
        if (!usuarioPodeVerPlano(usuario, p)) {
            throw new IllegalArgumentException("Sem permissão para este plano");
        }
        Map<Integer, String> nomes = carregarNomesEmpresas(List.of(p));
        return toDto(p, nomes);
    }

    public PlanoContasGerencialDTO criar(String emailUsuario, PlanoContasGerencialRequest req) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        Set<Integer> idsReq = Optional.ofNullable(req.getIdEmpresas()).orElseGet(HashSet::new);
        if (idsReq.isEmpty() && usuario.getRole() != Usuario.Role.ADMIN) {
            throw new IllegalArgumentException("Associe pelo menos uma empresa (apenas admin pode criar plano sem empresas).");
        }
        validarEmpresasNoRequest(emailUsuario, req.getIdEmpresas());

        PlanoContasGerencial p = PlanoContasGerencial.builder()
                .nome(req.getNome().trim())
                .idEmpresas(new HashSet<>(Optional.ofNullable(req.getIdEmpresas()).orElseGet(HashSet::new)))
                .padrao(Boolean.TRUE.equals(req.getPadrao()))
                .build();

        if (Boolean.TRUE.equals(p.getPadrao())) {
            desmarcarOutrosPadroes(null);
        }

        p = repository.save(p);
        Map<Integer, String> nomes = carregarNomesEmpresas(List.of(p));
        return toDto(p, nomes);
    }

    public PlanoContasGerencialDTO atualizar(String emailUsuario, Long id, PlanoContasGerencialRequest req) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        PlanoContasGerencial p = repository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new IllegalArgumentException("Plano não encontrado"));
        if (!usuarioPodeEditarPlano(usuario, p)) {
            throw new IllegalArgumentException("Sem permissão para editar este plano");
        }

        Set<Integer> idsNovos = new HashSet<>(Optional.ofNullable(req.getIdEmpresas()).orElseGet(HashSet::new));
        if (idsNovos.isEmpty() && usuario.getRole() != Usuario.Role.ADMIN) {
            throw new IllegalArgumentException("Mantenha pelo menos uma empresa vinculada (apenas admin pode deixar sem empresas).");
        }
        validarEmpresasNoRequest(emailUsuario, req.getIdEmpresas());

        p.setNome(req.getNome().trim());
        p.setIdEmpresas(idsNovos);
        if (req.getPadrao() != null) {
            p.setPadrao(req.getPadrao());
            if (Boolean.TRUE.equals(p.getPadrao())) {
                desmarcarOutrosPadroes(id);
            }
        }

        p = repository.save(p);
        Map<Integer, String> nomes = carregarNomesEmpresas(List.of(p));
        return toDto(p, nomes);
    }

    public void excluir(String emailUsuario, Long id) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        PlanoContasGerencial p = repository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new IllegalArgumentException("Plano não encontrado"));
        if (!usuarioPodeEditarPlano(usuario, p)) {
            throw new IllegalArgumentException("Sem permissão para excluir este plano");
        }
        p.marcarExcluido();
        repository.save(p);
    }

    public PlanoContasGerencialDTO marcarComoPadrao(String emailUsuario, Long id) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        PlanoContasGerencial p = repository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new IllegalArgumentException("Plano não encontrado"));
        if (!usuarioPodeEditarPlano(usuario, p)) {
            throw new IllegalArgumentException("Sem permissão");
        }
        desmarcarOutrosPadroes(id);
        p.setPadrao(true);
        p = repository.save(p);
        Map<Integer, String> nomes = carregarNomesEmpresas(List.of(p));
        return toDto(p, nomes);
    }

    private void desmarcarOutrosPadroes(Long manterId) {
        List<PlanoContasGerencial> todos = repository.findAllByDeletedFalseOrderByNomeAsc();
        for (PlanoContasGerencial x : todos) {
            if (manterId != null && x.getId().equals(manterId)) {
                continue;
            }
            if (Boolean.TRUE.equals(x.getPadrao())) {
                x.setPadrao(false);
                repository.save(x);
            }
        }
    }

    private boolean usuarioPodeVerPlano(Usuario usuario, PlanoContasGerencial p) {
        if (usuario.getRole() == Usuario.Role.ADMIN) {
            return true;
        }
        Set<Integer> ids = safeIds(p);
        if (ids.isEmpty()) {
            return true;
        }
        return ids.stream().anyMatch(idE -> usuarioEmpresaService.temAcesso(usuario.getId(), idE));
    }

    private boolean usuarioPodeEditarPlano(Usuario usuario, PlanoContasGerencial p) {
        if (usuario.getRole() == Usuario.Role.ADMIN) {
            return true;
        }
        Set<Integer> ids = safeIds(p);
        if (ids.isEmpty()) {
            return false;
        }
        return ids.stream().allMatch(idE -> usuarioEmpresaService.temAcesso(usuario.getId(), idE));
    }

    private boolean passaFiltroEmpresa(PlanoContasGerencial p, Integer filtroIdEmpresa) {
        if (filtroIdEmpresa == null || filtroIdEmpresa <= 0) {
            return true;
        }
        Set<Integer> ids = safeIds(p);
        if (ids.isEmpty()) {
            return true;
        }
        return ids.contains(filtroIdEmpresa);
    }

    private static Set<Integer> safeIds(PlanoContasGerencial p) {
        return p.getIdEmpresas() == null ? Set.of() : p.getIdEmpresas();
    }

    private void validarEmpresasNoRequest(String emailUsuario, Set<Integer> idEmpresas) {
        if (idEmpresas == null || idEmpresas.isEmpty()) {
            return;
        }
        for (Integer idE : idEmpresas) {
            if (idE == null || idE <= 0) {
                throw new IllegalArgumentException("idEmpresa inválido");
            }
            if (!usuarioEmpresaService.validarAcessoUsuarioEmpresa(emailUsuario, idE)) {
                throw new IllegalArgumentException("Sem acesso à empresa " + idE);
            }
        }
    }

    private Map<Integer, String> carregarNomesEmpresas(List<PlanoContasGerencial> planos) {
        Set<Integer> ids = planos.stream()
                .flatMap(p -> safeIds(p).stream())
                .collect(Collectors.toSet());
        Map<Integer, String> map = new HashMap<>();
        for (Integer id : ids) {
            empresaUsuarioRepository.findFirstByIdEmpresaAndAtivoTrueOrderByIdAsc(id)
                    .map(EmpresaUsuario::getNomeEmpresa)
                    .filter(n -> n != null && !n.isBlank())
                    .ifPresent(nome -> map.put(id, nome));
        }
        return map;
    }

    private static PlanoContasGerencialDTO toDto(PlanoContasGerencial p, Map<Integer, String> nomes) {
        Set<Integer> ids = safeIds(p);
        Set<PlanoContasEmpresaNomeDTO> empresas = ids.stream()
                .sorted()
                .map(id -> PlanoContasEmpresaNomeDTO.builder()
                        .idEmpresa(id)
                        .nomeEmpresa(nomes.getOrDefault(id, "Empresa " + id))
                        .build())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return PlanoContasGerencialDTO.builder()
                .id(p.getId())
                .nome(p.getNome())
                .padrao(p.getPadrao())
                .idEmpresas(new LinkedHashSet<>(ids))
                .empresas(empresas)
                .dataCriacao(p.getDataCriacao())
                .dataAtualizacao(p.getDataAtualizacao())
                .build();
    }
}
