package com.finnza.service;

import com.finnza.domain.entity.MovimentacaoFinanceira;
import com.finnza.domain.entity.OfxImportacao;
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

    @Transactional
    public ImportResumo importar(InputStream ofxStream, Integer idEmpresa, String nomeArquivo, String tipoImportacao) {
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
                    candidatos.addAll(mapStatement(idEmpresa, null, stmt.getAccount().getAccountNumber(), stmt.getCurrencyCode(), stmt.getTransactionList().getTransactions()));
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
                    candidatos.addAll(mapStatement(idEmpresa, null, conta, stmt.getCurrencyCode(), stmt.getTransactionList().getTransactions()));
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
        String conta = candidatos.stream().map(MovimentacaoFinanceira::getNomeContaFinanceira).filter(Objects::nonNull).findFirst().orElse(null);

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
            String accountNumber,
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
                    .observacao(null)
                    .numeroParcela(1)
                    .quantidadeParcela(1)
                    .idCategoriaFinanceira(null)
                    .nomeCategoriaFinanceira(null)
                    .idContaFinanceira(null)
                    .nomeContaFinanceira(accountNumber)
                    .nomeEmpresa(null)
                    .idCliente(null)
                    .idFornecedor(null)
                    .nomeClienteFornecedor(null)
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

