package com.finnza.service;

import com.finnza.config.EmpresaContextHolder;
import com.finnza.domain.entity.Cliente;
import com.finnza.domain.entity.EmpresaUsuario;
import com.finnza.domain.entity.Usuario;
import com.finnza.dto.EmpresaUsuarioDTO;
import com.finnza.dto.request.ClienteCadastroRequest;
import com.finnza.dto.response.ClienteCadastroDTO;
import com.finnza.dto.response.PlanoContasEmpresaNomeDTO;
import com.finnza.repository.ClienteRepository;
import com.finnza.repository.EmpresaUsuarioRepository;
import com.finnza.repository.UsuarioRepository;
import com.finnza.repository.specification.ClienteCadastroSpecification;
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
public class ClienteCadastroService {

    private final ClienteRepository clienteRepository;
    private final UsuarioRepository usuarioRepository;
    private final EmpresaUsuarioRepository empresaUsuarioRepository;
    private final UsuarioEmpresaService usuarioEmpresaService;

    @Transactional(readOnly = true)
    public Page<ClienteCadastroDTO> listar(
            String emailUsuario,
            String q,
            Integer idEmpresa,
            Integer classificacao,
            Cliente.TipoPessoa tipoPessoa,
            Pageable pageable
    ) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));

        if (idEmpresa == null || idEmpresa <= 0) {
            Integer ctx = EmpresaContextHolder.getIdEmpresa();
            if (ctx != null && ctx > 0) {
                idEmpresa = ctx;
            }
        }

        Specification<Cliente> spec = Specification.where(ClienteCadastroSpecification.naoDeletado())
                .and(ClienteCadastroSpecification.textoBusca(q))
                .and(ClienteCadastroSpecification.classificacaoIgual(classificacao))
                .and(ClienteCadastroSpecification.tipoPessoaIgual(tipoPessoa))
                .and(ClienteCadastroSpecification.possuiEmpresa(idEmpresa));

        if (usuario.getRole() != Usuario.Role.ADMIN) {
            Set<Integer> empresas = obterIdsEmpresasUsuario(usuario.getId());
            spec = spec.and(ClienteCadastroSpecification.visivelParaEmpresasUsuario(empresas));
        }

        Page<Cliente> page = clienteRepository.findAll(spec, pageable);
        Map<Integer, String> nomes = carregarNomesEmpresas(page.getContent());
        return page.map(c -> toDto(c, nomes));
    }

    @Transactional(readOnly = true)
    public ClienteCadastroDTO buscar(String emailUsuario, Long id) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        Cliente c = clienteRepository.findByIdNaoDeletado(id)
                .orElseThrow(() -> new IllegalArgumentException("Cliente não encontrado"));
        if (!usuarioPodeVerCliente(usuario, c)) {
            throw new IllegalArgumentException("Sem permissão para este cliente");
        }
        Map<Integer, String> nomes = carregarNomesEmpresas(List.of(c));
        return toDto(c, nomes);
    }

    public ClienteCadastroDTO criar(String emailUsuario, ClienteCadastroRequest req) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));

        Set<Integer> idsReq = resolverEmpresasParaEscrita(usuario, req.getIdEmpresas());
        validarEmpresasNoRequest(emailUsuario, idsReq);

        String cpfNorm = normalizarCpfCnpj(req.getCpfCnpj());
        validarCpfCnpjUnico(cpfNorm, null);

        Cliente c = Cliente.builder()
                .razaoSocial(req.getRazaoSocial().trim())
                .nomeFantasia(trimToNull(req.getNomeFantasia()))
                .cpfCnpj(cpfNorm)
                .tipoPessoa(Optional.ofNullable(req.getTipoPessoa()).orElse(Cliente.TipoPessoa.PJ))
                .classificacao(clampClassificacao(req.getClassificacao()))
                .idEmpresas(new HashSet<>(idsReq))
                .bloqueado(Boolean.TRUE.equals(req.getBloqueado()))
                .enderecoCompleto(trimToNull(req.getEnderecoCompleto()))
                .cep(trimToNull(req.getCep()))
                .celularFinanceiro(trimToNull(req.getCelularFinanceiro()))
                .emailFinanceiro(trimToNull(req.getEmailFinanceiro()))
                .responsavel(trimToNull(req.getResponsavel()))
                .cpf(trimToNull(req.getCpf()))
                .build();

        c = clienteRepository.save(c);
        Map<Integer, String> nomes = carregarNomesEmpresas(List.of(c));
        return toDto(c, nomes);
    }

    public ClienteCadastroDTO atualizar(String emailUsuario, Long id, ClienteCadastroRequest req) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        Cliente c = clienteRepository.findByIdNaoDeletado(id)
                .orElseThrow(() -> new IllegalArgumentException("Cliente não encontrado"));
        if (!usuarioPodeEditarCliente(usuario, c)) {
            throw new IllegalArgumentException("Sem permissão para editar este cliente");
        }

        Set<Integer> idsNovos = resolverEmpresasParaEscrita(usuario, req.getIdEmpresas());
        validarEmpresasNoRequest(emailUsuario, idsNovos);

        String cpfNorm = normalizarCpfCnpj(req.getCpfCnpj());
        validarCpfCnpjUnico(cpfNorm, id);

        c.setRazaoSocial(req.getRazaoSocial().trim());
        c.setNomeFantasia(trimToNull(req.getNomeFantasia()));
        c.setCpfCnpj(cpfNorm);
        c.setTipoPessoa(Optional.ofNullable(req.getTipoPessoa()).orElse(Cliente.TipoPessoa.PJ));
        c.setClassificacao(clampClassificacao(req.getClassificacao()));
        c.setIdEmpresas(new HashSet<>(idsNovos));
        if (req.getBloqueado() != null) {
            c.setBloqueado(req.getBloqueado());
        }
        c.setEnderecoCompleto(trimToNull(req.getEnderecoCompleto()));
        c.setCep(trimToNull(req.getCep()));
        c.setCelularFinanceiro(trimToNull(req.getCelularFinanceiro()));
        c.setEmailFinanceiro(trimToNull(req.getEmailFinanceiro()));
        c.setResponsavel(trimToNull(req.getResponsavel()));
        c.setCpf(trimToNull(req.getCpf()));

        c = clienteRepository.save(c);
        Map<Integer, String> nomes = carregarNomesEmpresas(List.of(c));
        return toDto(c, nomes);
    }

    public ClienteCadastroDTO alterarBloqueio(String emailUsuario, Long id, boolean bloqueado) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        Cliente c = clienteRepository.findByIdNaoDeletado(id)
                .orElseThrow(() -> new IllegalArgumentException("Cliente não encontrado"));
        if (!usuarioPodeEditarCliente(usuario, c)) {
            throw new IllegalArgumentException("Sem permissão");
        }
        c.setBloqueado(bloqueado);
        c = clienteRepository.save(c);
        Map<Integer, String> nomes = carregarNomesEmpresas(List.of(c));
        return toDto(c, nomes);
    }

    public void excluir(String emailUsuario, Long id) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        Cliente c = clienteRepository.findByIdNaoDeletado(id)
                .orElseThrow(() -> new IllegalArgumentException("Cliente não encontrado"));
        if (!usuarioPodeEditarCliente(usuario, c)) {
            throw new IllegalArgumentException("Sem permissão para excluir este cliente");
        }
        c.softDelete();
        clienteRepository.save(c);
    }

    private void validarCpfCnpjUnico(String cpfNorm, Long ignorarId) {
        if (!StringUtils.hasText(cpfNorm)) {
            return;
        }
        clienteRepository.findOutroPorCpfCnpj(cpfNorm, ignorarId).ifPresent(x -> {
            throw new IllegalArgumentException("Já existe cliente com este CPF/CNPJ");
        });
    }

    private static String normalizarCpfCnpj(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return raw.trim();
    }

    private static String trimToNull(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static int clampClassificacao(Integer c) {
        if (c == null) {
            return 3;
        }
        return Math.max(1, Math.min(5, c));
    }

    private Set<Integer> obterIdsEmpresasUsuario(Long usuarioId) {
        return usuarioEmpresaService.obterEmpresasDoUsuario(usuarioId).stream()
                .map(EmpresaUsuarioDTO::getIdEmpresa)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private boolean usuarioPodeVerCliente(Usuario usuario, Cliente c) {
        if (usuario.getRole() == Usuario.Role.ADMIN) {
            return true;
        }
        return usuarioPodeVerClienteNonAdmin(usuario, c);
    }

    private boolean usuarioPodeVerClienteNonAdmin(Usuario usuario, Cliente c) {
        Set<Integer> ids = c.getIdEmpresas() == null ? Set.of() : c.getIdEmpresas();
        if (ids.isEmpty()) {
            return true;
        }
        return ids.stream().anyMatch(idE -> usuarioEmpresaService.temAcesso(usuario.getId(), idE));
    }

    private boolean usuarioPodeEditarCliente(Usuario usuario, Cliente c) {
        if (usuario.getRole() == Usuario.Role.ADMIN) {
            return true;
        }
        Set<Integer> ids = c.getIdEmpresas() == null ? Set.of() : c.getIdEmpresas();
        if (ids.isEmpty()) {
            return false;
        }
        return ids.stream().allMatch(idE -> usuarioEmpresaService.temAcesso(usuario.getId(), idE));
    }

    /**
     * Com {@code X-Empresa-Id}, gravações ficam restritas à empresa do contexto (parametrização por empresa).
     */
    private Set<Integer> resolverEmpresasParaEscrita(Usuario usuario, Set<Integer> doRequest) {
        Integer ctx = EmpresaContextHolder.getIdEmpresa();
        if (ctx != null && ctx > 0) {
            return new HashSet<>(Set.of(ctx));
        }
        Set<Integer> idsReq = new HashSet<>(Optional.ofNullable(doRequest).orElseGet(HashSet::new));
        idsReq.removeIf(Objects::isNull);
        if (idsReq.isEmpty() && usuario.getRole() != Usuario.Role.ADMIN) {
            throw new IllegalArgumentException("Associe pelo menos uma empresa ou selecione uma no sistema.");
        }
        return idsReq;
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

    private Map<Integer, String> carregarNomesEmpresas(List<Cliente> clientes) {
        Set<Integer> ids = clientes.stream()
                .flatMap(cl -> {
                    Set<Integer> s = cl.getIdEmpresas();
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

    private static ClienteCadastroDTO toDto(Cliente c, Map<Integer, String> nomes) {
        Set<Integer> idSet = c.getIdEmpresas() == null ? Set.of() : c.getIdEmpresas();
        Set<PlanoContasEmpresaNomeDTO> empresas = idSet.stream()
                .sorted()
                .map(id -> PlanoContasEmpresaNomeDTO.builder()
                        .idEmpresa(id)
                        .nomeEmpresa(nomes.getOrDefault(id, "Empresa " + id))
                        .build())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return ClienteCadastroDTO.builder()
                .id(c.getId())
                .razaoSocial(c.getRazaoSocial())
                .nomeFantasia(c.getNomeFantasia())
                .cpfCnpj(c.getCpfCnpj())
                .tipoPessoa(c.getTipoPessoa())
                .classificacao(c.getClassificacao())
                .bloqueado(c.getBloqueado())
                .celularFinanceiro(c.getCelularFinanceiro())
                .emailFinanceiro(c.getEmailFinanceiro())
                .enderecoCompleto(c.getEnderecoCompleto())
                .cep(c.getCep())
                .responsavel(c.getResponsavel())
                .cpf(c.getCpf())
                .idEmpresas(new LinkedHashSet<>(idSet))
                .empresas(empresas)
                .dataCriacao(c.getDataCriacao())
                .dataAtualizacao(c.getDataAtualizacao())
                .build();
    }
}
