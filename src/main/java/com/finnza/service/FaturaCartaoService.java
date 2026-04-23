package com.finnza.service;

import com.finnza.domain.entity.CartaoCreditoEmpresa;
import com.finnza.domain.entity.MovimentacaoFinanceira;
import com.finnza.repository.CartaoCreditoEmpresaRepository;
import com.finnza.repository.MovimentacaoFinanceiraRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class FaturaCartaoService {

    private static final DateTimeFormatter DATE_BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private final MovimentacaoFinanceiraRepository movimentacaoRepo;
    private final CartaoCreditoEmpresaRepository cartaoRepo;

    public FaturaCartaoService(
            MovimentacaoFinanceiraRepository movimentacaoRepo,
            CartaoCreditoEmpresaRepository cartaoRepo) {
        this.movimentacaoRepo = movimentacaoRepo;
        this.cartaoRepo = cartaoRepo;
    }

    public List<Map<String, Object>> listarCartoesResumo(Integer idEmpresa) {
        LocalDate fim = LocalDate.now();
        LocalDate inicio = fim.minusMonths(6).withDayOfMonth(1);

        List<MovimentacaoFinanceira> despesas = movimentacaoRepo
                .findAllByIdEmpresaAndDataVencimentoBetween(idEmpresa, inicio, fim)
                .stream()
                .filter(m -> Boolean.TRUE.equals(m.getDebito()))
                .collect(Collectors.toList());

        List<CartaoCreditoEmpresa> cadastrados = cartaoRepo.findByIdEmpresaAndAtivoTrueOrderByNomeAsc(idEmpresa);
        if (!cadastrados.isEmpty()) {
            return construirCartoesCadastrados(cadastrados, despesas);
        }

        Map<String, List<MovimentacaoFinanceira>> porCartao = despesas.stream()
                .collect(Collectors.groupingBy(this::nomeCartaoResolvido, LinkedHashMap::new, Collectors.toList()));

        List<YearMonth> meses = construirJanelaMeses(7);
        List<Map<String, Object>> cartoes = new ArrayList<>();
        long seq = 1;

        for (Map.Entry<String, List<MovimentacaoFinanceira>> entry : porCartao.entrySet()) {
            List<MovimentacaoFinanceira> items = entry.getValue();
            Map<YearMonth, BigDecimal> totalMes = somarPorMes(items);
            BigDecimal pico = totalMes.values().stream().reduce(BigDecimal.ZERO, BigDecimal::max);
            BigDecimal limiteEstimado = pico.compareTo(BigDecimal.ZERO) > 0
                    ? pico.multiply(new BigDecimal("1.2")).setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            YearMonth atual = YearMonth.now();
            BigDecimal gastoAtual = totalMes.getOrDefault(atual, BigDecimal.ZERO);
            BigDecimal disponivel = limiteEstimado.subtract(gastoAtual).setScale(2, RoundingMode.HALF_UP);

            Map<String, Object> c = new HashMap<>();
            c.put("id", seq++);
            c.put("nome", entry.getKey());
            c.put("empresa", resolverEmpresa(items));
            c.put("limite", limiteEstimado);
            c.put("disponivel", disponivel);
            c.put("pontos", construirPontos(meses, totalMes));
            cartoes.add(c);
        }

        cartoes.sort(Comparator.comparing(c -> String.valueOf(c.get("nome"))));
        return cartoes;
    }

    public List<Map<String, Object>> listarCartoesCadastrados(Integer idEmpresa) {
        return cartaoRepo.findByIdEmpresaAndAtivoTrueOrderByNomeAsc(idEmpresa).stream()
                .map(c -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", c.getId());
                    map.put("nome", c.getNome());
                    map.put("bandeira", c.getBandeira());
                    map.put("finalCartao", c.getFinalCartao());
                    map.put("limite", c.getLimite());
                    map.put("diaFechamento", c.getDiaFechamento());
                    map.put("diaVencimento", c.getDiaVencimento());
                    map.put("contaReferencia", c.getContaReferencia());
                    map.put("ativo", c.getAtivo());
                    return map;
                })
                .collect(Collectors.toList());
    }

    public Map<String, Object> criarCartao(Integer idEmpresa, Map<String, Object> payload) {
        CartaoCreditoEmpresa c = CartaoCreditoEmpresa.builder()
                .idEmpresa(idEmpresa)
                .nome(sanitize(payload.get("nome")))
                .bandeira(sanitize(payload.get("bandeira")))
                .finalCartao(sanitize(payload.get("finalCartao")))
                .limite(parseBigDecimal(String.valueOf(payload.getOrDefault("limite", "0"))))
                .diaFechamento(parseInt(payload.get("diaFechamento")))
                .diaVencimento(parseInt(payload.get("diaVencimento")))
                .contaReferencia(sanitize(payload.get("contaReferencia")))
                .ativo(true)
                .build();
        validarCartao(c);
        CartaoCreditoEmpresa saved = cartaoRepo.save(c);
        return Map.of("id", saved.getId());
    }

    public void atualizarCartao(Integer idEmpresa, Long id, Map<String, Object> payload) {
        CartaoCreditoEmpresa c = cartaoRepo.findByIdAndIdEmpresa(id, idEmpresa)
                .orElseThrow(() -> new IllegalArgumentException("Cartão não encontrado"));
        c.setNome(sanitize(payload.get("nome")));
        c.setBandeira(sanitize(payload.get("bandeira")));
        c.setFinalCartao(sanitize(payload.get("finalCartao")));
        c.setLimite(parseBigDecimal(String.valueOf(payload.getOrDefault("limite", "0"))));
        c.setDiaFechamento(parseInt(payload.get("diaFechamento")));
        c.setDiaVencimento(parseInt(payload.get("diaVencimento")));
        c.setContaReferencia(sanitize(payload.get("contaReferencia")));
        validarCartao(c);
        cartaoRepo.save(c);
    }

    public void removerCartao(Integer idEmpresa, Long id) {
        CartaoCreditoEmpresa c = cartaoRepo.findByIdAndIdEmpresa(id, idEmpresa)
                .orElseThrow(() -> new IllegalArgumentException("Cartão não encontrado"));
        c.setAtivo(false);
        cartaoRepo.save(c);
    }

    public Map<String, Object> importarCsv(String csvContent) {
        List<Map<String, Object>> lancamentos = parseCsv(csvContent);
        return Map.of(
                "mensagem", lancamentos.size() + " lancamento(s) reconhecido(s) e categorizado(s).",
                "lancamentos", lancamentos
        );
    }

    public Map<String, Object> gerarContasPagar(String nomeCartao, List<Map<String, Object>> lancamentos) {
        Map<String, BigDecimal> totalPorCompetencia = new LinkedHashMap<>();

        for (Map<String, Object> lanc : lancamentos) {
            String tipo = String.valueOf(lanc.getOrDefault("tipo", "debito"));
            if (!"debito".equalsIgnoreCase(tipo)) {
                continue;
            }

            String data = String.valueOf(lanc.getOrDefault("data", ""));
            String competencia = obterCompetencia(data);
            BigDecimal valor = parseBigDecimal(String.valueOf(lanc.getOrDefault("valor", "0")));
            totalPorCompetencia.merge(competencia, valor.abs(), BigDecimal::add);
        }

        List<Map<String, Object>> contas = new ArrayList<>();
        long seq = 1L;
        List<Map.Entry<String, BigDecimal>> ordenado = totalPorCompetencia.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .toList();

        for (Map.Entry<String, BigDecimal> entry : ordenado) {
            String competencia = entry.getKey();
            BigDecimal total = entry.getValue().setScale(2, RoundingMode.HALF_UP);
            String vencimento = calcularVencimento(competencia);

            Map<String, Object> conta = new HashMap<>();
            conta.put("id", seq++);
            conta.put("competencia", competencia);
            conta.put("vencimento", vencimento);
            conta.put("descricao", "Fatura cartao - " + (nomeCartao == null ? "Cartao" : nomeCartao) + " (" + competencia + ")");
            conta.put("valor", total);
            conta.put("status", "prototipo");
            contas.add(conta);
        }

        return Map.of(
                "mensagem", contas.size() + " conta(s) a pagar gerada(s) em modo prototipo.",
                "contasPagar", contas
        );
    }

    private String nomeCartaoResolvido(MovimentacaoFinanceira mov) {
        String conta = mov.getNomeContaFinanceira();
        if (conta != null && !conta.isBlank()) return conta.trim();
        return "Cartao sem identificacao";
    }

    private List<Map<String, Object>> construirCartoesCadastrados(
            List<CartaoCreditoEmpresa> cadastrados,
            List<MovimentacaoFinanceira> despesas
    ) {
        List<YearMonth> meses = construirJanelaMeses(7);
        List<Map<String, Object>> cartoes = new ArrayList<>();

        for (CartaoCreditoEmpresa cadastro : cadastrados) {
            String nome = cadastro.getNome() != null ? cadastro.getNome().trim() : "";
            List<MovimentacaoFinanceira> items = despesas.stream()
                    .filter(m -> combinaCartao(cadastro, m))
                    .collect(Collectors.toList());
            Map<YearMonth, BigDecimal> totalMes = somarPorMes(items);
            BigDecimal limite = cadastro.getLimite() != null
                    ? cadastro.getLimite().setScale(2, RoundingMode.HALF_UP)
                    : totalMes.values().stream().reduce(BigDecimal.ZERO, BigDecimal::max)
                        .multiply(new BigDecimal("1.2")).setScale(2, RoundingMode.HALF_UP);
            YearMonth atual = YearMonth.now();
            BigDecimal gastoAtual = totalMes.getOrDefault(atual, BigDecimal.ZERO);
            BigDecimal disponivel = limite.subtract(gastoAtual).setScale(2, RoundingMode.HALF_UP);

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", cadastro.getId().intValue());
            c.put("nome", nome);
            c.put("empresa", "-");
            c.put("limite", limite);
            c.put("disponivel", disponivel);
            c.put("contaBancariaNome", cadastro.getContaReferencia());
            c.put("pontos", construirPontos(meses, totalMes));
            cartoes.add(c);
        }
        return cartoes;
    }

    private boolean combinaCartao(CartaoCreditoEmpresa cadastro, MovimentacaoFinanceira mov) {
        String nomeConta = mov.getNomeContaFinanceira() != null ? mov.getNomeContaFinanceira().toLowerCase(Locale.ROOT) : "";
        String contaRef = cadastro.getContaReferencia() != null ? cadastro.getContaReferencia().toLowerCase(Locale.ROOT) : "";
        String nome = cadastro.getNome() != null ? cadastro.getNome().toLowerCase(Locale.ROOT) : "";
        String finalCartao = cadastro.getFinalCartao() != null ? cadastro.getFinalCartao().trim() : "";

        boolean matchConta = !contaRef.isBlank() && nomeConta.contains(contaRef);
        boolean matchNome = !nome.isBlank() && nomeConta.contains(nome);
        boolean matchFinal = !finalCartao.isBlank() && nomeConta.contains(finalCartao);
        return matchConta || matchNome || matchFinal;
    }

    private String resolverEmpresa(List<MovimentacaoFinanceira> items) {
        return items.stream()
                .map(MovimentacaoFinanceira::getNomeEmpresa)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse("-");
    }

    private Map<YearMonth, BigDecimal> somarPorMes(List<MovimentacaoFinanceira> items) {
        Map<YearMonth, BigDecimal> porMes = new LinkedHashMap<>();
        for (MovimentacaoFinanceira m : items) {
            LocalDate data = m.getDataCompetencia() != null ? m.getDataCompetencia() : m.getDataVencimento();
            if (data == null) continue;
            YearMonth ym = YearMonth.from(data);
            BigDecimal valor = m.getValor() != null ? m.getValor().abs() : BigDecimal.ZERO;
            porMes.merge(ym, valor, BigDecimal::add);
        }
        return porMes;
    }

    private List<YearMonth> construirJanelaMeses(int quantidade) {
        List<YearMonth> meses = new ArrayList<>();
        YearMonth atual = YearMonth.now();
        for (int i = quantidade - 1; i >= 0; i--) {
            meses.add(atual.minusMonths(i));
        }
        return meses;
    }

    private List<Map<String, Object>> construirPontos(List<YearMonth> meses, Map<YearMonth, BigDecimal> totais) {
        List<Map<String, Object>> pontos = new ArrayList<>();
        YearMonth atual = YearMonth.now();
        for (YearMonth ym : meses) {
            Map<String, Object> p = new HashMap<>();
            p.put("mes", String.format("%02d/%d", ym.getMonthValue(), ym.getYear()));
            p.put("valor", totais.getOrDefault(ym, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
            p.put("status", ym.isBefore(atual) ? "paga" : "futura");
            pontos.add(p);
        }
        return pontos;
    }

    private List<Map<String, Object>> parseCsv(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        String[] linhas = content.split("\\r?\\n");
        if (linhas.length <= 1) {
            return List.of();
        }

        String delimitador = linhas[0].contains(";") ? ";" : ",";
        Pattern split = Pattern.compile(Pattern.quote(delimitador));
        List<Map<String, Object>> lancamentos = new ArrayList<>();

        for (int i = 1; i < linhas.length; i++) {
            String linha = linhas[i].trim();
            if (linha.isBlank()) continue;

            String[] cols = split.split(linha, -1);
            String data = normalizar(cols, 0, "");
            String descricao = normalizar(cols, 1, "Lancamento sem descricao");
            BigDecimal valorOriginal = parseBigDecimal(normalizar(cols, 2, "0"));
            String tipo = valorOriginal.signum() < 0 ? "credito" : "debito";

            CategoriaSugestao sugestao = categorizar(descricao);

            Map<String, Object> item = new HashMap<>();
            item.put("id", i);
            item.put("data", data);
            item.put("descricao", descricao);
            item.put("valor", valorOriginal.abs().setScale(2, RoundingMode.HALF_UP));
            item.put("tipo", tipo);
            item.put("categoria", sugestao.nome());
            item.put("confianca", sugestao.confianca());
            lancamentos.add(item);
        }

        return lancamentos;
    }

    private String normalizar(String[] cols, int idx, String fallback) {
        if (idx >= cols.length) return fallback;
        String raw = cols[idx] == null ? "" : cols[idx].trim();
        String semAspas = raw.replaceAll("^\"|\"$", "");
        return semAspas.isBlank() ? fallback : semAspas;
    }

    private BigDecimal parseBigDecimal(String valor) {
        try {
            String limpo = valor
                    .replace(".", "")
                    .replace(",", ".")
                    .replaceAll("[^\\d.-]", "");
            if (limpo.isBlank() || "-".equals(limpo)) return BigDecimal.ZERO;
            return new BigDecimal(limpo);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private Integer parseInt(Object value) {
        if (value == null) return null;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String sanitize(Object value) {
        if (value == null) return null;
        String out = String.valueOf(value).trim();
        return out.isBlank() ? null : out;
    }

    private void validarCartao(CartaoCreditoEmpresa c) {
        if (c.getNome() == null || c.getNome().isBlank()) {
            throw new IllegalArgumentException("Nome do cartão é obrigatório");
        }
        if (c.getDiaFechamento() != null && (c.getDiaFechamento() < 1 || c.getDiaFechamento() > 31)) {
            throw new IllegalArgumentException("Dia de fechamento deve estar entre 1 e 31");
        }
        if (c.getDiaVencimento() != null && (c.getDiaVencimento() < 1 || c.getDiaVencimento() > 31)) {
            throw new IllegalArgumentException("Dia de vencimento deve estar entre 1 e 31");
        }
        if (c.getFinalCartao() != null && !c.getFinalCartao().matches("\\d{4}")) {
            throw new IllegalArgumentException("Final do cartão deve conter 4 dígitos");
        }
    }

    private CategoriaSugestao categorizar(String descricao) {
        String d = descricao.toLowerCase(Locale.ROOT);
        if (d.matches(".*(ifood|restaurante|pizza|lanchonete|uber eats).*")) return new CategoriaSugestao("Alimentacao", "alta");
        if (d.matches(".*(posto|shell|ipiranga|combustivel).*")) return new CategoriaSugestao("Combustivel", "alta");
        if (d.matches(".*(google|meta|facebook|ads|trafego).*")) return new CategoriaSugestao("Marketing", "media");
        if (d.matches(".*(farmacia|drogaria|hospital|clinica).*")) return new CategoriaSugestao("Saude", "media");
        if (d.matches(".*(amazon|mercado livre|shop|loja).*")) return new CategoriaSugestao("Compras", "media");
        return new CategoriaSugestao("Outras despesas", "baixa");
    }

    private String obterCompetencia(String data) {
        try {
            if (data.contains("/")) {
                LocalDate d = LocalDate.parse(data, DATE_BR);
                return String.format("%02d/%d", d.getMonthValue(), d.getYear());
            }
            if (data.contains("-")) {
                LocalDate d = LocalDate.parse(data);
                return String.format("%02d/%d", d.getMonthValue(), d.getYear());
            }
        } catch (Exception ignored) {
        }
        return "N/A";
    }

    private String calcularVencimento(String competencia) {
        try {
            String[] parts = competencia.split("/");
            if (parts.length == 2) {
                return String.format("10/%s/%s", parts[0], parts[1]);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private record CategoriaSugestao(String nome, String confianca) {}
}

