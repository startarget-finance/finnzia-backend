package com.finnza.service;

import com.finnza.domain.entity.ContaBancariaParam;
import com.finnza.domain.entity.EmpresaUsuario;
import com.finnza.domain.entity.Usuario;
import com.finnza.dto.EmpresaUsuarioDTO;
import com.finnza.dto.request.ContaBancariaCadastroRequest;
import com.finnza.dto.response.ContaBancariaCadastroDTO;
import com.finnza.dto.response.PlanoContasEmpresaNomeDTO;
import com.finnza.repository.ContaBancariaParamRepository;
import com.finnza.repository.EmpresaUsuarioRepository;
import com.finnza.repository.UsuarioRepository;
import com.finnza.repository.specification.ContaBancariaParamSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class ContaBancariaCadastroService {

    private final ContaBancariaParamRepository repository;
    private final UsuarioRepository usuarioRepository;
    private final EmpresaUsuarioRepository empresaUsuarioRepository;
    private final UsuarioEmpresaService usuarioEmpresaService;

    @Transactional(readOnly = true)
    public Page<ContaBancariaCadastroDTO> listar(
            String emailUsuario,
            String q,
            Integer idEmpresa,
            Boolean ativo,
            Pageable pageable
    ) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuario nao encontrado"));

        Specification<ContaBancariaParam> spec = Specification.where(ContaBancariaParamSpecification.naoDeletado())
                .and(ContaBancariaParamSpecification.textoBusca(q))
                .and(ContaBancariaParamSpecification.possuiEmpresa(idEmpresa))
                .and(ContaBancariaParamSpecification.ativo(ativo));

        if (usuario.getRole() != Usuario.Role.ADMIN) {
            Set<Integer> empresas = obterIdsEmpresasUsuario(usuario.getId());
            spec = spec.and(ContaBancariaParamSpecification.visivelParaEmpresasUsuario(empresas));
        }

        Page<ContaBancariaParam> page = repository.findAll(spec, pageable);
        Map<Integer, String> nomes = carregarNomesEmpresas(page.getContent());
        return page.map(c -> toDto(c, nomes));
    }

    @Transactional(readOnly = true)
    public ContaBancariaCadastroDTO buscar(String emailUsuario, Long id) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuario nao encontrado"));
        ContaBancariaParam c = repository.findByIdNaoDeletado(id)
                .orElseThrow(() -> new IllegalArgumentException("Conta nao encontrada"));
        if (!usuarioPodeVer(usuario, c)) {
            throw new IllegalArgumentException("Sem permissao para esta conta");
        }
        Map<Integer, String> nomes = carregarNomesEmpresas(List.of(c));
        return toDto(c, nomes);
    }

    public ContaBancariaCadastroDTO criar(String emailUsuario, ContaBancariaCadastroRequest req) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuario nao encontrado"));

        Set<Integer> idsReq = new HashSet<>(Optional.ofNullable(req.getIdEmpresas()).orElseGet(HashSet::new));
        idsReq.removeIf(Objects::isNull);
        if (idsReq.isEmpty() && usuario.getRole() != Usuario.Role.ADMIN) {
            throw new IllegalArgumentException("Associe pelo menos uma empresa (apenas admin pode criar sem empresas).");
        }
        validarEmpresasNoRequest(emailUsuario, idsReq);

        ContaBancariaParam c = ContaBancariaParam.builder()
                .idEmpresas(new HashSet<>(idsReq))
                .ativo(req.getAtivo() == null || Boolean.TRUE.equals(req.getAtivo()))
                .banco("-")
                .agencia("0")
                .conta("0")
                .build();
        aplicarDadosConta(c, req);

        c = repository.save(c);
        Map<Integer, String> nomes = carregarNomesEmpresas(List.of(c));
        return toDto(c, nomes);
    }

    public ContaBancariaCadastroDTO atualizar(String emailUsuario, Long id, ContaBancariaCadastroRequest req) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuario nao encontrado"));
        ContaBancariaParam c = repository.findByIdNaoDeletado(id)
                .orElseThrow(() -> new IllegalArgumentException("Conta nao encontrada"));
        if (!usuarioPodeEditar(usuario, c)) {
            throw new IllegalArgumentException("Sem permissao para editar esta conta");
        }

        Set<Integer> idsNovos = new HashSet<>(Optional.ofNullable(req.getIdEmpresas()).orElseGet(HashSet::new));
        idsNovos.removeIf(Objects::isNull);
        if (idsNovos.isEmpty() && usuario.getRole() != Usuario.Role.ADMIN) {
            throw new IllegalArgumentException("Mantenha pelo menos uma empresa vinculada (apenas admin pode deixar sem empresas).");
        }
        validarEmpresasNoRequest(emailUsuario, idsNovos);

        aplicarDadosConta(c, req);
        if (req.getAtivo() != null) {
            c.setAtivo(req.getAtivo());
        }
        c.setIdEmpresas(new HashSet<>(idsNovos));

        c = repository.save(c);
        Map<Integer, String> nomes = carregarNomesEmpresas(List.of(c));
        return toDto(c, nomes);
    }

    public void excluir(String emailUsuario, Long id) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuario nao encontrado"));
        ContaBancariaParam c = repository.findByIdNaoDeletado(id)
                .orElseThrow(() -> new IllegalArgumentException("Conta nao encontrada"));
        if (!usuarioPodeEditar(usuario, c)) {
            throw new IllegalArgumentException("Sem permissao para excluir esta conta");
        }
        c.softDelete();
        repository.save(c);
    }

    public ContaBancariaCadastroDTO alterarAtivo(String emailUsuario, Long id, boolean ativo) {
        Usuario usuario = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuario nao encontrado"));
        ContaBancariaParam c = repository.findByIdNaoDeletado(id)
                .orElseThrow(() -> new IllegalArgumentException("Conta nao encontrada"));
        if (!usuarioPodeEditar(usuario, c)) {
            throw new IllegalArgumentException("Sem permissao");
        }
        c.setAtivo(ativo);
        c = repository.save(c);
        Map<Integer, String> nomes = carregarNomesEmpresas(List.of(c));
        return toDto(c, nomes);
    }

    private void aplicarDadosConta(ContaBancariaParam c, ContaBancariaCadastroRequest req) {
        ContaBancariaParam.CategoriaConta cat = Optional.ofNullable(req.getCategoria())
                .orElse(ContaBancariaParam.CategoriaConta.BANCARIA);
        c.setCategoria(cat);
        c.setNomeConta(trimToNull(req.getNomeConta()));
        c.setInstituicao(trimToNull(req.getInstituicao()));
        c.setBanco(req.getBanco().trim());

        String ag = req.getAgencia() != null ? req.getAgencia().trim() : "";
        String ct = req.getConta() != null ? req.getConta().trim() : "";
        if (cat == ContaBancariaParam.CategoriaConta.BANCARIA) {
            if (!StringUtils.hasText(ag) || !StringUtils.hasText(ct)) {
                throw new IllegalArgumentException("Informe agencia e conta para conta bancaria.");
            }
            c.setAgencia(ag);
            c.setConta(ct);
        } else {
            c.setAgencia(StringUtils.hasText(ag) ? ag : "0");
            c.setConta(StringUtils.hasText(ct) ? ct : "0");
        }

        if (cat == ContaBancariaParam.CategoriaConta.DINHEIRO) {
            c.setTipo(ContaBancariaParam.TipoConta.CORRENTE);
        } else {
            c.setTipo(Optional.ofNullable(req.getTipo()).orElse(ContaBancariaParam.TipoConta.CORRENTE));
        }
        c.setSaldoInicial(saldoSeguro(req.getSaldoInicial()));
    }

    private static String trimToNull(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static BigDecimal saldoSeguro(BigDecimal v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return v;
    }

    private Set<Integer> obterIdsEmpresasUsuario(Long usuarioId) {
        return usuarioEmpresaService.obterEmpresasDoUsuario(usuarioId).stream()
                .map(EmpresaUsuarioDTO::getIdEmpresa)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private boolean usuarioPodeVer(Usuario usuario, ContaBancariaParam c) {
        if (usuario.getRole() == Usuario.Role.ADMIN) {
            return true;
        }
        Set<Integer> ids = c.getIdEmpresas() == null ? Set.of() : c.getIdEmpresas();
        if (ids.isEmpty()) {
            return true;
        }
        return ids.stream().anyMatch(idE -> usuarioEmpresaService.temAcesso(usuario.getId(), idE));
    }

    private boolean usuarioPodeEditar(Usuario usuario, ContaBancariaParam c) {
        if (usuario.getRole() == Usuario.Role.ADMIN) {
            return true;
        }
        Set<Integer> ids = c.getIdEmpresas() == null ? Set.of() : c.getIdEmpresas();
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

    private Map<Integer, String> carregarNomesEmpresas(List<ContaBancariaParam> contas) {
        Set<Integer> ids = contas.stream()
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

    private static ContaBancariaCadastroDTO toDto(ContaBancariaParam c, Map<Integer, String> nomes) {
        Set<Integer> idSet = c.getIdEmpresas() == null ? Set.of() : c.getIdEmpresas();
        Set<PlanoContasEmpresaNomeDTO> empresas = idSet.stream()
                .sorted()
                .map(id -> PlanoContasEmpresaNomeDTO.builder()
                        .idEmpresa(id)
                        .nomeEmpresa(nomes.getOrDefault(id, "Empresa " + id))
                        .build())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return ContaBancariaCadastroDTO.builder()
                .id(c.getId())
                .nomeConta(c.getNomeConta())
                .categoria(c.getCategoria())
                .instituicao(c.getInstituicao())
                .banco(c.getBanco())
                .agencia(c.getAgencia())
                .conta(c.getConta())
                .tipo(c.getTipo())
                .saldoInicial(c.getSaldoInicial())
                .ativo(c.getAtivo())
                .idEmpresas(new LinkedHashSet<>(idSet))
                .empresas(empresas)
                .dataCriacao(c.getDataCriacao())
                .dataAtualizacao(c.getDataAtualizacao())
                .build();
    }
}
