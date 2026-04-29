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
                repository.findAllByDeletedFalseAndIdEmpresaOrderByTipoAscNomeCategoriaAscNomeSubcategoriaAsc(idEmpresa);
        return agrupar(rows);
    }

    public List<CategoriaFinanceiraDTO> salvar(String emailUsuario, CategoriaFinanceiraRequest req) {
        Integer idEmpresa = Optional.ofNullable(req.getIdEmpresa()).orElse(0);
        validarAcesso(emailUsuario, idEmpresa);
        CategoriaFinanceiraEmpresa.TipoCategoria tipo = parseTipo(req.getTipo());
        String nomeCategoria = normalizar(req.getNomeCategoria());
        if (nomeCategoria.isEmpty()) {
            throw new IllegalArgumentException("Nome da categoria é obrigatório.");
        }
        String nomeSubcategoria = normalizar(req.getNomeSubcategoria());

        if (nomeSubcategoria.isEmpty()) {
            boolean existe = repository
                    .findFirstByDeletedFalseAndIdEmpresaAndTipoAndNomeCategoriaIgnoreCaseAndNomeSubcategoriaIsNull(
                            idEmpresa, tipo, nomeCategoria
                    )
                    .isPresent();
            if (!existe) {
                repository.save(CategoriaFinanceiraEmpresa.builder()
                        .idEmpresa(idEmpresa)
                        .tipo(tipo)
                        .nomeCategoria(nomeCategoria)
                        .nomeSubcategoria(null)
                        .build());
            }
        } else {
            boolean existe = repository
                    .findFirstByDeletedFalseAndIdEmpresaAndTipoAndNomeCategoriaIgnoreCaseAndNomeSubcategoriaIgnoreCase(
                            idEmpresa, tipo, nomeCategoria, nomeSubcategoria
                    )
                    .isPresent();
            if (!existe) {
                repository.save(CategoriaFinanceiraEmpresa.builder()
                        .idEmpresa(idEmpresa)
                        .tipo(tipo)
                        .nomeCategoria(nomeCategoria)
                        .nomeSubcategoria(nomeSubcategoria)
                        .build());
            }
        }
        return listar(emailUsuario, idEmpresa);
    }

    public List<CategoriaFinanceiraDTO> excluirCategoria(String emailUsuario, Integer idEmpresa, String idCategoria) {
        validarAcesso(emailUsuario, idEmpresa);
        CategoriaKey key = parseCategoriaKey(idCategoria);
        List<CategoriaFinanceiraEmpresa> rows =
                repository.findAllByDeletedFalseAndIdEmpresaOrderByTipoAscNomeCategoriaAscNomeSubcategoriaAsc(idEmpresa);
        rows.stream()
                .filter(r -> r.getTipo() == key.tipo && r.getNomeCategoria().equalsIgnoreCase(key.nomeCategoria))
                .forEach(CategoriaFinanceiraEmpresa::marcarExcluido);
        repository.saveAll(rows);
        return listar(emailUsuario, idEmpresa);
    }

    public List<CategoriaFinanceiraDTO> excluirSubcategoria(
            String emailUsuario,
            Integer idEmpresa,
            String idCategoria,
            Long idSubcategoria
    ) {
        validarAcesso(emailUsuario, idEmpresa);
        CategoriaKey key = parseCategoriaKey(idCategoria);
        CategoriaFinanceiraEmpresa row = repository.findByIdAndDeletedFalse(idSubcategoria)
                .orElseThrow(() -> new IllegalArgumentException("Subcategoria não encontrada."));
        if (!Objects.equals(row.getIdEmpresa(), idEmpresa)
                || row.getTipo() != key.tipo
                || !row.getNomeCategoria().equalsIgnoreCase(key.nomeCategoria)) {
            throw new IllegalArgumentException("Subcategoria não pertence à categoria informada.");
        }
        row.marcarExcluido();
        repository.save(row);
        return listar(emailUsuario, idEmpresa);
    }

    private void validarAcesso(String emailUsuario, Integer idEmpresa) {
        if (idEmpresa == null || idEmpresa <= 0) {
            throw new IllegalArgumentException("idEmpresa inválido.");
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

    private static List<CategoriaFinanceiraDTO> agrupar(List<CategoriaFinanceiraEmpresa> rows) {
        Map<String, CategoriaFinanceiraDTO> map = new LinkedHashMap<>();
        for (CategoriaFinanceiraEmpresa r : rows) {
            String key = buildCategoriaId(r.getTipo(), r.getNomeCategoria());
            CategoriaFinanceiraDTO dto = map.computeIfAbsent(key, k -> CategoriaFinanceiraDTO.builder()
                    .id(k)
                    .tipo(r.getTipo() == CategoriaFinanceiraEmpresa.TipoCategoria.RECEITA ? "receita" : "despesa")
                    .nome(r.getNomeCategoria())
                    .subcategorias(new ArrayList<>())
                    .dataCriacao(r.getDataCriacao())
                    .dataAtualizacao(r.getDataAtualizacao())
                    .build());
            if (r.getDataCriacao() != null && (dto.getDataCriacao() == null || r.getDataCriacao().isBefore(dto.getDataCriacao()))) {
                dto.setDataCriacao(r.getDataCriacao());
            }
            if (r.getDataAtualizacao() != null && (dto.getDataAtualizacao() == null || r.getDataAtualizacao().isAfter(dto.getDataAtualizacao()))) {
                dto.setDataAtualizacao(r.getDataAtualizacao());
            }
            if (r.getNomeSubcategoria() != null && !r.getNomeSubcategoria().isBlank()) {
                dto.getSubcategorias().add(SubcategoriaFinanceiraDTO.builder()
                        .id(r.getId())
                        .nome(r.getNomeSubcategoria())
                        .build());
            }
        }
        return map.values().stream()
                .peek(c -> c.setSubcategorias(c.getSubcategorias().stream()
                        .sorted(Comparator.comparing(SubcategoriaFinanceiraDTO::getNome, String.CASE_INSENSITIVE_ORDER))
                        .collect(Collectors.toList())))
                .sorted(Comparator
                        .comparing(CategoriaFinanceiraDTO::getTipo)
                        .thenComparing(CategoriaFinanceiraDTO::getNome, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    private record CategoriaKey(CategoriaFinanceiraEmpresa.TipoCategoria tipo, String nomeCategoria) {}

    private static CategoriaKey parseCategoriaKey(String idCategoria) {
        String raw = normalizar(idCategoria);
        int idx = raw.indexOf(':');
        if (idx <= 0 || idx >= raw.length() - 1) {
            throw new IllegalArgumentException("Identificador de categoria inválido.");
        }
        String tipo = raw.substring(0, idx);
        String nome = raw.substring(idx + 1);
        return new CategoriaKey(parseTipo(tipo), nome);
    }

    private static String buildCategoriaId(CategoriaFinanceiraEmpresa.TipoCategoria tipo, String nomeCategoria) {
        String t = tipo == CategoriaFinanceiraEmpresa.TipoCategoria.RECEITA ? "receita" : "despesa";
        return t + ":" + nomeCategoria;
    }
}
