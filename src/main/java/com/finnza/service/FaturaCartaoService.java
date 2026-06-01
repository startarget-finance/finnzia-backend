package com.finnza.service;

import com.finnza.domain.entity.CartaoCreditoEmpresa;
import com.finnza.domain.entity.MovimentacaoFinanceira;
import com.finnza.domain.entity.RegraTextoConciliacaoExtrato;
import com.finnza.repository.CartaoCreditoEmpresaRepository;
import com.finnza.repository.MovimentacaoFinanceiraRepository;
import com.finnza.repository.RegraTextoConciliacaoExtratoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class FaturaCartaoService {

    public static final String CATEGORIA_A_CLASSIFICAR = "A classificar";
    private static final DateTimeFormatter DATE_BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private final MovimentacaoFinanceiraRepository movimentacaoRepo;
    private final CartaoCreditoEmpresaRepository cartaoRepo;
    private final RegraTextoConciliacaoExtratoRepository regraTextoRepo;

    public FaturaCartaoService(
            MovimentacaoFinanceiraRepository movimentacaoRepo,
            CartaoCreditoEmpresaRepository cartaoRepo,
            RegraTextoConciliacaoExtratoRepository regraTextoRepo) {
        this.movimentacaoRepo = movimentacaoRepo;
        this.cartaoRepo = cartaoRepo;
        this.regraTextoRepo = regraTextoRepo;
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

        // Sem cartões cadastrados em Parametrização: não inferir "cartões" a partir de NomeContaFinanceira
        // (evita misturar conta corrente com fatura de cartão).
        return List.of();
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

    /** Pré-visualização do extrato (não grava no financeiro). */
    public Map<String, Object> previewImportacao(Integer idEmpresa, String csvContent, Long cartaoId) {
        if (idEmpresa == null || idEmpresa <= 0) {
            throw new IllegalArgumentException("Empresa não identificada.");
        }
        List<Map<String, Object>> lancamentos = parseCsv(csvContent, idEmpresa, cartaoId);
        enriquecerItensPreview(idEmpresa, cartaoId, lancamentos);
        long pendentes = lancamentos.stream().filter(this::itemPrecisaRevisao).count();
        return Map.of(
                "mensagem", lancamentos.size() + " lançamento(s) reconhecido(s). Revise categorias antes de confirmar.",
                "lancamentos", lancamentos,
                "pendentesRevisao", pendentes,
                "modo", "preview"
        );
    }

    @Transactional
    public Map<String, Object> confirmarImportacao(
            Integer idEmpresa,
            Long cartaoId,
            List<Map<String, Object>> lancamentos
    ) {
        if (idEmpresa == null || idEmpresa <= 0) {
            throw new IllegalArgumentException("Empresa não identificada.");
        }
        if (cartaoId == null) {
            throw new IllegalArgumentException("Cartão é obrigatório para confirmar a importação.");
        }
        if (lancamentos == null || lancamentos.isEmpty()) {
            throw new IllegalArgumentException("Nenhum lançamento selecionado para importar.");
        }
        CartaoCreditoEmpresa cartao = cartaoRepo.findByIdAndIdEmpresaAndAtivoTrue(cartaoId, idEmpresa)
                .orElseThrow(() -> new IllegalArgumentException("Cartão não encontrado para a empresa informada."));
        String nomeCartao = cartao.getNome() != null && !cartao.getNome().isBlank() ? cartao.getNome() : "Cartão";

        persistirLancamentosImportados(idEmpresa, cartao, lancamentos);
        salvarRegrasDosItens(idEmpresa, cartaoId, lancamentos);

        for (Map<String, Object> item : lancamentos) {
            item.put("cartaoId", cartao.getId());
            item.put("cartaoNome", nomeCartao);
            item.put("contaBancariaId", cartao.getId());
            item.put("contaBancariaNome", nomeCartao);
            enriquecerFlagsClassificacao(item);
        }
        long pendentes = lancamentos.stream().filter(this::itemPrecisaRevisao).count();
        return Map.of(
                "mensagem", lancamentos.size() + " lançamento(s) importado(s) no cartão " + nomeCartao + ".",
                "lancamentos", lancamentos,
                "pendentesRevisao", pendentes,
                "modo", "confirmado"
        );
    }

    /** Mantido para compatibilidade: apenas pré-visualiza (não persiste). */
    public Map<String, Object> importarCsv(String csvContent) {
        List<Map<String, Object>> lancamentos = parseCsv(csvContent, null, null);
        for (Map<String, Object> item : lancamentos) {
            enriquecerFlagsClassificacao(item);
        }
        return Map.of(
                "mensagem", lancamentos.size() + " lançamento(s) reconhecido(s). Confirme a importação para gravar.",
                "lancamentos", lancamentos,
                "modo", "preview"
        );
    }

    /** Mantido para compatibilidade: redireciona para preview (não persiste automaticamente). */
    public Map<String, Object> importarCsv(Integer idEmpresa, String csvContent, Long cartaoId) {
        return previewImportacao(idEmpresa, csvContent, cartaoId);
    }

    public List<Map<String, Object>> listarRegrasTexto(Integer idEmpresa, Long cartaoId) {
        if (idEmpresa == null || idEmpresa <= 0) {
            return List.of();
        }
        List<RegraTextoConciliacaoExtrato> regras = cartaoId != null
                ? regraTextoRepo.findByIdEmpresaAndCartaoIdAndAtivoTrueOrderByTextoContemAsc(idEmpresa, cartaoId)
                : regraTextoRepo.findByIdEmpresaAndAtivoTrueOrderByTextoContemAsc(idEmpresa);
        List<Map<String, Object>> out = new ArrayList<>();
        for (RegraTextoConciliacaoExtrato r : regras) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", r.getId());
            map.put("textoContem", r.getTextoContem());
            map.put("categoria", r.getCategoria());
            map.put("tipoMovimento", r.getTipoMovimento());
            map.put("cartaoId", r.getCartaoId());
            out.add(map);
        }
        return out;
    }

    public Map<String, Object> criarRegraTexto(Integer idEmpresa, Map<String, Object> payload) {
        if (idEmpresa == null || idEmpresa <= 0) {
            throw new IllegalArgumentException("Empresa não identificada.");
        }
        String texto = sanitize(payload.get("textoContem"));
        String categoria = sanitize(payload.get("categoria"));
        if (texto == null || texto.length() < 3) {
            throw new IllegalArgumentException("Informe ao menos 3 caracteres do texto do extrato.");
        }
        if (categoria == null || categoria.isBlank()) {
            throw new IllegalArgumentException("Categoria é obrigatória.");
        }
        RegraTextoConciliacaoExtrato saved = regraTextoRepo.save(RegraTextoConciliacaoExtrato.builder()
                .idEmpresa(idEmpresa)
                .cartaoId(parseLong(payload.get("cartaoId")))
                .textoContem(texto)
                .categoria(categoria)
                .tipoMovimento(sanitize(payload.get("tipoMovimento")))
                .ativo(true)
                .build());
        return Map.of("id", saved.getId(), "textoContem", saved.getTextoContem(), "categoria", saved.getCategoria());
    }

    public void removerRegraTexto(Integer idEmpresa, Long idRegra) {
        RegraTextoConciliacaoExtrato regra = regraTextoRepo.findById(idRegra)
                .orElseThrow(() -> new IllegalArgumentException("Regra não encontrada."));
        if (!idEmpresa.equals(regra.getIdEmpresa())) {
            throw new IllegalArgumentException("Regra não pertence à empresa.");
        }
        regra.setAtivo(false);
        regraTextoRepo.save(regra);
    }

    private void persistirLancamentosImportados(
            Integer idEmpresa,
            CartaoCreditoEmpresa cartao,
            List<Map<String, Object>> lancamentos
    ) {
        if (lancamentos == null || lancamentos.isEmpty()) {
            return;
        }
        String nomeCartao = cartao.getNome() != null && !cartao.getNome().isBlank() ? cartao.getNome().trim() : "Cartão";
        List<MovimentacaoFinanceira> itens = new ArrayList<>();
        for (Map<String, Object> item : lancamentos) {
            String dataRaw = String.valueOf(item.getOrDefault("data", ""));
            LocalDate data = parseDataFatura(dataRaw);
            if (data == null) {
                data = LocalDate.now();
            }
            BigDecimal valor = parseBigDecimal(String.valueOf(item.getOrDefault("valor", "0")))
                    .abs()
                    .setScale(2, RoundingMode.HALF_UP);
            String tipo = String.valueOf(item.getOrDefault("tipo", "debito"));
            boolean debito = !"credito".equalsIgnoreCase(tipo);
            String categoria = normalizarCategoriaImportada(String.valueOf(item.getOrDefault("categoria", CATEGORIA_A_CLASSIFICAR)));
            String confianca = String.valueOf(item.getOrDefault("confianca", "baixa"));
            String descricao = String.valueOf(item.getOrDefault("descricao", "Lançamento cartão"));
            String statusClassificacao = statusClassificacaoDaCategoria(categoria, confianca);

            String idMovimentacao = "FAT-" + idEmpresa + "-" + UUID.randomUUID();
            item.put("idMovimentacao", idMovimentacao);
            MovimentacaoFinanceira mov = MovimentacaoFinanceira.builder()
                    .idMovimentacao(idMovimentacao)
                    .idEmpresa(idEmpresa)
                    .debito(debito)
                    .dataVencimento(data)
                    .dataCompetencia(data)
                    .valor(valor)
                    .nome(descricao)
                    .observacao("Importado via Fatura Cartão em " + LocalDateTime.now())
                    .nomeCategoriaFinanceira(categoria)
                    .idContaFinanceira(cartao.getId().intValue())
                    .nomeContaFinanceira(nomeCartao)
                    .statusPagamento("PENDENTE")
                    .sincronizadoEm(LocalDateTime.now())
                    .metadataJson(buildMetadataImportacao(cartao.getId(), confianca, statusClassificacao))
                    .build();
            itens.add(mov);
        }
        movimentacaoRepo.saveAll(itens);
    }

    private LocalDate parseDataFatura(String data) {
        try {
            if (data == null || data.isBlank()) return null;
            String t = data.trim();
            if (t.contains("/")) {
                String[] parts = t.split("/");
                if (parts.length == 2) {
                    int dia = Integer.parseInt(parts[0]);
                    int mes = Integer.parseInt(parts[1]);
                    int ano = LocalDate.now().getYear();
                    return LocalDate.of(ano, mes, dia);
                }
                return LocalDate.parse(t, DATE_BR);
            }
            return LocalDate.parse(t);
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public Map<String, Object> gerarContasPagar(
            Integer idEmpresa,
            Long cartaoId,
            String nomeCartao,
            List<Map<String, Object>> lancamentos
    ) {
        if (idEmpresa == null || idEmpresa <= 0) {
            throw new IllegalArgumentException("Empresa não identificada para gerar contas a pagar.");
        }
        if (cartaoId == null) {
            throw new IllegalArgumentException("Cartão é obrigatório para gerar contas a pagar.");
        }
        CartaoCreditoEmpresa cartao = cartaoRepo.findByIdAndIdEmpresaAndAtivoTrue(cartaoId, idEmpresa)
                .orElseThrow(() -> new IllegalArgumentException("Cartão não encontrado para a empresa informada."));
        String nomeCartaoResolvido = cartao.getNome() != null && !cartao.getNome().isBlank()
                ? cartao.getNome().trim()
                : (nomeCartao == null || nomeCartao.isBlank() ? "Cartão" : nomeCartao.trim());

        Map<String, BigDecimal> totalPorCompetencia = new LinkedHashMap<>();

        for (Map<String, Object> lanc : lancamentos) {
            String tipo = String.valueOf(lanc.getOrDefault("tipo", "debito"));
            if (!"debito".equalsIgnoreCase(tipo)) {
                continue;
            }

            String data = String.valueOf(lanc.getOrDefault("data", ""));
            String competencia = obterCompetencia(data);
            if ("N/A".equalsIgnoreCase(competencia)) {
                continue;
            }
            BigDecimal valor = parseBigDecimal(String.valueOf(lanc.getOrDefault("valor", "0")));
            totalPorCompetencia.merge(competencia, valor.abs(), BigDecimal::add);
        }

        List<Map<String, Object>> contas = new ArrayList<>();
        List<Map.Entry<String, BigDecimal>> ordenado = totalPorCompetencia.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .toList();

        for (Map.Entry<String, BigDecimal> entry : ordenado) {
            String competencia = entry.getKey();
            YearMonth ym = parseCompetencia(competencia);
            if (ym == null) {
                continue;
            }
            BigDecimal total = entry.getValue().setScale(2, RoundingMode.HALF_UP);
            LocalDate vencimentoData = calcularVencimento(ym, cartao.getDiaVencimento());
            String vencimento = vencimentoData.toString();
            String idMovimentacao = buildIdContaPagarFatura(idEmpresa, cartaoId, ym);

            MovimentacaoFinanceira mov = MovimentacaoFinanceira.builder()
                    .idMovimentacao(idMovimentacao)
                    .idEmpresa(idEmpresa)
                    .debito(true)
                    .dataVencimento(vencimentoData)
                    .dataCompetencia(ym.atDay(1))
                    .valor(total)
                    .nome("Fatura cartao - " + nomeCartaoResolvido + " (" + competencia + ")")
                    .observacao("Conta a pagar gerada automaticamente pela importação de fatura.")
                    .nomeCategoriaFinanceira("Cartão de crédito")
                    .idContaFinanceira(cartao.getId().intValue())
                    .nomeContaFinanceira(nomeCartaoResolvido)
                    .statusPagamento("PENDENTE")
                    .sincronizadoEm(LocalDateTime.now())
                    .metadataJson("{\"origem\":\"fatura_cartao\",\"cartaoId\":" + cartaoId + ",\"competencia\":\"" + competencia + "\"}")
                    .build();
            movimentacaoRepo.save(mov);

            Map<String, Object> conta = new HashMap<>();
            conta.put("id", idMovimentacao);
            conta.put("competencia", competencia);
            conta.put("vencimento", vencimento);
            conta.put("descricao", "Fatura cartao - " + nomeCartaoResolvido + " (" + competencia + ")");
            conta.put("valor", total);
            conta.put("status", "pendente");
            contas.add(conta);
        }

        return Map.of(
                "mensagem", contas.size() + " conta(s) a pagar criada(s) no financeiro.",
                "contasPagar", contas
        );
    }

    public Map<String, Object> listarPainelImportacao(Integer idEmpresa, Long cartaoId) {
        if (idEmpresa == null || idEmpresa <= 0) {
            return Map.of("lancamentos", List.of(), "contasPagar", List.of());
        }
        List<Map<String, Object>> lancamentos = new ArrayList<>();
        List<Map<String, Object>> contasPagar = new ArrayList<>();
        long seq = 1L;

        if (cartaoId != null) {
            CartaoCreditoEmpresa cartao = cartaoRepo.findByIdAndIdEmpresaAndAtivoTrue(cartaoId, idEmpresa)
                    .orElseThrow(() -> new IllegalArgumentException("Cartão não encontrado para a empresa informada."));
            PainelImportacaoCartao painel = listarPainelDoCartao(idEmpresa, cartao, seq);
            lancamentos.addAll(painel.lancamentos());
            contasPagar.addAll(painel.contasPagar());
        } else {
            List<CartaoCreditoEmpresa> cartoes = cartaoRepo.findByIdEmpresaAndAtivoTrueOrderByNomeAsc(idEmpresa);
            for (CartaoCreditoEmpresa cartao : cartoes) {
                PainelImportacaoCartao painel = listarPainelDoCartao(idEmpresa, cartao, seq);
                seq += painel.lancamentos().size();
                lancamentos.addAll(painel.lancamentos());
                contasPagar.addAll(painel.contasPagar());
            }
        }

        lancamentos.sort((a, b) -> String.valueOf(b.getOrDefault("data", ""))
                .compareTo(String.valueOf(a.getOrDefault("data", ""))));
        if (lancamentos.size() > 200) {
            lancamentos = new ArrayList<>(lancamentos.subList(0, 200));
        }

        contasPagar.sort((a, b) -> String.valueOf(b.getOrDefault("vencimento", ""))
                .compareTo(String.valueOf(a.getOrDefault("vencimento", ""))));

        return Map.of("lancamentos", lancamentos, "contasPagar", contasPagar);
    }

    private PainelImportacaoCartao listarPainelDoCartao(
            Integer idEmpresa,
            CartaoCreditoEmpresa cartao,
            long idInicial
    ) {
        List<MovimentacaoFinanceira> rows = movimentacaoRepo
                .findTop200ByIdEmpresaAndIdContaFinanceiraOrderByDataVencimentoDescIdMovimentacaoDesc(
                        idEmpresa,
                        cartao.getId().intValue()
                );
        List<Map<String, Object>> lancamentos = new ArrayList<>();
        List<Map<String, Object>> contasPagar = new ArrayList<>();
        long seq = idInicial;
        String nomeCartao = cartao.getNome() != null ? cartao.getNome() : "Cartão";

        for (MovimentacaoFinanceira m : rows) {
            if (ehContaPagarFaturaConsolidada(m)) {
                contasPagar.add(mapearContaPagarFatura(m, cartao));
            } else if (ehLancamentoExtratoImportado(m)) {
                lancamentos.add(mapearLancamentoExtrato(m, cartao, nomeCartao, seq++));
            }
        }
        return new PainelImportacaoCartao(lancamentos, contasPagar);
    }

    private boolean ehContaPagarFaturaConsolidada(MovimentacaoFinanceira m) {
        String id = m.getIdMovimentacao() != null ? m.getIdMovimentacao() : "";
        if (id.startsWith("FATCP-")) {
            return true;
        }
        String nome = m.getNome() != null ? m.getNome().trim() : "";
        return nome.toLowerCase(Locale.ROOT).startsWith("fatura cartao -");
    }

    private boolean ehLancamentoExtratoImportado(MovimentacaoFinanceira m) {
        if (ehContaPagarFaturaConsolidada(m)) {
            return false;
        }
        String id = m.getIdMovimentacao() != null ? m.getIdMovimentacao() : "";
        if (id.startsWith("FAT-")) {
            return true;
        }
        String obs = m.getObservacao() != null ? m.getObservacao().toLowerCase(Locale.ROOT) : "";
        return obs.contains("importado via fatura cart");
    }

    private Map<String, Object> mapearLancamentoExtrato(
            MovimentacaoFinanceira m,
            CartaoCreditoEmpresa cartao,
            String nomeCartao,
            long seq
    ) {
        Map<String, Object> item = new LinkedHashMap<>();
        String idMovimentacao = m.getIdMovimentacao() != null ? m.getIdMovimentacao() : "";
        String descricao = m.getNome() != null ? m.getNome() : "";
        item.put("id", seq);
        item.put("idMovimentacao", idMovimentacao);
        item.put("data", m.getDataVencimento() != null ? m.getDataVencimento().toString() : "");
        item.put("descricao", descricao);
        item.put("valor", m.getValor() != null ? m.getValor().abs().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        item.put("tipo", Boolean.TRUE.equals(m.getDebito()) ? "debito" : "credito");
        String categoria = m.getNomeCategoriaFinanceira() != null ? m.getNomeCategoriaFinanceira() : CATEGORIA_A_CLASSIFICAR;
        CategoriaSugestao sugestao = classificarDescricao(m.getIdEmpresa(), cartao.getId(), descricao, Boolean.TRUE.equals(m.getDebito()) ? "debito" : "credito");
        String confianca = inferirConfiancaPersistida(m, sugestao);
        item.put("categoria", categoria);
        item.put("confianca", confianca);
        enriquecerFlagsClassificacao(item);
        item.put("cartaoId", cartao.getId());
        item.put("cartaoNome", nomeCartao);
        item.put("contaBancariaId", cartao.getId());
        item.put("contaBancariaNome", nomeCartao);
        return item;
    }

    private Map<String, Object> mapearContaPagarFatura(MovimentacaoFinanceira m, CartaoCreditoEmpresa cartao) {
        Map<String, Object> conta = new LinkedHashMap<>();
        String nomeCartao = cartao.getNome() != null ? cartao.getNome() : "Cartão";
        conta.put("id", m.getIdMovimentacao());
        conta.put("idMovimentacao", m.getIdMovimentacao());
        conta.put("competencia", extrairCompetenciaDaFatura(m));
        conta.put("vencimento", m.getDataVencimento() != null ? m.getDataVencimento().toString() : "");
        conta.put("descricao", m.getNome() != null ? m.getNome() : "");
        conta.put("valor", m.getValor() != null ? m.getValor().abs().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        conta.put("status", m.getStatusPagamento() != null ? m.getStatusPagamento().toLowerCase(Locale.ROOT) : "pendente");
        conta.put("cartaoId", cartao.getId());
        conta.put("cartaoNome", nomeCartao);
        return conta;
    }

    private String extrairCompetenciaDaFatura(MovimentacaoFinanceira m) {
        String nome = m.getNome() != null ? m.getNome() : "";
        int abre = nome.lastIndexOf('(');
        int fecha = nome.lastIndexOf(')');
        if (abre >= 0 && fecha > abre) {
            return nome.substring(abre + 1, fecha).trim();
        }
        if (m.getDataCompetencia() != null) {
            return String.format("%02d/%d", m.getDataCompetencia().getMonthValue(), m.getDataCompetencia().getYear());
        }
        return "N/A";
    }

    private record PainelImportacaoCartao(List<Map<String, Object>> lancamentos, List<Map<String, Object>> contasPagar) {}

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

    /**
     * Associa despesa ao cartão cadastrado usando texto do lançamento (histórico/observação),
     * não {@code NomeContaFinanceira} — evita somar toda a conta corrente quando "conta referência"
     * coincide com o nome da conta bancária.
     */
    private boolean combinaCartao(CartaoCreditoEmpresa cadastro, MovimentacaoFinanceira mov) {
        // Regra principal: se já veio vinculado pelo ID da conta/cartão, considera match imediato.
        if (cadastro.getId() != null && mov.getIdContaFinanceira() != null
                && cadastro.getId().intValue() == mov.getIdContaFinanceira()) {
            return true;
        }

        String textoMov = textoMovimentoParaCorrespondencia(mov);
        String nomeCad = cadastro.getNome() != null ? cadastro.getNome().trim().toLowerCase(Locale.ROOT) : "";
        String finalCartao = cadastro.getFinalCartao() != null ? cadastro.getFinalCartao().trim() : "";
        String contaRef = cadastro.getContaReferencia() != null ? cadastro.getContaReferencia().trim().toLowerCase(Locale.ROOT) : "";

        boolean matchNome = nomeCad.length() >= 3 && !textoMov.isEmpty() && textoMov.contains(nomeCad);

        boolean matchFinal = finalCartao.length() == 4
                && finalCartao.chars().allMatch(Character::isDigit)
                && !textoMov.isEmpty()
                && textoMov.contains(finalCartao);

        boolean matchRefCurta = false;
        if (!contaRef.isEmpty() && !textoMov.isEmpty() && contaRef.matches("^\\d{4,8}$")) {
            matchRefCurta = textoMov.contains(contaRef);
        }

        return matchNome || matchFinal || matchRefCurta;
    }

    private static String textoMovimentoParaCorrespondencia(MovimentacaoFinanceira mov) {
        String n = mov.getNome() != null ? mov.getNome() : "";
        String o = mov.getObservacao() != null ? mov.getObservacao() : "";
        return (n + " " + o).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
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

    private List<Map<String, Object>> parseCsv(String content, Integer idEmpresa, Long cartaoId) {
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

            CategoriaSugestao sugestao = classificarDescricao(idEmpresa, cartaoId, descricao, tipo);

            Map<String, Object> item = new HashMap<>();
            item.put("id", i);
            item.put("data", data);
            item.put("descricao", descricao);
            item.put("valor", valorOriginal.abs().setScale(2, RoundingMode.HALF_UP));
            item.put("tipo", tipo);
            item.put("categoria", sugestao.nome());
            item.put("confianca", sugestao.confianca());
            item.put("origemSugestao", sugestao.origem());
            enriquecerFlagsClassificacao(item);
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
            if (valor == null) return BigDecimal.ZERO;
            String bruto = valor.trim();
            if (bruto.isBlank()) return BigDecimal.ZERO;

            boolean negativoPorParenteses = bruto.startsWith("(") && bruto.endsWith(")");
            String somenteNumero = bruto
                    .replaceAll("[Rr$\\s]", "")
                    .replace("(", "")
                    .replace(")", "")
                    .replaceAll("[^\\d,.-]", "");

            if (somenteNumero.isBlank() || "-".equals(somenteNumero)) return BigDecimal.ZERO;

            int lastComma = somenteNumero.lastIndexOf(',');
            int lastDot = somenteNumero.lastIndexOf('.');

            String normalizado;
            if (lastComma >= 0 && lastDot >= 0) {
                // Quando tem os dois separadores, o último é decimal.
                if (lastDot > lastComma) {
                    // Ex.: 1,234.56
                    normalizado = somenteNumero.replace(",", "");
                } else {
                    // Ex.: 1.234,56
                    normalizado = somenteNumero.replace(".", "").replace(",", ".");
                }
            } else if (lastComma >= 0) {
                // Ex.: 1234,56
                normalizado = somenteNumero.replace(".", "").replace(",", ".");
            } else {
                // Ex.: 1234.56 (ponto decimal) ou inteiro
                normalizado = somenteNumero;
            }

            if (negativoPorParenteses && !normalizado.startsWith("-")) {
                normalizado = "-" + normalizado;
            }
            if (normalizado.isBlank() || "-".equals(normalizado)) return BigDecimal.ZERO;
            return new BigDecimal(normalizado);
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

    private CategoriaSugestao classificarDescricao(Integer idEmpresa, Long cartaoId, String descricao, String tipo) {
        if (idEmpresa != null && idEmpresa > 0 && descricao != null && !descricao.isBlank()) {
            CategoriaSugestao porRegra = classificarPorRegraTexto(idEmpresa, cartaoId, descricao, tipo);
            if (porRegra != null) {
                return porRegra;
            }
        }
        return categorizarHeuristica(descricao);
    }

    private CategoriaSugestao classificarPorRegraTexto(
            Integer idEmpresa,
            Long cartaoId,
            String descricao,
            String tipo
    ) {
        String d = descricao.toLowerCase(Locale.ROOT);
        List<RegraTextoConciliacaoExtrato> regrasEmpresa = new ArrayList<>(
                regraTextoRepo.findByIdEmpresaAndAtivoTrueOrderByTextoContemAsc(idEmpresa));
        regrasEmpresa.sort((a, b) -> {
            boolean aEsp = a.getCartaoId() != null;
            boolean bEsp = b.getCartaoId() != null;
            if (aEsp != bEsp) {
                return aEsp ? -1 : 1;
            }
            return 0;
        });
        for (RegraTextoConciliacaoExtrato regra : regrasEmpresa) {
            if (!regraCombina(regra, cartaoId, d, tipo)) {
                continue;
            }
            return new CategoriaSugestao(regra.getCategoria(), "alta", "regra_texto");
        }
        return null;
    }

    private boolean regraCombina(RegraTextoConciliacaoExtrato regra, Long cartaoId, String descricaoLower, String tipo) {
        if (regra.getCartaoId() != null && cartaoId != null && !regra.getCartaoId().equals(cartaoId)) {
            return false;
        }
        if (regra.getCartaoId() != null && cartaoId == null) {
            return false;
        }
        String texto = regra.getTextoContem() != null ? regra.getTextoContem().toLowerCase(Locale.ROOT) : "";
        if (texto.isBlank() || !descricaoLower.contains(texto)) {
            return false;
        }
        String tipoRegra = regra.getTipoMovimento();
        if (tipoRegra != null && !tipoRegra.isBlank() && tipo != null && !tipoRegra.equalsIgnoreCase(tipo)) {
            return false;
        }
        return true;
    }

    private CategoriaSugestao categorizarHeuristica(String descricao) {
        String d = descricao.toLowerCase(Locale.ROOT);
        if (d.matches(".*(ifood|restaurante|pizza|lanchonete|uber eats).*")) {
            return new CategoriaSugestao("Alimentacao", "alta", "heuristica");
        }
        if (d.matches(".*(posto|shell|ipiranga|combustivel).*")) {
            return new CategoriaSugestao("Combustivel", "alta", "heuristica");
        }
        if (d.matches(".*(google|meta|facebook|ads|trafego).*")) {
            return new CategoriaSugestao("Marketing", "media", "heuristica");
        }
        if (d.matches(".*(farmacia|drogaria|hospital|clinica).*")) {
            return new CategoriaSugestao("Saude", "media", "heuristica");
        }
        if (d.matches(".*(amazon|mercado livre|shop|loja).*")) {
            return new CategoriaSugestao("Compras", "media", "heuristica");
        }
        return new CategoriaSugestao(CATEGORIA_A_CLASSIFICAR, "baixa", "pendente");
    }

    private void enriquecerItensPreview(Integer idEmpresa, Long cartaoId, List<Map<String, Object>> lancamentos) {
        if (cartaoId != null) {
            cartaoRepo.findByIdAndIdEmpresaAndAtivoTrue(cartaoId, idEmpresa).ifPresent(cartao -> {
                String nomeCartao = cartao.getNome() != null && !cartao.getNome().isBlank() ? cartao.getNome() : "Cartão";
                for (Map<String, Object> item : lancamentos) {
                    item.put("cartaoId", cartao.getId());
                    item.put("cartaoNome", nomeCartao);
                    item.put("contaBancariaId", cartao.getId());
                    item.put("contaBancariaNome", nomeCartao);
                    enriquecerFlagsClassificacao(item);
                }
            });
        } else {
            for (Map<String, Object> item : lancamentos) {
                enriquecerFlagsClassificacao(item);
            }
        }
    }

    private void enriquecerFlagsClassificacao(Map<String, Object> item) {
        String categoria = normalizarCategoriaImportada(String.valueOf(item.getOrDefault("categoria", CATEGORIA_A_CLASSIFICAR)));
        String confianca = String.valueOf(item.getOrDefault("confianca", "baixa"));
        item.put("categoria", categoria);
        item.put("statusClassificacao", statusClassificacaoDaCategoria(categoria, confianca));
        item.put("precisaRevisao", itemPrecisaRevisao(item));
    }

    private boolean itemPrecisaRevisao(Map<String, Object> item) {
        String categoria = String.valueOf(item.getOrDefault("categoria", ""));
        String confianca = String.valueOf(item.getOrDefault("confianca", "baixa"));
        return CATEGORIA_A_CLASSIFICAR.equalsIgnoreCase(categoria.trim())
                || "baixa".equalsIgnoreCase(confianca);
    }

    private String statusClassificacaoDaCategoria(String categoria, String confianca) {
        if (CATEGORIA_A_CLASSIFICAR.equalsIgnoreCase(categoria != null ? categoria.trim() : "")) {
            return "pendente";
        }
        if ("alta".equalsIgnoreCase(confianca) || "media".equalsIgnoreCase(confianca)) {
            return "sugerida";
        }
        return "classificada";
    }

    private String normalizarCategoriaImportada(String categoria) {
        if (categoria == null || categoria.isBlank() || "Outras despesas".equalsIgnoreCase(categoria.trim())) {
            return CATEGORIA_A_CLASSIFICAR;
        }
        return categoria.trim();
    }

    private String buildMetadataImportacao(Long cartaoId, String confianca, String statusClassificacao) {
        return "{\"origem\":\"fatura_cartao\",\"cartaoId\":" + cartaoId
                + ",\"confianca\":\"" + (confianca != null ? confianca : "baixa")
                + "\",\"classificacao\":\"" + (statusClassificacao != null ? statusClassificacao : "pendente") + "\"}";
    }

    private String inferirConfiancaPersistida(MovimentacaoFinanceira m, CategoriaSugestao sugestao) {
        String meta = m.getMetadataJson() != null ? m.getMetadataJson().toLowerCase(Locale.ROOT) : "";
        if (meta.contains("\"confianca\":\"alta\"")) return "alta";
        if (meta.contains("\"confianca\":\"media\"")) return "media";
        if (CATEGORIA_A_CLASSIFICAR.equalsIgnoreCase(
                m.getNomeCategoriaFinanceira() != null ? m.getNomeCategoriaFinanceira().trim() : "")) {
            return "baixa";
        }
        return sugestao.confianca();
    }

    private void salvarRegrasDosItens(Integer idEmpresa, Long cartaoId, List<Map<String, Object>> lancamentos) {
        for (Map<String, Object> item : lancamentos) {
            if (!Boolean.TRUE.equals(item.get("salvarRegra"))) {
                continue;
            }
            String textoRegra = sanitize(item.get("textoRegra"));
            if (textoRegra == null) {
                textoRegra = sanitize(item.get("descricao"));
                if (textoRegra != null && textoRegra.length() > 48) {
                    textoRegra = textoRegra.substring(0, 48).trim();
                }
            }
            final String texto = textoRegra;
            String categoria = normalizarCategoriaImportada(String.valueOf(item.getOrDefault("categoria", "")));
            if (texto == null || texto.length() < 3 || CATEGORIA_A_CLASSIFICAR.equalsIgnoreCase(categoria)) {
                continue;
            }
            String tipo = String.valueOf(item.getOrDefault("tipo", "debito"));
            boolean jaExiste = regraTextoRepo.findByIdEmpresaAndAtivoTrueOrderByTextoContemAsc(idEmpresa).stream()
                    .anyMatch(r -> texto.equalsIgnoreCase(r.getTextoContem())
                            && categoria.equalsIgnoreCase(r.getCategoria())
                            && (r.getCartaoId() == null || r.getCartaoId().equals(cartaoId)));
            if (!jaExiste) {
                regraTextoRepo.save(RegraTextoConciliacaoExtrato.builder()
                        .idEmpresa(idEmpresa)
                        .cartaoId(cartaoId)
                        .textoContem(texto.length() > 120 ? texto.substring(0, 120) : texto)
                        .categoria(categoria)
                        .tipoMovimento(tipo)
                        .ativo(true)
                        .build());
            }
        }
    }

    private Long parseLong(Object value) {
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
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

    private YearMonth parseCompetencia(String competencia) {
        try {
            String[] parts = competencia.split("/");
            if (parts.length != 2) return null;
            int mes = Integer.parseInt(parts[0]);
            int ano = Integer.parseInt(parts[1]);
            return YearMonth.of(ano, mes);
        } catch (Exception ignored) {
            return null;
        }
    }

    private LocalDate calcularVencimento(YearMonth competencia, Integer diaVencimentoCartao) {
        int dia = diaVencimentoCartao != null && diaVencimentoCartao >= 1 && diaVencimentoCartao <= 31
                ? diaVencimentoCartao
                : 10;
        return competencia.plusMonths(1).atDay(Math.min(dia, competencia.plusMonths(1).lengthOfMonth()));
    }

    private String buildIdContaPagarFatura(Integer idEmpresa, Long cartaoId, YearMonth competencia) {
        return "FATCP-" + idEmpresa + "-" + cartaoId + "-" + competencia.getYear() + String.format("%02d", competencia.getMonthValue());
    }

    private record CategoriaSugestao(String nome, String confianca, String origem) {}
}

