package com.finnza.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Importação em lote de lançamentos (contas a receber / pagar) via planilha CSV.
 */
@Service
@RequiredArgsConstructor
public class MovimentacaoLancamentoImportService {

    public static final int MAX_LINHAS_IMPORTACAO = 2000;

    private static final DateTimeFormatter DATE_BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_BR_DASH = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private final ErpFinanceiroService erpFinanceiroService;
    private final MovimentacaoHistoricoService movimentacaoHistoricoService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> previewCsv(String csvContent, String tipo) {
        boolean debito = isDebito(tipo);
        List<LinhaParseada> linhas = parseCsv(csvContent, debito);
        return montarRespostaPreview(linhas, debito);
    }

    @Transactional
    public Map<String, Object> importarLinhas(
            Integer idEmpresa,
            String tipo,
            List<Map<String, Object>> linhasRequest,
            String categoriaPadrao,
            String contaPadrao,
            String formaPagamentoPadrao,
            String nomeArquivo
    ) {
        if (idEmpresa == null || idEmpresa <= 0) {
            throw new IllegalArgumentException("Empresa inválida para importação.");
        }
        boolean debito = isDebito(tipo);
        if (linhasRequest == null || linhasRequest.isEmpty()) {
            throw new IllegalArgumentException("Nenhuma linha informada para importação.");
        }
        if (linhasRequest.size() > MAX_LINHAS_IMPORTACAO) {
            throw new IllegalArgumentException("Máximo de " + MAX_LINHAS_IMPORTACAO + " linhas por importação.");
        }

        String catPadrao = blankToNull(categoriaPadrao);
        String contaPad = blankToNull(contaPadrao);
        String formaPad = blankToNull(formaPagamentoPadrao);
        String metaBase = buildMetadataImport(debito, nomeArquivo);

        int importados = 0;
        int ignorados = 0;
        List<Map<String, Object>> erros = new ArrayList<>();

        for (Map<String, Object> raw : linhasRequest) {
            int numeroLinha = parseInt(raw.get("numeroLinha"), 0);
            if (Boolean.FALSE.equals(raw.get("valido"))) {
                ignorados++;
                continue;
            }
            try {
                String parceiro = stringVal(raw.get("parceiro"));
                LocalDate vencimento = parseDataIso(stringVal(raw.get("dataVencimento")));
                LocalDate quitacao = parseDataIso(stringVal(raw.get("dataQuitacao")));
                String descricao = stringVal(raw.get("descricao"));
                String categoria = firstNonBlank(stringVal(raw.get("categoria")), catPadrao);
                BigDecimal valor = parseValorDecimal(stringVal(raw.get("valor")));
                String conta = firstNonBlank(stringVal(raw.get("conta")), contaPad);
                String forma = firstNonBlank(stringVal(raw.get("formaPagamento")), formaPad);

                if (vencimento == null) {
                    throw new IllegalArgumentException("Data de vencimento inválida.");
                }
                if (descricao == null || descricao.isBlank()) {
                    throw new IllegalArgumentException("Descrição obrigatória.");
                }
                if (categoria == null || categoria.isBlank()) {
                    throw new IllegalArgumentException("Categoria obrigatória (informe na planilha ou categoria padrão).");
                }
                if (valor == null || valor.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Valor deve ser maior que zero.");
                }

                Map<String, Object> criada = erpFinanceiroService.criarMovimentacaoManual(
                        idEmpresa,
                        debito,
                        vencimento,
                        vencimento,
                        quitacao,
                        valor,
                        descricao.trim(),
                        null,
                        categoria.trim(),
                        conta,
                        parceiro,
                        forma,
                        null,
                        null,
                        null,
                        null,
                        metaBase
                );

                String idOrigem = String.valueOf(criada.getOrDefault("IdMovimentacaoFinanceiraParcela", ""));
                movimentacaoHistoricoService.registrarCriacao(
                        idEmpresa,
                        idOrigem,
                        debito,
                        vencimento,
                        vencimento,
                        quitacao,
                        valor,
                        descricao.trim(),
                        null,
                        categoria.trim(),
                        conta,
                        parceiro
                );
                importados++;
            } catch (Exception e) {
                ignorados++;
                erros.add(Map.of(
                        "numeroLinha", numeroLinha,
                        "mensagem", e.getMessage() != null ? e.getMessage() : "Erro ao importar linha"
                ));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("erro", false);
        out.put("mensagem", importados + " lançamento(s) importado(s) com sucesso.");
        out.put("importados", importados);
        out.put("ignorados", ignorados);
        out.put("erros", erros);
        return out;
    }

    private Map<String, Object> montarRespostaPreview(List<LinhaParseada> linhas, boolean debito) {
        List<Map<String, Object>> itens = new ArrayList<>();
        int validas = 0;
        int invalidas = 0;

        for (LinhaParseada lp : linhas) {
            List<String> erros = new ArrayList<>(lp.erros);
            String categoria = lp.categoria;
            List<String> avisos = new ArrayList<>();
            if (categoria == null || categoria.isBlank()) {
                avisos.add("Categoria não informada — será usada a categoria padrão na confirmação.");
            }
            boolean valido = erros.isEmpty()
                    && lp.vencimento != null
                    && lp.descricao != null && !lp.descricao.isBlank()
                    && lp.valor != null && lp.valor.compareTo(BigDecimal.ZERO) > 0;

            if (valido) {
                validas++;
            } else {
                invalidas++;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("numeroLinha", lp.numeroLinha);
            item.put("valido", valido);
            item.put("erros", erros);
            item.put("avisos", avisos);
            item.put("parceiro", lp.parceiro);
            item.put("dataVencimento", lp.vencimento != null ? lp.vencimento.toString() : null);
            item.put("dataQuitacao", lp.quitacao != null ? lp.quitacao.toString() : null);
            item.put("descricao", lp.descricao);
            item.put("categoria", categoria);
            item.put("valor", lp.valor);
            item.put("conta", lp.conta);
            item.put("formaPagamento", lp.formaPagamento);
            item.put("status", lp.statusLabel);
            itens.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("erro", false);
        out.put("tipo", debito ? "despesa" : "receita");
        out.put("totalLinhas", linhas.size());
        out.put("linhasValidas", validas);
        out.put("linhasInvalidas", invalidas);
        out.put("linhas", itens);
        out.put("colunasEsperadas", colunasEsperadas(debito));
        return out;
    }

    private List<String> colunasEsperadas(boolean debito) {
        if (debito) {
            return List.of(
                    "Fornecedor",
                    "Vencimento",
                    "Pagamento",
                    "Descricao",
                    "Categoria",
                    "Valor",
                    "Status",
                    "Conta",
                    "Forma pagamento"
            );
        }
        return List.of(
                "Cliente / origem",
                "Vencimento",
                "Recebimento",
                "Descricao",
                "Categoria",
                "Valor",
                "Status",
                "Conta",
                "Forma pagamento"
        );
    }

    private List<LinhaParseada> parseCsv(String csvContent, boolean debito) {
        if (csvContent == null || csvContent.isBlank()) {
            return List.of();
        }
        String normalized = csvContent.replace("\uFEFF", "").trim();
        String[] linhasBrutas = normalized.split("\\r?\\n");
        if (linhasBrutas.length <= 1) {
            return List.of();
        }

        String delimitador = linhasBrutas[0].contains(";") ? ";" : ",";
        String[] headerCols = splitLinha(linhasBrutas[0], delimitador);
        Map<String, Integer> headerIndex = mapearHeaders(headerCols, debito);

        List<LinhaParseada> out = new ArrayList<>();
        for (int i = 1; i < linhasBrutas.length; i++) {
            String linha = linhasBrutas[i].trim();
            if (linha.isBlank()) {
                continue;
            }
            if (out.size() >= MAX_LINHAS_IMPORTACAO) {
                break;
            }
            String[] cols = splitLinha(linha, delimitador);
            out.add(parseLinhaDados(i + 1, cols, headerIndex, debito));
        }
        return out;
    }

    private LinhaParseada parseLinhaDados(int numeroLinha, String[] cols, Map<String, Integer> headerIndex, boolean debito) {
        LinhaParseada lp = new LinhaParseada();
        lp.numeroLinha = numeroLinha;
        List<String> erros = new ArrayList<>();

        lp.parceiro = col(cols, headerIndex.get("parceiro"));
        String vencStr = col(cols, headerIndex.get("vencimento"));
        String quitStr = col(cols, headerIndex.get("quitacao"));
        lp.descricao = col(cols, headerIndex.get("descricao"));
        lp.categoria = col(cols, headerIndex.get("categoria"));
        String valorStr = col(cols, headerIndex.get("valor"));
        lp.conta = col(cols, headerIndex.get("conta"));
        lp.formaPagamento = col(cols, headerIndex.get("forma"));
        String statusStr = col(cols, headerIndex.get("status"));

        lp.vencimento = parseDataFlex(vencStr);
        if (lp.vencimento == null && vencStr != null && !vencStr.isBlank()) {
            erros.add("Vencimento inválido: " + vencStr);
        } else if (lp.vencimento == null) {
            erros.add("Vencimento obrigatório.");
        }

        lp.quitacao = parseDataFlex(quitStr);
        if (quitStr != null && !quitStr.isBlank() && !"-".equals(quitStr.trim()) && lp.quitacao == null) {
            erros.add("Data de " + (debito ? "pagamento" : "recebimento") + " inválida: " + quitStr);
        }

        if (lp.descricao == null || lp.descricao.isBlank()) {
            erros.add("Descrição obrigatória.");
        }

        lp.valor = parseValor(valorStr);
        if (lp.valor == null || lp.valor.compareTo(BigDecimal.ZERO) <= 0) {
            erros.add("Valor inválido ou zerado.");
        } else {
            lp.valor = lp.valor.setScale(2, RoundingMode.HALF_UP);
        }

        lp.statusLabel = resolverStatus(statusStr, lp.quitacao, debito);
        if (lp.quitacao == null && isStatusLiquidado(statusStr, debito)) {
            lp.quitacao = lp.vencimento;
        }

        lp.erros = erros;
        return lp;
    }

    private Map<String, Integer> mapearHeaders(String[] headerCols, boolean debito) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headerCols.length; i++) {
            String norm = normalizarHeader(headerCols[i]);
            if (norm.isEmpty()) {
                continue;
            }
            if (matchesAny(norm, "cliente", "cliente origem", "fornecedor", "parceiro", "nome cliente", "nome fornecedor")) {
                map.putIfAbsent("parceiro", i);
            } else if (matchesAny(norm, "vencimento", "data vencimento", "data", "dt vencimento")) {
                map.putIfAbsent("vencimento", i);
            } else if (matchesAny(norm, "recebimento", "pagamento", "data recebimento", "data pagamento", "data quitacao", "quitacao")) {
                map.putIfAbsent("quitacao", i);
            } else if (matchesAny(norm, "descricao", "descricao lancamento", "nome", "historico", "titulo", "lancamento")) {
                map.putIfAbsent("descricao", i);
            } else if (matchesAny(norm, "categoria", "nome categoria", "categoria financeira")) {
                map.putIfAbsent("categoria", i);
            } else if (matchesAny(norm, "valor", "valor total", "valor r$", "vlr")) {
                map.putIfAbsent("valor", i);
            } else if (matchesAny(norm, "status", "situacao", "situacao pagamento")) {
                map.putIfAbsent("status", i);
            } else if (matchesAny(norm, "conta", "conta financeira", "nome conta", "banco")) {
                map.putIfAbsent("conta", i);
            } else if (matchesAny(norm, "forma pagamento", "forma de pagamento", "meio pagamento")) {
                map.putIfAbsent("forma", i);
            }
        }

        // Layout posicional (Sipag / extratos simples): data;descricao;valor;cliente
        if (!map.containsKey("vencimento") && headerCols.length >= 3) {
            map.put("vencimento", 0);
            map.put("descricao", 1);
            map.put("valor", 2);
            if (headerCols.length >= 4) {
                map.put("parceiro", 3);
            }
        }

        if (!map.containsKey("descricao") && debito && headerCols.length >= 2) {
            map.putIfAbsent("descricao", 1);
        }

        return map;
    }

    private static String[] splitLinha(String linha, String delimitador) {
        if (";".equals(delimitador)) {
            return linha.split(";", -1);
        }
        return linha.split(",", -1);
    }

    private static String col(String[] cols, Integer idx) {
        if (idx == null || idx < 0 || idx >= cols.length) {
            return null;
        }
        String raw = cols[idx] == null ? "" : cols[idx].trim();
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
            raw = raw.substring(1, raw.length() - 1).replace("\"\"", "\"");
        }
        return raw.isBlank() ? null : raw;
    }

    private static String normalizarHeader(String h) {
        if (h == null) {
            return "";
        }
        String s = Normalizer.normalize(h.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return s.replaceAll("\\s+", " ");
    }

    private static boolean matchesAny(String norm, String... options) {
        for (String o : options) {
            if (norm.equals(o) || norm.contains(o)) {
                return true;
            }
        }
        return false;
    }

    private static LocalDate parseDataFlex(String raw) {
        if (raw == null || raw.isBlank() || "-".equals(raw.trim())) {
            return null;
        }
        String t = raw.trim();
        try {
            if (t.contains("/")) {
                return LocalDate.parse(t, DATE_BR);
            }
            if (t.matches("\\d{2}-\\d{2}-\\d{4}")) {
                return LocalDate.parse(t, DATE_BR_DASH);
            }
            return LocalDate.parse(t);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate parseDataIso(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(iso.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static BigDecimal parseValor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String bruto = raw.trim();
            boolean negativoPorParenteses = bruto.startsWith("(") && bruto.endsWith(")");
            String limpo = bruto
                    .replace("R$", "")
                    .replace(" ", "")
                    .replace("(", "")
                    .replace(")", "")
                    .trim()
                    .replaceAll("[^\\d,.-]", "");

            int lastComma = limpo.lastIndexOf(',');
            int lastDot = limpo.lastIndexOf('.');
            if (lastComma >= 0 && lastDot >= 0) {
                // Quando existem os dois, o último é o separador decimal.
                if (lastDot > lastComma) {
                    // Ex.: 1,234.56
                    limpo = limpo.replace(",", "");
                } else {
                    // Ex.: 1.234,56
                    limpo = limpo.replace(".", "").replace(",", ".");
                }
            } else if (lastComma >= 0) {
                // Ex.: 1234,56
                limpo = limpo.replace(".", "").replace(",", ".");
            }

            if (negativoPorParenteses && !limpo.startsWith("-")) {
                limpo = "-" + limpo;
            }

            if (limpo.isBlank() || "-".equals(limpo)) {
                return null;
            }
            return new BigDecimal(limpo);
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal parseValorDecimal(String raw) {
        return parseValor(raw);
    }

    private static String resolverStatus(String statusStr, LocalDate quitacao, boolean debito) {
        if (quitacao != null) {
            return debito ? "PAGO" : "RECEBIDO";
        }
        if (statusStr == null || statusStr.isBlank()) {
            return "PENDENTE";
        }
        String u = statusStr.trim().toUpperCase(Locale.ROOT);
        if (u.contains("RECEB") || u.contains("PAGO") || u.contains("LIQUID") || u.contains("QUITAD")) {
            return debito ? "PAGO" : "RECEBIDO";
        }
        if (u.contains("ATRAS")) {
            return "ATRASADO";
        }
        return "PENDENTE";
    }

    private static boolean isStatusLiquidado(String statusStr, boolean debito) {
        if (statusStr == null || statusStr.isBlank()) {
            return false;
        }
        String u = statusStr.trim().toUpperCase(Locale.ROOT);
        return u.contains("RECEB") || u.contains("PAGO") || u.contains("LIQUID") || u.contains("QUITAD");
    }

    private String buildMetadataImport(boolean debito, String nomeArquivo) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("origemImportacao", "planilha");
            if (nomeArquivo != null && !nomeArquivo.isBlank()) {
                meta.put("arquivoImportacao", nomeArquivo.trim());
            }
            if (!debito) {
                meta.put("fluxoReceita", "venda");
            }
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return "{\"origemImportacao\":\"planilha\"}";
        }
    }

    private static boolean isDebito(String tipo) {
        return "despesa".equalsIgnoreCase(tipo != null ? tipo.trim() : "");
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static String stringVal(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return b;
    }

    private static int parseInt(Object o, int fallback) {
        if (o == null) {
            return fallback;
        }
        try {
            if (o instanceof Number n) {
                return n.intValue();
            }
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static class LinhaParseada {
        int numeroLinha;
        String parceiro;
        LocalDate vencimento;
        LocalDate quitacao;
        String descricao;
        String categoria;
        BigDecimal valor;
        String conta;
        String formaPagamento;
        String statusLabel;
        List<String> erros = List.of();
    }
}
