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
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfxImportService {

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
            CategoriaFinanceiraEmpresa categoriaOfx = obterOuCriarCategoriaOfx(idEmpresa, debito);
            ParceiroMatch parceiroMatch = resolverParceiroPorNome(idEmpresa, parceiroExtraido);

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
                    .observacao(observacaoConta)
                    .numeroParcela(1)
                    .quantidadeParcela(1)
                    .idCategoriaFinanceira(categoriaOfx != null ? Math.toIntExact(categoriaOfx.getId()) : null)
                    .nomeCategoriaFinanceira(categoriaOfx != null ? categoriaOfx.getNomeCategoria() : null)
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

    private void enriquecerDadosOfx(MovimentacaoFinanceira mov, Integer idEmpresa) {
        CategoriaFinanceiraEmpresa categoria = obterOuCriarCategoriaOfx(idEmpresa, Boolean.TRUE.equals(mov.getDebito()));
        if (!hasText(mov.getNomeCategoriaFinanceira()) && categoria != null) {
            mov.setNomeCategoriaFinanceira(categoria.getNomeCategoria());
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
        String nome = debito ? "OFX - Despesa importada" : "OFX - Receita importada";
        return categoriaFinanceiraEmpresaRepository
                .findFirstByDeletedFalseAndIdEmpresaAndTipoAndNomeCategoriaIgnoreCaseAndNomeSubcategoriaIsNull(idEmpresa, tipo, nome)
                .orElseGet(() -> categoriaFinanceiraEmpresaRepository.save(CategoriaFinanceiraEmpresa.builder()
                        .idEmpresa(idEmpresa)
                        .tipo(tipo)
                        .nomeCategoria(nome)
                        .nomeSubcategoria(null)
                        .build()));
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

