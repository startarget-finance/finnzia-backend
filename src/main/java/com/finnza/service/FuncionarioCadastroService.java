package com.finnza.service;

import com.finnza.domain.entity.EmpresaUsuario;
import com.finnza.domain.entity.FuncionarioParam;
import com.finnza.domain.entity.Usuario;
import com.finnza.dto.EmpresaUsuarioDTO;
import com.finnza.dto.request.FuncionarioCadastroRequest;
import com.finnza.dto.response.FuncionarioCadastroDTO;
import com.finnza.dto.response.PlanoContasEmpresaNomeDTO;
import com.finnza.repository.EmpresaUsuarioRepository;
import com.finnza.repository.FuncionarioParamRepository;
import com.finnza.repository.UsuarioRepository;
import com.finnza.repository.specification.FuncionarioParamSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class FuncionarioCadastroService {

    private final FuncionarioParamRepository repository;
    private final UsuarioRepository usuarioRepository;
    private final EmpresaUsuarioRepository empresaUsuarioRepository;
    private final UsuarioEmpresaService usuarioEmpresaService;

    @Transactional(readOnly = true)
    public Page<FuncionarioCadastroDTO> listar(
            String emailUsuario,
            String q,
            Integer idEmpresa,
            Boolean ativo,
            Pageable pageable
    ) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuario nao encontrado"));

        Specification<FuncionarioParam> spec = Specification.where(FuncionarioParamSpecification.naoDeletado())
                .and(FuncionarioParamSpecification.textoBusca(q))
                .and(FuncionarioParamSpecification.possuiEmpresa(idEmpresa))
                .and(FuncionarioParamSpecification.ativo(ativo));

        if (usuario.getRole() != Usuario.Role.ADMIN) {
            Set<Integer> empresas = obterIdsEmpresasUsuario(usuario.getId());
            spec = spec.and(FuncionarioParamSpecification.visivelParaEmpresasUsuario(empresas));
        }

        Page<FuncionarioParam> page = repository.findAll(spec, pageable);
        Map<Integer, String> nomes = carregarNomesEmpresas(page.getContent());
        return page.map(f -> toDto(f, nomes));
    }

    @Transactional(readOnly = true)
    public FuncionarioCadastroDTO buscar(String emailUsuario, Long id) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuario nao encontrado"));
        FuncionarioParam f = repository.findByIdNaoDeletado(id)
                .orElseThrow(() -> new IllegalArgumentException("Funcionario nao encontrado"));
        if (!usuarioPodeVer(usuario, f)) {
            throw new IllegalArgumentException("Sem permissao para este funcionario");
        }
        Map<Integer, String> nomes = carregarNomesEmpresas(List.of(f));
        return toDto(f, nomes);
    }

    public FuncionarioCadastroDTO criar(String emailUsuario, FuncionarioCadastroRequest req) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuario nao encontrado"));

        Set<Integer> idsReq = new HashSet<>(Optional.ofNullable(req.getIdEmpresas()).orElseGet(HashSet::new));
        idsReq.removeIf(Objects::isNull);
        if (idsReq.isEmpty() && usuario.getRole() != Usuario.Role.ADMIN) {
            throw new IllegalArgumentException("Associe pelo menos uma empresa (apenas admin pode criar sem empresas).");
        }
        validarEmpresasNoRequest(emailUsuario, idsReq);

        String cpfNorm = normalizarCpf(req.getCpf());
        validarCpfUnico(cpfNorm, null);

        FuncionarioParam f = FuncionarioParam.builder()
                .nomeCompleto(req.getNomeCompleto().trim())
                .cpf(cpfNorm)
                .cargo(trimToNull(req.getCargo()))
                .departamento(trimToNull(req.getDepartamento()))
                .email(trimToNull(req.getEmail()))
                .telefone(trimToNull(req.getTelefone()))
                .ativo(req.getAtivo() == null || Boolean.TRUE.equals(req.getAtivo()))
                .idEmpresas(new HashSet<>(idsReq))
                .build();

        f = repository.save(f);
        return toDto(f, carregarNomesEmpresas(List.of(f)));
    }

    public FuncionarioCadastroDTO atualizar(String emailUsuario, Long id, FuncionarioCadastroRequest req) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuario nao encontrado"));
        FuncionarioParam f = repository.findByIdNaoDeletado(id)
                .orElseThrow(() -> new IllegalArgumentException("Funcionario nao encontrado"));
        if (!usuarioPodeEditar(usuario, f)) {
            throw new IllegalArgumentException("Sem permissao para editar este funcionario");
        }

        Set<Integer> idsNovos = new HashSet<>(Optional.ofNullable(req.getIdEmpresas()).orElseGet(HashSet::new));
        idsNovos.removeIf(Objects::isNull);
        if (idsNovos.isEmpty() && usuario.getRole() != Usuario.Role.ADMIN) {
            throw new IllegalArgumentException("Mantenha pelo menos uma empresa vinculada (apenas admin pode deixar sem empresas).");
        }
        validarEmpresasNoRequest(emailUsuario, idsNovos);

        String cpfNorm = normalizarCpf(req.getCpf());
        validarCpfUnico(cpfNorm, id);

        f.setNomeCompleto(req.getNomeCompleto().trim());
        f.setCpf(cpfNorm);
        f.setCargo(trimToNull(req.getCargo()));
        f.setDepartamento(trimToNull(req.getDepartamento()));
        f.setEmail(trimToNull(req.getEmail()));
        f.setTelefone(trimToNull(req.getTelefone()));
        if (req.getAtivo() != null) {
            f.setAtivo(req.getAtivo());
        }
        f.setIdEmpresas(new HashSet<>(idsNovos));

        f = repository.save(f);
        return toDto(f, carregarNomesEmpresas(List.of(f)));
    }

    public FuncionarioCadastroDTO alterarAtivo(String emailUsuario, Long id, boolean ativo) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuario nao encontrado"));
        FuncionarioParam f = repository.findByIdNaoDeletado(id)
                .orElseThrow(() -> new IllegalArgumentException("Funcionario nao encontrado"));
        if (!usuarioPodeEditar(usuario, f)) {
            throw new IllegalArgumentException("Sem permissao");
        }
        f.setAtivo(ativo);
        f = repository.save(f);
        return toDto(f, carregarNomesEmpresas(List.of(f)));
    }

    public void excluir(String emailUsuario, Long id) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuario nao encontrado"));
        FuncionarioParam f = repository.findByIdNaoDeletado(id)
                .orElseThrow(() -> new IllegalArgumentException("Funcionario nao encontrado"));
        if (!usuarioPodeEditar(usuario, f)) {
            throw new IllegalArgumentException("Sem permissao para excluir este funcionario");
        }
        f.softDelete();
        repository.save(f);
    }

    private void validarCpfUnico(String cpfNorm, Long ignorarId) {
        if (!StringUtils.hasText(cpfNorm)) {
            return;
        }
        repository.findOutroPorCpf(cpfNorm, ignorarId).ifPresent(x -> {
            throw new IllegalArgumentException("Ja existe funcionario com este CPF");
        });
    }

    private static String normalizarCpf(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String d = raw.replaceAll("\\D", "");
        if (d.isEmpty()) {
            return null;
        }
        return d.length() > 14 ? d.substring(0, 14) : d;
    }

    private static String trimToNull(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private Set<Integer> obterIdsEmpresasUsuario(Long usuarioId) {
        return usuarioEmpresaService.obterEmpresasDoUsuario(usuarioId).stream()
                .map(EmpresaUsuarioDTO::getIdEmpresa)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private boolean usuarioPodeVer(Usuario usuario, FuncionarioParam f) {
        if (usuario.getRole() == Usuario.Role.ADMIN) {
            return true;
        }
        Set<Integer> ids = f.getIdEmpresas() == null ? Set.of() : f.getIdEmpresas();
        if (ids.isEmpty()) {
            return true;
        }
        return ids.stream().anyMatch(idE -> usuarioEmpresaService.temAcesso(usuario.getId(), idE));
    }

    private boolean usuarioPodeEditar(Usuario usuario, FuncionarioParam f) {
        if (usuario.getRole() == Usuario.Role.ADMIN) {
            return true;
        }
        Set<Integer> ids = f.getIdEmpresas() == null ? Set.of() : f.getIdEmpresas();
        if (ids.isEmpty()) {
            return false;
        }
        return ids.stream().allMatch(idE -> usuarioEmpresaService.temAcesso(usuario.getId(), idE));
    }

    private void validarEmpresasNoRequest(String emailUsuario, Set<Integer> idEmpresas) {
        if (idEmpresas == null || idEmpresas.isEmpty()) {
            return;
        }
        for (Integer idE : idEmpresas) {
            if (idE == null || idE <= 0) {
                throw new IllegalArgumentException("idEmpresa invalido");
            }
            if (!usuarioEmpresaService.validarAcessoUsuarioEmpresa(emailUsuario, idE)) {
                throw new IllegalArgumentException("Sem acesso a empresa " + idE);
            }
        }
    }

    private Map<Integer, String> carregarNomesEmpresas(List<FuncionarioParam> lista) {
        Set<Integer> ids = lista.stream()
                .flatMap(f -> {
                    Set<Integer> s = f.getIdEmpresas();
                    return s == null ? java.util.stream.Stream.<Integer>empty() : s.stream();
                })
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

    private static FuncionarioCadastroDTO toDto(FuncionarioParam f, Map<Integer, String> nomes) {
        Set<Integer> idSet = f.getIdEmpresas() == null ? Set.of() : f.getIdEmpresas();
        Set<PlanoContasEmpresaNomeDTO> empresas = idSet.stream()
                .sorted()
                .map(id -> PlanoContasEmpresaNomeDTO.builder()
                        .idEmpresa(id)
                        .nomeEmpresa(nomes.getOrDefault(id, "Empresa " + id))
                        .build())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return FuncionarioCadastroDTO.builder()
                .id(f.getId())
                .nomeCompleto(f.getNomeCompleto())
                .cpf(f.getCpf())
                .cargo(f.getCargo())
                .departamento(f.getDepartamento())
                .email(f.getEmail())
                .telefone(f.getTelefone())
                .ativo(f.getAtivo())
                .idEmpresas(new LinkedHashSet<>(idSet))
                .empresas(empresas)
                .dataCriacao(f.getDataCriacao())
                .dataAtualizacao(f.getDataAtualizacao())
                .build();
    }
}
