package com.finnza.service;

import com.finnza.domain.entity.CategoriaFinanceiraEmpresa;
import com.finnza.dto.request.CategoriaFinanceiraRequest;
import com.finnza.dto.response.CategoriaFinanceiraDTO;
import com.finnza.dto.response.SubcategoriaFinanceiraDTO;
import com.finnza.repository.CategoriaFinanceiraEmpresaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class CategoriaFinanceiraService {

    private final CategoriaFinanceiraEmpresaRepository repository;
    private final UsuarioEmpresaService usuarioEmpresaService;

    @Transactional(readOnly = true)
    public List<CategoriaFinanceiraDTO> listar(String emailUsuario, Integer idEmpresa) {
        validarAcesso(emailUsuario, idEmpresa);
        List<CategoriaFinanceiraEmpresa> rows =
                repository.findAllByDeletedFalseAndIdEmpresaOrderByTipoAscParentIdAscOrdemAscNomeAsc(idEmpresa);
        return montarFloresta(rows);
    }

    public List<CategoriaFinanceiraDTO> salvar(String emailUsuario, CategoriaFinanceiraRequest req) {
        Integer idEmpresa = Optional.ofNullable(req.getIdEmpresa()).orElse(0);
        validarAcesso(emailUsuario, idEmpresa);
        CategoriaFinanceiraEmpresa.TipoCategoria tipo = parseTipo(req.getTipo());

        String nome = normalizar(req.getNome());
        Long parentId = req.getParentId();

        if (nome.isEmpty()) {
            String legCat = normalizar(req.getNomeCategoria());
            String legSub = normalizar(req.getNomeSubcategoria());
            if (legCat.isEmpty()) {
                throw new IllegalArgumentException("Nome do plano de contas é obrigatório.");
            }
            if (legSub.isEmpty()) {
                nome = legCat;
                parentId = null;
            } else {
                nome = legSub;
                CategoriaFinanceiraEmpresa pai = repository
                        .findRootByNome(idEmpresa, tipo, legCat)
                        .orElseGet(() -> repository.save(CategoriaFinanceiraEmpresa.builder()
                                .idEmpresa(idEmpresa)
                                .tipo(tipo)
                                .nome(legCat)
                                .parentId(null)
                                .ordem(proximaOrdem(idEmpresa, tipo, null))
                                .build()));
                parentId = pai.getId();
            }
        }

        if (nome.isEmpty()) {
            throw new IllegalArgumentException("Nome do plano de contas é obrigatório.");
        }

        CategoriaFinanceiraEmpresa parent = null;
        if (parentId != null && parentId > 0) {
            parent = repository.findByIdAndDeletedFalse(parentId)
                    .orElseThrow(() -> new IllegalArgumentException("Categoria pai não encontrada."));
            if (!Objects.equals(parent.getIdEmpresa(), idEmpresa)) {
                throw new IllegalArgumentException("Categoria pai não pertence à empresa.");
            }
            if (parent.getTipo() != tipo) {
                throw new IllegalArgumentException("Tipo deve ser o mesmo da categoria pai.");
            }
        }

        if (parentId == null || parentId <= 0) {
            boolean existe = repository
                    .findFirstByDeletedFalseAndIdEmpresaAndTipoAndParentIdIsNullAndNomeIgnoreCase(idEmpresa, tipo, nome)
                    .isPresent();
            if (!existe) {
                repository.save(CategoriaFinanceiraEmpresa.builder()
                        .idEmpresa(idEmpresa)
                        .tipo(tipo)
                        .nome(nome)
                        .parentId(null)
                        .ordem(proximaOrdem(idEmpresa, tipo, null))
                        .build());
            }
        } else {
            Long pid = parent.getId();
            boolean existe = repository
                    .findFirstByDeletedFalseAndIdEmpresaAndTipoAndParentIdAndNomeIgnoreCase(idEmpresa, tipo, pid, nome)
                    .isPresent();
            if (!existe) {
                repository.save(CategoriaFinanceiraEmpresa.builder()
                        .idEmpresa(idEmpresa)
                        .tipo(tipo)
                        .nome(nome)
                        .parentId(pid)
                        .ordem(proximaOrdem(idEmpresa, tipo, pid))
                        .build());
            }
        }
        return listar(emailUsuario, idEmpresa);
    }

    /**
     * Renomeia um nó existente (mesmo nível hierárquico e pai). Evita duplicidade de nome entre irmãos.
     */
    public List<CategoriaFinanceiraDTO> renomearNo(
            String emailUsuario, Integer idEmpresa, Long nodeId, String nomeNovo) {
        validarAcesso(emailUsuario, idEmpresa);
        String nome = normalizar(nomeNovo);
        if (nome.isEmpty()) {
            throw new IllegalArgumentException("Nome do plano de contas é obrigatório.");
        }
        CategoriaFinanceiraEmpresa node = repository.findByIdAndDeletedFalse(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Item do plano de contas não encontrado."));
        if (!Objects.equals(node.getIdEmpresa(), idEmpresa)) {
            throw new IllegalArgumentException("Item não pertence à empresa selecionada.");
        }
        if (nome.equalsIgnoreCase(node.getNome())) {
            return listar(emailUsuario, idEmpresa);
        }
        CategoriaFinanceiraEmpresa.TipoCategoria tipo = node.getTipo();
        Long parentId = node.getParentId();
        if (parentId == null || parentId <= 0) {
            Optional<CategoriaFinanceiraEmpresa> clash = repository
                    .findFirstByDeletedFalseAndIdEmpresaAndTipoAndParentIdIsNullAndNomeIgnoreCase(idEmpresa, tipo, nome);
            if (clash.isPresent() && !clash.get().getId().equals(nodeId)) {
                throw new IllegalArgumentException("Já existe categoria com este nome.");
            }
        } else {
            Optional<CategoriaFinanceiraEmpresa> clash = repository
                    .findFirstByDeletedFalseAndIdEmpresaAndTipoAndParentIdAndNomeIgnoreCase(
                            idEmpresa, tipo, parentId, nome);
            if (clash.isPresent() && !clash.get().getId().equals(nodeId)) {
                throw new IllegalArgumentException("Já existe conta com este nome neste agrupamento.");
            }
        }
        node.setNome(nome);
        repository.save(node);
        return listar(emailUsuario, idEmpresa);
    }

    /**
     * Remove o nó e toda a subárvore (soft delete).
     */
    public List<CategoriaFinanceiraDTO> excluirNo(String emailUsuario, Integer idEmpresa, Long nodeId) {
        validarAcesso(emailUsuario, idEmpresa);
        CategoriaFinanceiraEmpresa node = repository.findByIdAndDeletedFalse(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Item do plano de contas não encontrado."));
        if (!Objects.equals(node.getIdEmpresa(), idEmpresa)) {
            throw new IllegalArgumentException("Item não pertence à empresa selecionada.");
        }
        Set<Long> ids = coletarSubarvoreIds(node.getId(), idEmpresa);
        List<CategoriaFinanceiraEmpresa> todos = repository
                .findAllByDeletedFalseAndIdEmpresaOrderByTipoAscParentIdAscOrdemAscNomeAsc(idEmpresa);
        List<CategoriaFinanceiraEmpresa> toSave = new ArrayList<>();
        for (CategoriaFinanceiraEmpresa e : todos) {
            if (ids.contains(e.getId())) {
                e.marcarExcluido();
                toSave.add(e);
            }
        }
        repository.saveAll(toSave);
        return listar(emailUsuario, idEmpresa);
    }

    private Set<Long> coletarSubarvoreIds(Long rootId, Integer idEmpresa) {
        List<CategoriaFinanceiraEmpresa> all =
                repository.findAllByDeletedFalseAndIdEmpresaOrderByTipoAscParentIdAscOrdemAscNomeAsc(idEmpresa);
        Map<Long, List<CategoriaFinanceiraEmpresa>> byParent = new HashMap<>();
        for (CategoriaFinanceiraEmpresa e : all) {
            Long p = e.getParentId();
            byParent.computeIfAbsent(p == null ? -1L : p, k -> new ArrayList<>()).add(e);
        }
        Set<Long> out = new LinkedHashSet<>();
        Deque<Long> dq = new ArrayDeque<>();
        dq.add(rootId);
        while (!dq.isEmpty()) {
            Long id = dq.poll();
            if (!out.add(id)) {
                continue;
            }
            List<CategoriaFinanceiraEmpresa> kids = byParent.getOrDefault(id, List.of());
            for (CategoriaFinanceiraEmpresa k : kids) {
                dq.add(k.getId());
            }
        }
        return out;
    }

    private int proximaOrdem(Integer idEmpresa, CategoriaFinanceiraEmpresa.TipoCategoria tipo, Long parentId) {
        Integer max = (parentId == null || parentId <= 0)
                ? repository.findMaxOrdemRaiz(idEmpresa, tipo)
                : repository.findMaxOrdemFilho(idEmpresa, tipo, parentId);
        return (max == null ? -1 : max) + 1;
    }

    private List<CategoriaFinanceiraDTO> montarFloresta(List<CategoriaFinanceiraEmpresa> rows) {
        Map<Long, List<CategoriaFinanceiraEmpresa>> byParent = new LinkedHashMap<>();
        for (CategoriaFinanceiraEmpresa r : rows) {
            Long p = r.getParentId();
            byParent.computeIfAbsent(p == null ? -1L : p, k -> new ArrayList<>()).add(r);
        }
        for (List<CategoriaFinanceiraEmpresa> list : byParent.values()) {
            list.sort(Comparator
                    .comparing(CategoriaFinanceiraEmpresa::getOrdem, Comparator.nullsLast(Integer::compareTo))
                    .thenComparing(CategoriaFinanceiraEmpresa::getNome, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        }
        List<CategoriaFinanceiraEmpresa> roots = byParent.getOrDefault(-1L, List.of());
        roots.sort(Comparator
                .comparing(CategoriaFinanceiraEmpresa::getTipo)
                .thenComparing(CategoriaFinanceiraEmpresa::getOrdem, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(CategoriaFinanceiraEmpresa::getNome, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return roots.stream().map(r -> toRootDto(r, byParent)).collect(Collectors.toList());
    }

    private CategoriaFinanceiraDTO toRootDto(
            CategoriaFinanceiraEmpresa root,
            Map<Long, List<CategoriaFinanceiraEmpresa>> byParent
    ) {
        String tipoStr = root.getTipo() == CategoriaFinanceiraEmpresa.TipoCategoria.RECEITA ? "receita" : "despesa";
        String id = tipoStr + ":" + root.getId();
        List<SubcategoriaFinanceiraDTO> filhos = nosFilhosParaDto(root.getId(), byParent);
        return CategoriaFinanceiraDTO.builder()
                .id(id)
                .tipo(tipoStr)
                .nome(safeNome(root.getNome()))
                .subcategorias(filhos)
                .dataCriacao(root.getDataCriacao())
                .dataAtualizacao(root.getDataAtualizacao())
                .build();
    }

    private List<SubcategoriaFinanceiraDTO> nosFilhosParaDto(
            Long parentDbId,
            Map<Long, List<CategoriaFinanceiraEmpresa>> byParent
    ) {
        List<CategoriaFinanceiraEmpresa> kids = byParent.getOrDefault(parentDbId, List.of());
        return kids.stream().map(k -> toSubDto(k, byParent)).collect(Collectors.toList());
    }

    private SubcategoriaFinanceiraDTO toSubDto(
            CategoriaFinanceiraEmpresa node,
            Map<Long, List<CategoriaFinanceiraEmpresa>> byParent
    ) {
        return SubcategoriaFinanceiraDTO.builder()
                .id(node.getId())
                .nome(safeNome(node.getNome()))
                .children(nosFilhosParaDto(node.getId(), byParent))
                .build();
    }

    private static String safeNome(String nome) {
        return (nome == null || nome.isBlank()) ? "Sem nome" : nome;
    }

    private void validarAcesso(String emailUsuario, Integer idEmpresa) {
        if (idEmpresa == null || idEmpresa <= 0) {
            throw new IllegalArgumentException("idEmpresa inválido.");
        }
        // Alinha com ErpFinanceiroController: usuário sem vínculos em empresa_usuario (single-tenant)
        // não é bloqueado só por ausência do mapeamento legado.
        if (!usuarioEmpresaService.usuarioTemEmpresasAtivasPorEmail(emailUsuario)) {
            return;
        }
        if (!usuarioEmpresaService.validarAcessoUsuarioEmpresa(emailUsuario, idEmpresa)) {
            throw new IllegalArgumentException("Sem acesso à empresa " + idEmpresa);
        }
    }

    private static CategoriaFinanceiraEmpresa.TipoCategoria parseTipo(String tipo) {
        String t = normalizar(tipo).toLowerCase(Locale.ROOT);
        if ("receita".equals(t)) return CategoriaFinanceiraEmpresa.TipoCategoria.RECEITA;
        if ("despesa".equals(t)) return CategoriaFinanceiraEmpresa.TipoCategoria.DESPESA;
        throw new IllegalArgumentException("Tipo deve ser receita ou despesa.");
    }

    private static String normalizar(String s) {
        return s == null ? "" : s.trim();
    }
}
