package com.finnza.service;

import com.finnza.domain.entity.MovimentacaoFinanceira;
import com.finnza.domain.entity.OfxImportacao;
import com.finnza.domain.entity.CategoriaFinanceiraEmpresa;
import com.finnza.domain.entity.Cliente;
import com.finnza.domain.entity.FornecedorParam;
import com.finnza.repository.CategoriaFinanceiraEmpresaRepository;
import com.finnza.repository.ClienteRepository;
import com.finnza.repository.FornecedorParamRepository;
import com.finnza.repository.MovimentacaoFinanceiraRepository;
import com.finnza.repository.OfxImportacaoRepository;
import com.webcohesion.ofx4j.domain.data.MessageSetType;
import com.webcohesion.ofx4j.domain.data.ResponseEnvelope;
import com.webcohesion.ofx4j.domain.data.banking.BankingResponseMessageSet;
import com.webcohesion.ofx4j.domain.data.banking.BankStatementResponse;
import com.webcohesion.ofx4j.domain.data.banking.BankStatementResponseTransaction;
import com.webcohesion.ofx4j.domain.data.common.Transaction;
import com.webcohesion.ofx4j.domain.data.creditcard.CreditCardResponseMessageSet;
import com.webcohesion.ofx4j.domain.data.creditcard.CreditCardStatementResponse;
import com.webcohesion.ofx4j.domain.data.creditcard.CreditCardStatementResponseTransaction;
import com.webcohesion.ofx4j.io.AggregateUnmarshaller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfxImportService {
    private static final String OFX_RECEITA_PADRAO = "Entradas bancárias";
    private static final String OFX_DESPESA_PADRAO = "Saídas bancárias";
    private static final String OFX_RECEITA_LEGADO = "OFX - Receita importada";
    private static final String OFX_DESPESA_LEGADO = "OFX - Despesa importada";

    private final MovimentacaoFinanceiraRepository movimentacaoRepo;
    private final OfxImportacaoRepository ofxImportacaoRepository;
    private final CategoriaFinanceiraEmpresaRepository categoriaFinanceiraEmpresaRepository;
    private final FornecedorParamRepository fornecedorParamRepository;
    private final ClienteRepository clienteRepository;

    public record ImportResumo(
            int totalTransacoes,
            int importadas,
            int ignoradasDuplicadas,
            Long importacaoId,
            String conta,
            String moeda,
            LocalDate dataInicio,
            LocalDate dataFim
    ) {}

    public record AprovacaoResumo(
            Long importacaoId,
            String status,
            int aprovadasAgora,
            int conciliadasTotal,
            int pendentesTotal,
            int totalMovimentacoes
    ) {}

    public record BackfillResumo(
            int processadas,
            int categoriaPreenchida,
            int parceiroPreenchido
    ) {}

    @Transactional
    public ImportResumo importar(InputStream ofxStream, Integer idEmpresa, String nomeArquivo, String tipoImportacao) {
        return importar(ofxStream, idEmpresa, nomeArquivo, tipoImportacao, null, null);
    }

    /**
     * @param idContaFinanceiraVinculo opcional — id da conta bancária cadastrada no Finnzia (grava em {@code idContaFinanceira})
     * @param nomeContaFinanceiraPreferida opcional — nome amigável (ex.: nome curto do cadastro); se ausente, usa o número do OFX
     */
    @Transactional
    public ImportResumo importar(
            InputStream ofxStream,
            Integer idEmpresa,
            String nomeArquivo,
            String tipoImportacao,
            Integer idContaFinanceiraVinculo,
            String nomeContaFinanceiraPreferida
    ) {
        Objects.requireNonNull(ofxStream, "ofxStream");
        if (idEmpresa == null || idEmpresa <= 0) {
            throw new IllegalArgumentException("idEmpresa inválido");
        }

        ResponseEnvelope envelope = parseEnvelope(ofxStream);

        List<MovimentacaoFinanceira> candidatos = new ArrayList<>();

        // Banking
        try {
            Object msgSet = envelope.getMessageSet(MessageSetType.banking);
            if (msgSet instanceof BankingResponseMessageSet banking) {
                for (BankStatementResponseTransaction txResp : banking.getStatementResponses()) {
                    BankStatementResponse stmt = txResp.getMessage();
                    candidatos.addAll(mapStatement(
                            idEmpresa,
                            null,
                            stmt.getAccount() != null ? stmt.getAccount().getAccountNumber() : null,
                            idContaFinanceiraVinculo,
                            nomeContaFinanceiraPreferida,
                            stmt.getCurrencyCode(),
                            stmt.getTransactionList().getTransactions()));
                }
            }
        } catch (Exception e) {
            log.debug("OFX: sem message set banking ou falhou parse", e);
        }

        // Credit card
        try {
            Object msgSet = envelope.getMessageSet(MessageSetType.creditcard);
            if (msgSet instanceof CreditCardResponseMessageSet cc) {
                for (CreditCardStatementResponseTransaction txResp : cc.getStatementResponses()) {
                    CreditCardStatementResponse stmt = txResp.getMessage();
                    String conta = stmt.getAccount() != null ? stmt.getAccount().getAccountNumber() : null;
                    candidatos.addAll(mapStatement(
                            idEmpresa,
                            null,
                            conta,
                            idContaFinanceiraVinculo,
                            nomeContaFinanceiraPreferida,
                            stmt.getCurrencyCode(),
                            stmt.getTransactionList().getTransactions()));
                }
            }
        } catch (Exception e) {
            log.debug("OFX: sem message set creditcard ou falhou parse", e);
        }

        // Dedup por PK (idMovimentacao)
        List<String> ids = candidatos.stream().map(MovimentacaoFinanceira::getIdMovimentacao).filter(Objects::nonNull).distinct().toList();
        Set<String> existentes = new HashSet<>(movimentacaoRepo.findAllById(ids).stream()
                .map(MovimentacaoFinanceira::getIdMovimentacao)
                .collect(Collectors.toSet()));

        List<MovimentacaoFinanceira> novos = candidatos.stream()
                .filter(m -> m.getIdMovimentacao() != null && !existentes.contains(m.getIdMovimentacao()))
                .toList();

        LocalDate min = candidatos.stream().map(MovimentacaoFinanceira::getDataVencimento).filter(Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        LocalDate max = candidatos.stream().map(MovimentacaoFinanceira::getDataVencimento).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
        String conta = firstNonBlank(
                nomeContaFinanceiraPreferida,
                candidatos.stream().map(MovimentacaoFinanceira::getNomeContaFinanceira).filter(Objects::nonNull).findFirst().orElse(null));

        OfxImportacao importacao = ofxImportacaoRepository.save(OfxImportacao.builder()
                .idEmpresa(idEmpresa)
                .arquivoNome(nomeArquivo)
                .tipo(firstNonBlank(tipoImportacao, "MANUAL"))
                // Importação OFX entra como pré-aprovação (sem conciliação automática).
                .status("PENDENTE")
                .dataImportacao(LocalDateTime.now())
                .banco("OFX")
                .conta(conta)
                .periodoInicio(min)
                .periodoFim(max)
                .totalConciliadas(0)
                .totalIgnoradas(Math.max(0, candidatos.size() - novos.size()))
                .totalPendentes(novos.size())
                .total(candidatos.size())
                .build());

        if (!novos.isEmpty()) {
            novos.forEach(m -> m.setOfxImportacaoId(importacao.getId()));
            movimentacaoRepo.saveAll(novos);
        }

        return new ImportResumo(
                candidatos.size(),
                novos.size(),
                Math.max(0, candidatos.size() - novos.size()),
                importacao.getId(),
                conta,
                null,
                min,
                max
        );
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarImportacoes(Integer idEmpresa, LocalDate dataInicio, LocalDate dataFim, String status, String tipo, String conta) {
        LocalDate ini = dataInicio != null ? dataInicio : LocalDate.now().minusMonths(1);
        LocalDate fim = dataFim != null ? dataFim : LocalDate.now();
        List<OfxImportacao> rows = ofxImportacaoRepository.findByIdEmpresaAndDataImportacaoBetweenOrderByDataImportacaoDesc(
                idEmpresa,
                ini.atStartOfDay(),
                fim.plusDays(1).atStartOfDay().minusNanos(1)
        );

        return rows.stream()
                .filter(r -> status == null || status.isBlank() || status.equalsIgnoreCase(r.getStatus()))
                .filter(r -> tipo == null || tipo.isBlank() || tipo.equalsIgnoreCase(r.getTipo()))
                .filter(r -> {
                    if (conta == null || conta.isBlank()) return true;
                    if (r.getConta() == null) return false;
                    return r.getConta().equalsIgnoreCase(conta.trim());
                })
                .map(r -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", r.getId());
                    map.put("idEmpresa", r.getIdEmpresa());
                    map.put("nomeEmpresa", r.getNomeEmpresa());
                    map.put("arquivoNome", r.getArquivoNome());
                    map.put("tipo", r.getTipo());
                    map.put("status", r.getStatus());
                    map.put("dataImportacao", r.getDataImportacao() != null ? r.getDataImportacao().toString() : null);
                    map.put("banco", r.getBanco());
                    map.put("conta", r.getConta());
                    map.put("periodoInicio", r.getPeriodoInicio() != null ? r.getPeriodoInicio().toString() : null);
                    map.put("periodoFim", r.getPeriodoFim() != null ? r.getPeriodoFim().toString() : null);
                    map.put("conciliadas", r.getTotalConciliadas());
                    map.put("ignoradas", r.getTotalIgnoradas());
                    map.put("pendentes", r.getTotalPendentes());
                    map.put("total", r.getTotal());
                    return map;
                })
                .toList();
    }

    @Transactional
    public boolean excluirImportacao(Integer idEmpresa, Long importacaoId) {
        Optional<OfxImportacao> op = ofxImportacaoRepository.findById(importacaoId);
        if (op.isEmpty()) return false;
        OfxImportacao row = op.get();
        if (!Objects.equals(row.getIdEmpresa(), idEmpresa)) {
            throw new IllegalArgumentException("Importação não pertence à empresa selecionada");
        }
        movimentacaoRepo.deleteByIdEmpresaAndOfxImportacaoId(idEmpresa, importacaoId);
        ofxImportacaoRepository.deleteById(importacaoId);
        return true;
    }

    @Transactional
    public AprovacaoResumo aprovarImportacao(Integer idEmpresa, Long importacaoId) {
        Optional<OfxImportacao> op = ofxImportacaoRepository.findById(importacaoId);
        if (op.isEmpty()) {
            throw new IllegalArgumentException("Importação OFX não encontrada");
        }
        OfxImportacao row = op.get();
        if (!Objects.equals(row.getIdEmpresa(), idEmpresa)) {
            throw new IllegalArgumentException("Importação não pertence à empresa selecionada");
        }

        int pendentesAntes = safeInt(row.getTotalPendentes());
        int conciliadasAntes = safeInt(row.getTotalConciliadas());
        int aprovadasAgora = movimentacaoRepo.aprovarConciliacaoOfx(idEmpresa, importacaoId);
        int totalMovimentacoes = (int) movimentacaoRepo.countByIdEmpresaAndOfxImportacaoId(idEmpresa, importacaoId);

        int pendentesDepois = Math.max(0, pendentesAntes - aprovadasAgora);
        int conciliadasDepois = conciliadasAntes + aprovadasAgora;

        row.setTotalPendentes(pendentesDepois);
        row.setTotalConciliadas(conciliadasDepois);
        if (aprovadasAgora == 0 && conciliadasDepois == 0) {
            // Lote sem novos lançamentos para aprovar (ex.: importação 100% duplicada).
            row.setStatus("PENDENTE");
        } else if (pendentesDepois == 0) {
            row.setStatus("CONCILIADO");
        } else if (conciliadasDepois > 0) {
            row.setStatus("PARCIAL");
        } else {
            row.setStatus("PENDENTE");
        }
        ofxImportacaoRepository.save(row);

        return new AprovacaoResumo(
                row.getId(),
                row.getStatus(),
                aprovadasAgora,
                conciliadasDepois,
                pendentesDepois,
                totalMovimentacoes
        );
    }

    @Transactional
    public BackfillResumo backfillDadosOfx(Integer idEmpresa, int limite) {
        int limiteSeguro = Math.max(1, Math.min(limite, 5000));
        List<MovimentacaoFinanceira> pendentes = movimentacaoRepo.findOfxComDadosPendentes(
                idEmpresa,
                PageRequest.of(0, limiteSeguro)
        );
        if (pendentes.isEmpty()) {
            return new BackfillResumo(0, 0, 0);
        }

        int categoriaPreenchida = 0;
        int parceiroPreenchido = 0;
        for (MovimentacaoFinanceira mov : pendentes) {
            boolean tinhaCategoria = hasText(mov.getNomeCategoriaFinanceira());
            boolean tinhaParceiro = hasText(mov.getNomeClienteFornecedor());
            enriquecerDadosOfx(mov, idEmpresa);
            if (!tinhaCategoria && hasText(mov.getNomeCategoriaFinanceira())) categoriaPreenchida++;
            if (!tinhaParceiro && hasText(mov.getNomeClienteFornecedor())) parceiroPreenchido++;
        }
        movimentacaoRepo.saveAll(pendentes);
        return new BackfillResumo(pendentes.size(), categoriaPreenchida, parceiroPreenchido);
    }

    private ResponseEnvelope parseEnvelope(InputStream ofxStream) {
        try {
            AggregateUnmarshaller<ResponseEnvelope> unmarshaller = new AggregateUnmarshaller<>(ResponseEnvelope.class);
            // ofx4j aceita InputStream diretamente; charset é geralmente ISO-8859-1/UTF-8.
            // Usamos bytes puros e deixamos o parser lidar com headers OFX.
            return unmarshaller.unmarshal(ofxStream);
        } catch (Exception e) {
            throw new IllegalArgumentException("Não foi possível ler o OFX. Verifique se o arquivo é válido.", e);
        }
    }

    private List<MovimentacaoFinanceira> mapStatement(
            Integer idEmpresa,
            Long ofxImportacaoId,
            String accountNumberFromOfx,
            Integer idContaFinanceiraVinculo,
            String nomeContaFinanceiraPreferida,
            String currencyCode,
            List<?> txs
    ) {
        if (txs == null) return List.of();
        List<MovimentacaoFinanceira> out = new ArrayList<>();
        CategoriaClassifier receitaClassifier = new CategoriaClassifier(idEmpresa, false);
        CategoriaClassifier despesaClassifier = new CategoriaClassifier(idEmpresa, true);
        int idx = 0;
        for (Object obj : txs) {
            if (!(obj instanceof Transaction t)) {
                continue;
            }
            idx++;
            LocalDate posted = toLocalDate(t.getDatePosted());
            BigDecimal amount = t.getBigDecimalAmount();
            if (amount == null) continue;

            boolean debito = amount.signum() < 0;
            BigDecimal valor = amount.abs();

            String payeeNome = (t.getPayee() != null) ? t.getPayee().getName() : null;
            String memo = firstNonBlank(t.getMemo(), t.getName(), payeeNome);
            String fitId = firstNonBlank(t.getId(), t.getCheckNumber());
            String idMov = buildId(idEmpresa, fitId, posted, valor, idx);

            String rawAcct = accountNumberFromOfx != null ? accountNumberFromOfx.trim() : null;
            String nomeContaFin = firstNonBlank(nomeContaFinanceiraPreferida, rawAcct, "Conta OFX");
            String observacaoConta = null;
            if (rawAcct != null && !rawAcct.isBlank()
                    && nomeContaFin != null
                    && !rawAcct.equalsIgnoreCase(nomeContaFin)) {
                observacaoConta = "Identificador da conta no arquivo OFX: " + rawAcct;
            }

            String parceiroExtraido = extrairNomeParceiro(memo, payeeNome);
            ParceiroMatch parceiroMatch = resolverParceiroPorNome(idEmpresa, parceiroExtraido);
            CategoriaClassifier classifier = debito ? despesaClassifier : receitaClassifier;
            CategoriaMatch categoriaMatch = classifier.classificar(memo, payeeNome, parceiroMatch.nome());
            CategoriaFinanceiraEmpresa categoriaOfx = categoriaMatch.categoria();
            String observacaoClassificacao = montarObservacaoClassificacao(categoriaMatch);
            String observacaoFinal = firstNonBlank(observacaoConta, observacaoClassificacao);
            if (hasText(observacaoConta) && hasText(observacaoClassificacao)) {
                observacaoFinal = observacaoConta + " | " + observacaoClassificacao;
            }

            MovimentacaoFinanceira m = MovimentacaoFinanceira.builder()
                    .idMovimentacao(idMov)
                    .idEmpresa(idEmpresa)
                    .debito(debito)
                    .dataVencimento(posted)
                    .dataCompetencia(posted)
                    // Não marca quitação/conciliação no import inicial (pré-aprovação).
                    .dataQuitacao(null)
                    .dataConciliacao(null)
                    .valor(valor)
                    .formaPagamento(null)
                    .nomeFormaPagamento(null)
                    .tipoMovimentacao(null)
                    .nomeTipoMovimentacao(null)
                    .nome(memo != null ? memo : "Movimentação OFX")
                    .observacao(observacaoFinal)
                    .numeroParcela(1)
                    .quantidadeParcela(1)
                    .idCategoriaFinanceira(categoriaOfx != null ? Math.toIntExact(categoriaOfx.getId()) : null)
                    .nomeCategoriaFinanceira(categoriaOfx != null ? categoriaOfx.getNome() : null)
                    .idContaFinanceira(idContaFinanceiraVinculo != null && idContaFinanceiraVinculo > 0
                            ? idContaFinanceiraVinculo
                            : null)
                    .nomeContaFinanceira(nomeContaFin)
                    .nomeEmpresa(null)
                    .idCliente(parceiroMatch.idCliente())
                    .idFornecedor(parceiroMatch.idFornecedor())
                    .nomeClienteFornecedor(parceiroMatch.nome())
                    .statusPagamento("pendente")
                    .dadosRaw(null)
                    .sincronizadoEm(null)
                    .ofxImportacaoId(ofxImportacaoId)
                    .ofxAprovado(false)
                    .build();

            out.add(m);
        }
        return out;
    }

    /**
     * Categoria e cliente/fornecedor no mesmo padrão do import OFX, usando descrição finzzia + categoria Pluggy.
     */
    public void aplicarClassificacaoOpenFinancePluggy(MovimentacaoFinanceira mov, Integer idEmpresa, String pluggyCategory) {
        if (mov == null || idEmpresa == null || idEmpresa <= 0) {
            return;
        }
        boolean debito = Boolean.TRUE.equals(mov.getDebito());
        String memo = mov.getNome();
        if (hasText(pluggyCategory)) {
            if (!hasText(memo)) {
                memo = pluggyCategory;
            } else if (!memo.toLowerCase(Locale.ROOT).contains(pluggyCategory.toLowerCase(Locale.ROOT))) {
                memo = memo + " | " + pluggyCategory;
            }
        }
        if (!hasText(memo)) {
            memo = debito ? "Despesa bancária" : "Receita bancária";
        }
        String payeeNome = mov.getNomeClienteFornecedor();
        String parceiroExtraido = extrairNomeParceiro(memo, payeeNome);
        ParceiroMatch parceiroMatch = resolverParceiroPorNome(idEmpresa, firstNonBlank(parceiroExtraido, payeeNome));

        CategoriaClassifier despesaClassifier = new CategoriaClassifier(idEmpresa, true);
        CategoriaClassifier receitaClassifier = new CategoriaClassifier(idEmpresa, false);
        CategoriaClassifier classifier = debito ? despesaClassifier : receitaClassifier;
        CategoriaMatch categoriaMatch = classifier.classificar(memo, payeeNome, parceiroMatch.nome());
        CategoriaFinanceiraEmpresa categoriaOfx = categoriaMatch.categoria();

        if (categoriaOfx != null) {
            mov.setIdCategoriaFinanceira(Math.toIntExact(categoriaOfx.getId()));
            mov.setNomeCategoriaFinanceira(categoriaOfx.getNome());
        }

        if (parceiroMatch.idFornecedor() != null) {
            mov.setIdFornecedor(parceiroMatch.idFornecedor());
            mov.setIdCliente(null);
        } else if (parceiroMatch.idCliente() != null) {
            mov.setIdCliente(parceiroMatch.idCliente());
            mov.setIdFornecedor(null);
        }

        if (hasText(parceiroMatch.nome())) {
            mov.setNomeClienteFornecedor(parceiroMatch.nome());
        } else if (hasText(payeeNome)) {
            mov.setNomeClienteFornecedor(payeeNome.trim());
        }

        String obsClass = montarObservacaoClassificacao(categoriaMatch);
        if (hasText(obsClass)) {
            String obs = mov.getObservacao();
            mov.setObservacao(hasText(obs) ? obs + " | " + obsClass : obsClass);
        }
    }

    private String montarObservacaoClassificacao(CategoriaMatch categoriaMatch) {
        if (categoriaMatch == null || !hasText(categoriaMatch.origem())) {
            return null;
        }
        return "Classificação automática: " + categoriaMatch.origem();
    }

    private void enriquecerDadosOfx(MovimentacaoFinanceira mov, Integer idEmpresa) {
        CategoriaFinanceiraEmpresa categoria = obterOuCriarCategoriaOfx(idEmpresa, Boolean.TRUE.equals(mov.getDebito()));
        if ((!hasText(mov.getNomeCategoriaFinanceira()) || isCategoriaOfxLegada(mov.getNomeCategoriaFinanceira())) && categoria != null) {
            mov.setNomeCategoriaFinanceira(categoria.getNome());
        }
        if (mov.getIdCategoriaFinanceira() == null && categoria != null) {
            mov.setIdCategoriaFinanceira(Math.toIntExact(categoria.getId()));
        }

        String nomeBase = firstNonBlank(mov.getNomeClienteFornecedor(), extrairNomeParceiro(mov.getNome(), null));
        if (hasText(nomeBase)) {
            ParceiroMatch parceiro = resolverParceiroPorNome(idEmpresa, nomeBase);
            if (!hasText(mov.getNomeClienteFornecedor()) && hasText(parceiro.nome())) {
                mov.setNomeClienteFornecedor(parceiro.nome());
            }
            if (mov.getIdFornecedor() == null && parceiro.idFornecedor() != null) {
                mov.setIdFornecedor(parceiro.idFornecedor());
                mov.setIdCliente(null);
            } else if (mov.getIdCliente() == null && parceiro.idCliente() != null) {
                mov.setIdCliente(parceiro.idCliente());
                mov.setIdFornecedor(null);
            }
        }
    }

    private CategoriaFinanceiraEmpresa obterOuCriarCategoriaOfx(Integer idEmpresa, boolean debito) {
        CategoriaFinanceiraEmpresa.TipoCategoria tipo = debito
                ? CategoriaFinanceiraEmpresa.TipoCategoria.DESPESA
                : CategoriaFinanceiraEmpresa.TipoCategoria.RECEITA;
        String nomePadrao = debito ? OFX_DESPESA_PADRAO : OFX_RECEITA_PADRAO;
        String nomeLegado = debito ? OFX_DESPESA_LEGADO : OFX_RECEITA_LEGADO;

        return categoriaFinanceiraEmpresaRepository
                .findFirstByDeletedFalseAndIdEmpresaAndTipoAndParentIdIsNullAndNomeIgnoreCase(idEmpresa, tipo, nomePadrao)
                .orElseGet(() -> {
                    Optional<CategoriaFinanceiraEmpresa> legado =
                            categoriaFinanceiraEmpresaRepository
                                    .findFirstByDeletedFalseAndIdEmpresaAndTipoAndParentIdIsNullAndNomeIgnoreCase(
                                            idEmpresa, tipo, nomeLegado);
                    if (legado.isPresent()) {
                        CategoriaFinanceiraEmpresa categoria = legado.get();
                        categoria.setNome(nomePadrao);
                        return categoriaFinanceiraEmpresaRepository.save(categoria);
                    }

                    Integer mx = categoriaFinanceiraEmpresaRepository.findMaxOrdemRaiz(idEmpresa, tipo);
                    int ordem = (mx == null ? -1 : mx) + 1;
                    return categoriaFinanceiraEmpresaRepository.save(CategoriaFinanceiraEmpresa.builder()
                            .idEmpresa(idEmpresa)
                            .tipo(tipo)
                            .nome(nomePadrao)
                            .parentId(null)
                            .ordem(ordem)
                            .build());
                });
    }

    private ParceiroMatch resolverParceiroPorNome(Integer idEmpresa, String nomeInformado) {
        String nome = sanitizeNomeParceiro(nomeInformado);
        if (!hasText(nome)) return new ParceiroMatch(null, null, null);

        List<FornecedorParam> fornecedores = fornecedorParamRepository.findByNomeNaEmpresa(idEmpresa, nome);
        if (!fornecedores.isEmpty()) {
            FornecedorParam f = fornecedores.get(0);
            return new ParceiroMatch(null, f.getId() != null ? Math.toIntExact(f.getId()) : null, firstNonBlank(f.getRazaoSocial(), f.getNomeFantasia(), nome));
        }

        List<Cliente> clientes = clienteRepository.findByNomeNaEmpresa(idEmpresa, nome);
        if (!clientes.isEmpty()) {
            Cliente c = clientes.get(0);
            return new ParceiroMatch(c.getId() != null ? Math.toIntExact(c.getId()) : null, null, firstNonBlank(c.getRazaoSocial(), c.getNomeFantasia(), nome));
        }
        return new ParceiroMatch(null, null, nome);
    }

    private String extrairNomeParceiro(String memo, String payeeNome) {
        String bruto = firstNonBlank(payeeNome, memo);
        if (!hasText(bruto)) return null;
        String candidato = bruto;

        int posTraço = candidato.indexOf(" - ");
        if (posTraço > 0) {
            candidato = candidato.substring(0, posTraço);
        }
        int posBarra = candidato.indexOf('/');
        if (posBarra > 0) {
            candidato = candidato.substring(0, posBarra);
        }
        return sanitizeNomeParceiro(candidato);
    }

    private String sanitizeNomeParceiro(String valor) {
        if (!hasText(valor)) return null;
        String s = valor.trim().replaceAll("\\s+", " ");
        s = s.replaceAll("(?i)^(pix|ted|doc|debito|credito)\\s+", "");
        s = s.replaceAll("(?i)\\b(pgto|pagamento|transferencia|transferência|compra)\\b", "").trim();
        return hasText(s) ? s : null;
    }

    private static boolean hasText(String v) {
        return v != null && !v.isBlank();
    }

    private boolean isCategoriaOfxLegada(String nomeCategoria) {
        if (!hasText(nomeCategoria)) {
            return false;
        }
        String normalized = nomeCategoria.trim();
        return OFX_RECEITA_LEGADO.equalsIgnoreCase(normalized) || OFX_DESPESA_LEGADO.equalsIgnoreCase(normalized);
    }

    private final class CategoriaClassifier {
        private final boolean debito;
        private final CategoriaFinanceiraEmpresa fallbackCategoria;
        private final List<CategoriaFinanceiraEmpresa> categorias;
        private final Map<String, CategoriaFinanceiraEmpresa> nomeExato;

        private CategoriaClassifier(Integer idEmpresa, boolean debito) {
            this.debito = debito;
            this.fallbackCategoria = obterOuCriarCategoriaOfx(idEmpresa, debito);
            CategoriaFinanceiraEmpresa.TipoCategoria tipo = debito
                    ? CategoriaFinanceiraEmpresa.TipoCategoria.DESPESA
                    : CategoriaFinanceiraEmpresa.TipoCategoria.RECEITA;
            this.categorias = categoriaFinanceiraEmpresaRepository
                    .findAllByDeletedFalseAndIdEmpresaOrderByTipoAscParentIdAscOrdemAscNomeAsc(idEmpresa)
                    .stream()
                    .filter(c -> c.getTipo() == tipo)
                    .collect(Collectors.toList());
            this.nomeExato = new LinkedHashMap<>();
            for (CategoriaFinanceiraEmpresa c : categorias) {
                nomeExato.putIfAbsent(normalize(c.getNome()), c);
            }
        }

        private CategoriaMatch classificar(String memo, String payee, String parceiroNome) {
            String texto = normalize(String.join(" ", List.of(
                    firstNonBlank(memo, ""),
                    firstNonBlank(payee, ""),
                    firstNonBlank(parceiroNome, "")
            )));
            if (!hasText(texto)) {
                return new CategoriaMatch(fallbackCategoria, "fallback-sem-texto");
            }

            CategoriaFinanceiraEmpresa porNome = nomeExato.get(texto);
            if (porNome != null) {
                return new CategoriaMatch(porNome, "nome-exato");
            }

            CategoriaFinanceiraEmpresa porCategoriaContida = categorias.stream()
                    .filter(c -> {
                        String nome = normalize(c.getNome());
                        return nome.length() >= 4 && texto.contains(nome);
                    })
                    .findFirst()
                    .orElse(null);
            if (porCategoriaContida != null) {
                return new CategoriaMatch(porCategoriaContida, "categoria-contida");
            }

            LinkedHashMap<String, List<String>> regras = regrasProfissionais(this.debito);
            for (Map.Entry<String, List<String>> regra : regras.entrySet()) {
                boolean ok = regra.getValue().stream().anyMatch(texto::contains);
                if (!ok) continue;
                CategoriaFinanceiraEmpresa porRegra = buscarCategoriaPorNomeAproximado(regra.getKey());
                if (porRegra != null) {
                    return new CategoriaMatch(porRegra, "regra-" + regra.getKey());
                }
            }

            return new CategoriaMatch(fallbackCategoria, "fallback-ofx");
        }

        private CategoriaFinanceiraEmpresa buscarCategoriaPorNomeAproximado(String target) {
            String alvo = normalize(target);
            CategoriaFinanceiraEmpresa exata = nomeExato.get(alvo);
            if (exata != null) {
                return exata;
            }
            return categorias.stream()
                    .filter(c -> {
                        String nome = normalize(c.getNome());
                        return nome.contains(alvo) || alvo.contains(nome);
                    })
                    .findFirst()
                    .orElse(null);
        }

        private LinkedHashMap<String, List<String>> regrasProfissionais(boolean debito) {
            LinkedHashMap<String, List<String>> regras = new LinkedHashMap<>();
            if (debito) {
                regras.put("Tráfego Pago", List.of("meta ads", "facebook ads", "google ads", "trafego pago", "ads"));
                regras.put("Impostos", List.of("darj", "simples nacional", "imposto", "darf", "tributo"));
                regras.put("Folha / Salário", List.of("salario", "folha", "pro labore", "adiantamento salarial"));
                regras.put("Tarifas Bancárias", List.of("tarifa", "cesta", "manutencao conta", "juros", "iof"));
                regras.put("Fornecedores", List.of("pagamento fornecedor", "fornecedor", "nf "));
            } else {
                regras.put("Receita de Contratos", List.of("mensalidade", "assinatura", "recebimento cliente", "fatura recebida"));
                regras.put("Recebimentos", List.of("pix recebido", "transferencia recebida", "credito em conta"));
                regras.put("Vendas", List.of("venda", "receita", "pagamento cliente"));
            }
            return regras;
        }
    }

    private record CategoriaMatch(CategoriaFinanceiraEmpresa categoria, String origem) {}

    private String normalize(String valor) {
        if (!hasText(valor)) {
            return "";
        }
        String noAccents = Normalizer.normalize(valor, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return noAccents.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s/.-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record ParceiroMatch(Integer idCliente, Integer idFornecedor, String nome) {}

    private static LocalDate toLocalDate(Date d) {
        if (d == null) return null;
        return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static String buildId(Integer idEmpresa, String fitId, LocalDate posted, BigDecimal valor, int idx) {
        String base = (fitId != null && !fitId.isBlank())
                ? fitId.trim()
                : String.format("ofx:%s:%s:%s:%d", idEmpresa, posted, valor, idx);
        // Evita colisão entre empresas
        String scoped = idEmpresa + ":" + base;
        return "ofx:" + sha256_16(scoped);
    }

    private static String sha256_16(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16 && i < dig.length; i++) {
                sb.append(String.format("%02x", dig[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
    }

    private static int safeInt(Integer value) {
        return value != null ? value : 0;
    }
}

