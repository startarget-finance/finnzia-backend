package com.finnza.service;

import com.finnza.domain.entity.MovimentacaoHistorico;
import com.finnza.dto.response.MovimentacaoHistoricoDTO;
import com.finnza.repository.MovimentacaoHistoricoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional
public class MovimentacaoHistoricoService {

    private final MovimentacaoHistoricoRepository repository;
    private final ErpFinanceiroService erpFinanceiroService;

    @Transactional(readOnly = true)
    public Map<String, Object> listar(
            Integer idEmpresa,
            String acao,
            LocalDate dataInicio,
            LocalDate dataFim,
            Integer itensPorPagina,
            Integer numeroDaPagina
    ) {
        int pageSize = Math.max(1, itensPorPagina != null ? itensPorPagina : 20);
        int pageNumber = Math.max(1, numeroDaPagina != null ? numeroDaPagina : 1);

        LocalDateTime inicio = dataInicio != null ? dataInicio.atStartOfDay() : null;
        LocalDateTime fim = dataFim != null ? dataFim.atTime(23, 59, 59) : null;
        String acaoFiltro = (acao == null || acao.isBlank()) ? null : acao.trim().toUpperCase();
        if (acaoFiltro != null && !acaoFiltro.equals("CRIACAO") && !acaoFiltro.equals("EDICAO")) {
            acaoFiltro = null;
        }

        var pageable = PageRequest.of(pageNumber - 1, pageSize, Sort.by(Sort.Direction.DESC, "dataEvento"));
        Page<MovimentacaoHistorico> page;
        boolean temPeriodo = inicio != null || fim != null;
        if (temPeriodo) {
            LocalDateTime inicioEfetivo = inicio != null ? inicio : LocalDateTime.of(1970, 1, 1, 0, 0, 0);
            LocalDateTime fimEfetivo = fim != null ? fim : LocalDateTime.of(2999, 12, 31, 23, 59, 59);
            if (acaoFiltro != null) {
                page = repository.findByIdEmpresaAndAcaoAndDataEventoBetween(
                        idEmpresa, acaoFiltro, inicioEfetivo, fimEfetivo, pageable
                );
            } else {
                page = repository.findByIdEmpresaAndDataEventoBetween(
                        idEmpresa, inicioEfetivo, fimEfetivo, pageable
                );
            }
        } else if (acaoFiltro != null) {
            page = repository.findByIdEmpresaAndAcao(idEmpresa, acaoFiltro, pageable);
        } else {
            page = repository.findByIdEmpresa(idEmpresa, pageable);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("itens", page.getContent().stream().map(this::toDto).toList());
        result.put("paginacao", Map.of(
                "itensPorPagina", pageSize,
                "numeroDaPagina", pageNumber,
                "totalItens", page.getTotalElements(),
                "totalPaginas", page.getTotalPages()
        ));
        return result;
    }

    public void registrarCriacao(
            Integer idEmpresa,
            String origemMovimentacaoId,
            Boolean debito,
            LocalDate dataVencimento,
            LocalDate dataCompetencia,
            LocalDate dataQuitacao,
            BigDecimal valor,
            String nome,
            String observacao,
            String nomeCategoriaFinanceira,
            String nomeContaFinanceira,
            String nomeClienteFornecedor
    ) {
        if (idEmpresa == null) return;
        MovimentacaoHistorico item = MovimentacaoHistorico.builder()
                .idEmpresa(idEmpresa)
                .acao("CRIACAO")
                .origemMovimentacaoId(origemMovimentacaoId)
                .descricao(nome)
                .debito(debito)
                .dataVencimento(dataVencimento)
                .dataCompetencia(dataCompetencia)
                .dataQuitacao(dataQuitacao)
                .valor(valor)
                .nome(nome)
                .observacao(observacao)
                .nomeCategoriaFinanceira(nomeCategoriaFinanceira)
                .nomeContaFinanceira(nomeContaFinanceira)
                .nomeClienteFornecedor(nomeClienteFornecedor)
                .build();
        repository.save(item);
    }

    public void registrarEdicao(
            Integer idEmpresa,
            String origemMovimentacaoId,
            Boolean debito,
            LocalDate dataVencimento,
            LocalDate dataCompetencia,
            LocalDate dataQuitacao,
            BigDecimal valor,
            String nome,
            String observacao,
            String nomeCategoriaFinanceira,
            String nomeContaFinanceira,
            String nomeClienteFornecedor
    ) {
        if (idEmpresa == null) return;
        MovimentacaoHistorico item = MovimentacaoHistorico.builder()
                .idEmpresa(idEmpresa)
                .acao("EDICAO")
                .origemMovimentacaoId(origemMovimentacaoId)
                .descricao(nome)
                .debito(debito)
                .dataVencimento(dataVencimento)
                .dataCompetencia(dataCompetencia)
                .dataQuitacao(dataQuitacao)
                .valor(valor)
                .nome(nome)
                .observacao(observacao)
                .nomeCategoriaFinanceira(nomeCategoriaFinanceira)
                .nomeContaFinanceira(nomeContaFinanceira)
                .nomeClienteFornecedor(nomeClienteFornecedor)
                .build();
        repository.save(item);
    }

    public Map<String, Object> restaurar(Integer idEmpresa, Long idHistorico) {
        MovimentacaoHistorico item = repository.findByIdAndIdEmpresa(idHistorico, idEmpresa)
                .orElseThrow(() -> new IllegalArgumentException("Item do histórico não encontrado"));

        Map<String, Object> novaMov = erpFinanceiroService.criarMovimentacaoManual(
                idEmpresa,
                item.getDebito(),
                item.getDataVencimento(),
                item.getDataCompetencia(),
                item.getDataQuitacao(),
                item.getValor(),
                item.getNome(),
                item.getObservacao(),
                item.getNomeCategoriaFinanceira(),
                item.getNomeContaFinanceira(),
                item.getNomeClienteFornecedor()
        );

        item.setRestauradoEm(LocalDateTime.now());
        repository.save(item);
        return novaMov;
    }

    private MovimentacaoHistoricoDTO toDto(MovimentacaoHistorico item) {
        return MovimentacaoHistoricoDTO.builder()
                .id(item.getId())
                .idEmpresa(item.getIdEmpresa())
                .acao(item.getAcao())
                .origemMovimentacaoId(item.getOrigemMovimentacaoId())
                .dataEvento(item.getDataEvento())
                .descricao(item.getDescricao())
                .restauradoEm(item.getRestauradoEm())
                .debito(item.getDebito())
                .dataVencimento(item.getDataVencimento())
                .dataCompetencia(item.getDataCompetencia())
                .dataQuitacao(item.getDataQuitacao())
                .valor(item.getValor())
                .nome(item.getNome())
                .observacao(item.getObservacao())
                .nomeCategoriaFinanceira(item.getNomeCategoriaFinanceira())
                .nomeContaFinanceira(item.getNomeContaFinanceira())
                .nomeClienteFornecedor(item.getNomeClienteFornecedor())
                .build();
    }

}
