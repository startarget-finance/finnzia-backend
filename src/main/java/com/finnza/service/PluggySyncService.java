package com.finnza.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.finnza.domain.entity.MovimentacaoFinanceira;
import com.finnza.domain.entity.OfxImportacao;
import com.finnza.domain.entity.PluggyConexao;
import com.finnza.domain.entity.Usuario;
import com.finnza.dto.request.PluggySyncRequest;
import com.finnza.dto.response.PluggySyncResponse;
import com.finnza.integration.pluggy.PluggyApiClient;
import com.finnza.repository.MovimentacaoFinanceiraRepository;
import com.finnza.repository.OfxImportacaoRepository;
import com.finnza.repository.PluggyConexaoRepository;
import com.finnza.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PluggySyncService {

    /** Período máximo (dias corridos, inclusive) por requisição de sync. */
    private static final int MAX_DIAS_SYNC = 90;

    /** Máximo de páginas Pluggy por conta (500 tx/página) para não estourar timeout do host. */
    private static final int MAX_PAGINAS_TRANSACOES_POR_CONTA = 12;

    private final PluggyApiClient pluggyApiClient;
    private final PluggyConexaoRepository pluggyConexaoRepository;
    private final UsuarioRepository usuarioRepository;
    private final MovimentacaoFinanceiraRepository movimentacaoRepo;
    private final OfxImportacaoRepository ofxImportacaoRepository;
    private final OfxImportService ofxImportService;
    private final UsuarioEmpresaService usuarioEmpresaService;

    @Transactional
    public PluggySyncResponse sincronizarTransacoes(String email, Long conexaoId, Integer idEmpresa, PluggySyncRequest request) {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(conexaoId, "conexaoId");
        if (idEmpresa == null || idEmpresa <= 0) {
            throw new IllegalArgumentException("Empresa inválida para sincronizar Pluggy");
        }
        if (usuarioEmpresaService.usuarioTemEmpresasAtivasPorEmail(email)) {
            if (!usuarioEmpresaService.validarAcessoUsuarioEmpresa(email, idEmpresa)) {
                throw new IllegalArgumentException("Sem permissão para esta empresa");
            }
        }

        Usuario usuario = usuarioRepository.findByEmail(email).orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        PluggyConexao conexao = pluggyConexaoRepository
                .findByIdAndUsuario_Id(conexaoId, usuario.getId())
                .orElseThrow(() -> new IllegalArgumentException("Conexão Pluggy não encontrada"));

        LocalDate fim = request != null && request.getDataFim() != null ? request.getDataFim() : LocalDate.now();
        // Últimos 90 dias inclusive: hoje e mais 89 para trás (minusDays(90) gerava 91 dias com a validação abaixo).
        LocalDate ini = request != null && request.getDataInicio() != null
                ? request.getDataInicio()
                : fim.minusDays(MAX_DIAS_SYNC - 1L);
        if (ini.isAfter(fim)) {
            throw new IllegalArgumentException("dataInicio não pode ser posterior a dataFim");
        }
        long diasPeriodo = ChronoUnit.DAYS.between(ini, fim) + 1;
        if (diasPeriodo > MAX_DIAS_SYNC) {
            throw new IllegalArgumentException(
                    "Período máximo de " + MAX_DIAS_SYNC + " dias por sincronização (atual: " + diasPeriodo + " dias). "
                            + "Reduza o intervalo entre data início e data fim ou sincronize em partes.");
        }

        Integer idConta = request != null ? request.getIdContaBancaria() : null;
        String nomeContaPref = request != null ? trimToNull(request.getNomeContaExibicao()) : null;

        String apiKey = pluggyApiClient.getOrCreateApiKey();
        String itemId = conexao.getPluggyItemId();

        JsonNode accountsRoot = pluggyApiClient.listAccounts(apiKey, itemId);
        JsonNode results = accountsRoot.get("results");
        if (results == null || !results.isArray()) {
            throw new IllegalStateException("Resposta Pluggy /accounts sem results");
        }

        String from = ini.toString();
        String to = fim.toString();
        log.info("Pluggy sync: item={} empresa={} período {} a {} ({} dias)", itemId, idEmpresa, from, to, diasPeriodo);

        List<MovimentacaoFinanceira> candidatos = new ArrayList<>();
        for (JsonNode acc : results) {
            if (acc == null || !acc.hasNonNull("id")) {
                continue;
            }
            String accountId = acc.get("id").asText();
            String accountLabel = accountLabel(acc);
            List<JsonNode> txs = fetchAllTransactions(apiKey, accountId, from, to);
            for (JsonNode tx : txs) {
                MovimentacaoFinanceira m = mapTransaction(idEmpresa, itemId, accountLabel, idConta, nomeContaPref, tx);
                if (m != null) {
                    candidatos.add(m);
                }
            }
        }

        List<String> ids = candidatos.stream().map(MovimentacaoFinanceira::getIdMovimentacao).filter(Objects::nonNull).distinct().toList();
        Set<String> existentes = new HashSet<>(
                movimentacaoRepo.findAllById(ids).stream().map(MovimentacaoFinanceira::getIdMovimentacao).collect(Collectors.toSet()));

        List<MovimentacaoFinanceira> novos = candidatos.stream()
                .filter(m -> m.getIdMovimentacao() != null && !existentes.contains(m.getIdMovimentacao()))
                .toList();

        LocalDate min = candidatos.stream()
                .map(MovimentacaoFinanceira::getDataVencimento)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(ini);
        LocalDate max = candidatos.stream()
                .map(MovimentacaoFinanceira::getDataVencimento)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(fim);

        String nomeLote = "finzzia · Open Finance · " + itemId.substring(0, Math.min(8, itemId.length())) + " (" + ini + " a " + fim + ")";
        String contaNome = firstNonBlank(
                nomeContaPref,
                candidatos.stream().map(MovimentacaoFinanceira::getNomeContaFinanceira).filter(Objects::nonNull).findFirst().orElse(null),
                "Open Finance");

        OfxImportacao importacao = ofxImportacaoRepository.save(OfxImportacao.builder()
                .idEmpresa(idEmpresa)
                .arquivoNome(nomeLote)
                .tipo("PLUGGY")
                .status("PENDENTE")
                .dataImportacao(LocalDateTime.now())
                .banco("Pluggy")
                .conta(contaNome)
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
            // Classificação já aplicada em mapTransaction; backfill síncrono estourava timeout no Render.
        }

        return PluggySyncResponse.builder()
                .totalPluggy(candidatos.size())
                .importadas(novos.size())
                .ignoradasDuplicadas(Math.max(0, candidatos.size() - novos.size()))
                .importacaoId(importacao.getId())
                .conta(contaNome)
                .periodoInicio(min)
                .periodoFim(max)
                .build();
    }

    private List<JsonNode> fetchAllTransactions(String apiKey, String accountId, String from, String to) {
        List<JsonNode> out = new ArrayList<>();
        int page = 1;
        while (true) {
            JsonNode pageRes = pluggyApiClient.listTransactions(apiKey, accountId, from, to, page, 500);
            JsonNode arr = pageRes.get("results");
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    if (n != null && !n.isNull()) {
                        out.add(n);
                    }
                }
            }
            int totalPages = pageRes.has("totalPages") ? Math.max(1, pageRes.get("totalPages").asInt(1)) : 1;
            if (page >= totalPages) {
                break;
            }
            page++;
            if (page >= MAX_PAGINAS_TRANSACOES_POR_CONTA) {
                log.warn("Pluggy sync: limite de {} páginas na conta {} (período {} a {})",
                        MAX_PAGINAS_TRANSACOES_POR_CONTA, accountId, from, to);
                break;
            }
            if (page > 10_000) {
                log.warn("Pluggy sync: interrompido por limite de segurança na conta {}", accountId);
                break;
            }
        }
        return out;
    }

    private MovimentacaoFinanceira mapTransaction(
            Integer idEmpresa,
            String itemId,
            String accountLabel,
            Integer idConta,
            String nomeContaPref,
            JsonNode tx
    ) {
        if (!tx.hasNonNull("id")) {
            return null;
        }
        String txId = tx.get("id").asText().trim();
        if (txId.isEmpty()) {
            return null;
        }

        BigDecimal signed = pickSignedAmount(tx);
        if (signed == null || signed.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        boolean debito = signed.signum() < 0;
        BigDecimal valor = signed.abs();

        LocalDate d = parseTxDate(tx);
        if (d == null) {
            return null;
        }

        String merchant = null;
        if (tx.has("merchant") && tx.get("merchant").isObject()) {
            JsonNode mer = tx.get("merchant");
            if (mer.hasNonNull("name")) {
                merchant = mer.get("name").asText().trim();
            } else if (mer.hasNonNull("businessName")) {
                merchant = mer.get("businessName").asText().trim();
            }
        }

        String contrapartePagamento = nomeContrapartePagamento(tx);
        String nomeCliente = firstNonBlank(merchant, contrapartePagamento);

        String nome = montarNomeExibicaoPluggy(tx, merchant);
        String observacao = montarObservacaoPluggy(itemId, accountLabel, txId, tx);

        String idMov = "pluggy:" + idEmpresa + ":" + txId;
        String nomeConta = firstNonBlank(nomeContaPref, accountLabel, "Conta Open Finance");

        MovimentacaoFinanceira mov = MovimentacaoFinanceira.builder()
                .idMovimentacao(idMov)
                .idEmpresa(idEmpresa)
                .debito(debito)
                .dataVencimento(d)
                .dataCompetencia(d)
                .dataQuitacao(null)
                .dataConciliacao(null)
                .valor(valor)
                .formaPagamento(null)
                .nomeFormaPagamento(null)
                .tipoMovimentacao(null)
                .nomeTipoMovimentacao(null)
                .nome(nome.length() > 500 ? nome.substring(0, 500) : nome)
                .observacao(observacao.length() > 4000 ? observacao.substring(0, 4000) : observacao)
                .numeroParcela(1)
                .quantidadeParcela(1)
                .idCategoriaFinanceira(null)
                .nomeCategoriaFinanceira(null)
                .idContaFinanceira(idConta != null && idConta > 0 ? idConta : null)
                .nomeContaFinanceira(nomeConta)
                .nomeEmpresa(null)
                .idCliente(null)
                .idFornecedor(null)
                .nomeClienteFornecedor(nomeCliente)
                .statusPagamento("pendente")
                .dadosRaw(null)
                .sincronizadoEm(null)
                .ofxImportacaoId(null)
                .ofxAprovado(false)
                .build();

        ofxImportService.aplicarClassificacaoOpenFinancePluggy(mov, idEmpresa, textNode(tx, "category"));
        return mov;
    }

    /** Nome em TED/PIX etc. (campo {@code paymentData} da Pluggy). */
    private static String nomeContrapartePagamento(JsonNode tx) {
        if (tx == null || !tx.has("paymentData") || !tx.get("paymentData").isObject()) {
            return null;
        }
        JsonNode pd = tx.get("paymentData");
        String payer = nomeDeParty(pd.get("payer"));
        String receiver = nomeDeParty(pd.get("receiver"));
        return firstNonBlank(receiver, payer);
    }

    private static String nomeDeParty(JsonNode party) {
        if (party == null || !party.isObject()) {
            return null;
        }
        if (party.hasNonNull("name")) {
            String n = party.get("name").asText().trim();
            return n.isEmpty() ? null : n;
        }
        return null;
    }

    private static String montarNomeExibicaoPluggy(JsonNode tx, String merchant) {
        String descRaw = textNode(tx, "descriptionRaw");
        String desc = textNode(tx, "description");
        String category = textNode(tx, "category");
        String operationType = textNode(tx, "operationType");

        String candidato = firstNonBlank(nonGenericOrNull(descRaw), nonGenericOrNull(desc), category);
        if (candidato == null) {
            candidato = firstNonBlank(descRaw, desc, category, "Transação Open Finance");
        }

        String nomeBase = candidato;
        if (merchant != null && !merchant.isBlank()) {
            String m = merchant.trim();
            if (isDescricaoGenerica(nomeBase) || nomeBase.equalsIgnoreCase("Transação Open Finance")) {
                nomeBase = m;
                if (category != null && !category.isBlank() && !category.equalsIgnoreCase(m)) {
                    nomeBase = nomeBase + " · " + category;
                } else if (!isDescricaoGenerica(firstNonBlank(descRaw, desc))) {
                    String d = firstNonBlank(nonGenericOrNull(descRaw), nonGenericOrNull(desc));
                    if (d != null) {
                        nomeBase = nomeBase + " · " + d;
                    }
                }
            } else if (!nomeBase.toLowerCase(Locale.ROOT).contains(m.toLowerCase(Locale.ROOT))) {
                nomeBase = m + " · " + nomeBase;
            }
        }

        if (operationType != null
                && !operationType.isBlank()
                && !nomeBase.toLowerCase(Locale.ROOT).contains(operationType.toLowerCase(Locale.ROOT))) {
            nomeBase = nomeBase + " (" + operationType.trim() + ")";
        }

        if (isDescricaoGenerica(nomeBase)) {
            nomeBase = firstNonBlank(
                    category,
                    operationType != null ? "Lançamento (" + operationType.trim() + ")" : null,
                    "Transação Open Finance");
        }

        nomeBase = nomeBase.trim();
        if (nomeBase.length() > 500) {
            nomeBase = nomeBase.substring(0, 500);
        }
        return nomeBase;
    }

    private static String montarObservacaoPluggy(String itemId, String accountLabel, String pluggyTransactionId, JsonNode tx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Open Finance (finzzia · Pluggy) · item ").append(itemId);
        if (accountLabel != null && !accountLabel.isBlank()) {
            sb.append(" · conta ").append(accountLabel.trim());
        }
        sb.append(" · tx ").append(pluggyTransactionId);
        String cat = textNode(tx, "category");
        if (cat != null) {
            sb.append(" · categoria Pluggy: ").append(cat);
        }
        String st = textNode(tx, "status");
        if (st != null) {
            sb.append(" · status: ").append(st);
        }
        String out = sb.toString();
        return out.length() > 4000 ? out.substring(0, 4000) : out;
    }

    private static String textNode(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode n = parent.get(field);
        String s = n.isValueNode() ? n.asText() : null;
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String nonGenericOrNull(String s) {
        if (s == null || isDescricaoGenerica(s)) {
            return null;
        }
        return s.trim();
    }

    /**
     * Descrições muito curtas/genéricas vindas de bancos (ex.: PGTO, TED) — enriquecer com merchant/categoria.
     */
    private static boolean isDescricaoGenerica(String texto) {
        if (texto == null) {
            return true;
        }
        String t = texto.trim();
        if (t.isEmpty()) {
            return true;
        }
        if (t.length() > 56) {
            return false;
        }
        String u = t.toUpperCase(Locale.ROOT)
                .replace('Á', 'A')
                .replace('Â', 'A')
                .replace('Ã', 'A')
                .replace('É', 'E')
                .replace('Ê', 'E')
                .replace('Í', 'I')
                .replace('Ó', 'O')
                .replace('Ô', 'O')
                .replace('Ú', 'U')
                .replace('Ç', 'C');
        if (u.matches("^(PGTO|PAG\\.?|PAGAMENTO|PIX|TED|DOC|T\\.?ED|DOC/TED|TRANSF\\.?|TR\\.?|TRANSFERENCIA|RECEBIMENTO|RECEB\\.?|SAQUE|DEPOSITO|DEP\\.|CRED|DEB|COMPRA|VENDA)$")) {
            return true;
        }
        return u.matches("^\\*+$") || u.matches("^[-–—\\s]+$");
    }

    private static BigDecimal pickSignedAmount(JsonNode tx) {
        if (tx.hasNonNull("amountInAccountCurrency")) {
            return tx.get("amountInAccountCurrency").decimalValue();
        }
        if (tx.hasNonNull("amount")) {
            return tx.get("amount").decimalValue();
        }
        return null;
    }

    private static LocalDate parseTxDate(JsonNode tx) {
        if (!tx.hasNonNull("date")) {
            return null;
        }
        String raw = tx.get("date").asText();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toLocalDate();
        } catch (DateTimeParseException e1) {
            try {
                return LocalDate.parse(raw.length() >= 10 ? raw.substring(0, 10) : raw);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static String accountLabel(JsonNode acc) {
        String name = acc.hasNonNull("name") ? acc.get("name").asText() : null;
        String marketing = acc.hasNonNull("marketingName") ? acc.get("marketingName").asText() : null;
        String number = acc.hasNonNull("number") ? acc.get("number").asText() : null;
        return firstNonBlank(name, marketing, number, "Conta");
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
