package com.finnza.service;

import com.finnza.domain.entity.CategoriaFinanceiraEmpresa;
import com.finnza.domain.entity.MovimentacaoFinanceira;
import com.finnza.dto.response.DfcResponseDTO;
import com.finnza.repository.CategoriaFinanceiraEmpresaRepository;
import com.finnza.dto.response.ResumoFinanceiroDTO;
import com.finnza.repository.EmpresaUsuarioRepository;
import com.finnza.repository.MovimentacaoFinanceiraRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Optional;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Camada de leitura financeira do ERP (sem dependência de integrações externas).
 *
 * Observação: atualmente usa a tabela persistida de movimentações no banco
 * (a mesma usada anteriormente como cache), mas a fonte é o seu ERP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ErpFinanceiroService {

    private static final ObjectMapper METADATA_JSON_MAPPER = new ObjectMapper();

    private final MovimentacaoFinanceiraRepository movimentacaoRepo;
    private final CategoriaFinanceiraEmpresaRepository categoriaFinanceiraRepo;
    private final EmpresaUsuarioRepository empresaUsuarioRepository;
    private final DashboardKpiService dashboardKpiService;

    private static final int RECORRENCIA_MAX_PARCELAS = 120;

    /**
     * Remove {@code conteudoBase64} de cada item em {@code anexos} para não duplicar o payload em todas as parcelas.
     */
    private static String metadataJsonSemBinariosAnexos(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = METADATA_JSON_MAPPER.readTree(metadataJson);
            if (!root.isObject()) {
                return metadataJson;
            }
            ObjectNode obj = (ObjectNode) root;
            JsonNode arr = obj.get("anexos");
            if (arr != null && arr.isArray()) {
                ArrayNode copy = METADATA_JSON_MAPPER.createArrayNode();
                for (JsonNode a : arr) {
                    if (a != null && a.isObject()) {
                        ObjectNode one = (ObjectNode) a.deepCopy();
                        one.remove("conteudoBase64");
                        copy.add(one);
                    } else {
                        copy.add(a);
                    }
                }
                obj.set("anexos", copy);
            }
            return METADATA_JSON_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return metadataJson;
        }
    }

    private static String trimMetadataJson(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        String t = metadataJson.trim();
        if (t.length() > 4_000_000) {
            throw new IllegalArgumentException("Metadados (metadataJson) excedem o tamanho máximo permitido.");
        }
        return t;
    }

    /**
     * Frequências aceitas (case-insensitive): SEMANAL, QUINZENAL, MENSAL, BIMESTRAL, TRIMESTRAL, SEMESTRAL, ANUAL.
     * NENHUMA/NONE ou null = lançamento único.
     */
    public static String normalizarFrequenciaRecorrencia(String raw) {
        if (raw == null || raw.isBlank()) {
            return "NENHUMA";
        }
        String u = raw.trim().toUpperCase().replace('-', '_');
        return switch (u) {
            case "SEMANAL", "QUINZENAL", "MENSAL", "BIMESTRAL", "TRIMESTRAL", "SEMESTRAL", "ANUAL" -> u;
            case "NONE", "NENHUMA", "" -> "NENHUMA";
            default -> throw new IllegalArgumentException("Frequência de recorrência inválida: " + raw);
        };
    }

    private static String resolverNomeTipoMovimentacao(Boolean debito, String tipoMovimentoDespesa) {
        if (!Boolean.TRUE.equals(debito)) {
            return "Receita";
        }
        if (tipoMovimentoDespesa == null || tipoMovimentoDespesa.isBlank()) {
            return "Despesa";
        }
        String u = tipoMovimentoDespesa.trim().toUpperCase()
                .replace('Á', 'A')
                .replace('É', 'E')
                .replace('Í', 'I')
                .replace('Ó', 'O')
                .replace('Ú', 'U');
        return switch (u) {
            case "FORNECEDOR" -> "Despesa com fornecedor";
            case "FUNCIONARIO" -> "Despesa com funcionário";
            case "IMPOSTOS", "IMPOSTO" -> "Despesa (impostos)";
            case "TRANSFERENCIA", "TRANSFERÊNCIA" -> "Transferência entre contas";
            default -> "Despesa";
        };
    }

    private static LocalDate avancarRecorrencia(LocalDate base, String frequenciaNormalizada, int indice) {
        if (indice <= 0) {
            return base;
        }
        return switch (frequenciaNormalizada) {
            case "SEMANAL" -> base.plusWeeks(indice);
            case "QUINZENAL" -> base.plusWeeks(2L * indice);
            case "MENSAL" -> base.plusMonths(indice);
            case "BIMESTRAL" -> base.plusMonths(2L * indice);
            case "TRIMESTRAL" -> base.plusMonths(3L * indice);
            case "SEMESTRAL" -> base.plusMonths(6L * indice);
            case "ANUAL" -> base.plusYears(indice);
            default -> base;
        };
    }

    private MovimentacaoFinanceira novaMovimentacaoManualEntity(
            Integer idEmpresa,
            Boolean debito,
            LocalDate dataVencimento,
            LocalDate dataCompetencia,
            LocalDate dataQuitacao,
            BigDecimal valor,
            String nome,
            String observacao,
            String nomeCategoriaFinanceira,
            String nomeContaFinanceira,
            String nomeClienteFornecedor,
            int numeroParcela,
            int quantidadeParcela,
            String nomeFormaPagamento,
            String tipoMovimentoDespesa,
            String departamento,
            String rateioJson,
            Long idFuncionario,
            String metadataJson
    ) {
        String contaTrim = nomeContaFinanceira == null || nomeContaFinanceira.isBlank()
                ? null
                : nomeContaFinanceira.trim();
        String formaTrim = nomeFormaPagamento == null || nomeFormaPagamento.isBlank()
                ? null
                : nomeFormaPagamento.trim();
        String deptTrim = departamento == null || departamento.isBlank() ? null : departamento.trim();
        String rateioTrim = rateioJson == null || rateioJson.isBlank() ? null : rateioJson.trim();
        String metaTrim = trimMetadataJson(metadataJson);
        return MovimentacaoFinanceira.builder()
                .idMovimentacao("manual:" + UUID.randomUUID().toString().replace("-", ""))
                .idEmpresa(idEmpresa)
                .debito(Boolean.TRUE.equals(debito))
                .dataVencimento(dataVencimento)
                .dataCompetencia(dataCompetencia != null ? dataCompetencia : dataVencimento)
                .dataQuitacao(dataQuitacao)
                .dataConciliacao(null)
                .valor(valor)
                .formaPagamento(null)
                .nomeFormaPagamento(formaTrim)
                .tipoMovimentacao(null)
                .nomeTipoMovimentacao(resolverNomeTipoMovimentacao(debito, tipoMovimentoDespesa))
                .nome(nome)
                .observacao(observacao)
                .numeroParcela(numeroParcela)
                .quantidadeParcela(quantidadeParcela)
                .idCategoriaFinanceira(null)
                .nomeCategoriaFinanceira(nomeCategoriaFinanceira)
                .idContaFinanceira(null)
                .nomeContaFinanceira(contaTrim)
                .nomeEmpresa("Empresa " + idEmpresa)
                .idCliente(null)
                .idFornecedor(null)
                .nomeClienteFornecedor(nomeClienteFornecedor)
                .departamento(deptTrim)
                .rateioJson(rateioTrim)
                .metadataJson(metaTrim)
                .idFuncionario(idFuncionario)
                .statusPagamento(dataQuitacao != null ? "pago" : "pendente")
                .dadosRaw(null)
                .sincronizadoEm(LocalDateTime.now())
                .ofxImportacaoId(null)
                .ofxAprovado(true)
                .build();
    }

    /** Compatível com chamadas antigas (sem forma/tipo de despesa nem metadados BC). */
    public Map<String, Object> criarMovimentacaoManual(
            Integer idEmpresa,
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
        return criarMovimentacaoManual(
                idEmpresa,
                debito,
                dataVencimento,
                dataCompetencia,
                dataQuitacao,
                valor,
                nome,
                observacao,
                nomeCategoriaFinanceira,
                nomeContaFinanceira,
                nomeClienteFornecedor,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /** Compatível com chamadas sem departamento / rateio / funcionário. */
    public Map<String, Object> criarMovimentacaoManual(
            Integer idEmpresa,
            Boolean debito,
            LocalDate dataVencimento,
            LocalDate dataCompetencia,
            LocalDate dataQuitacao,
            BigDecimal valor,
            String nome,
            String observacao,
            String nomeCategoriaFinanceira,
            String nomeContaFinanceira,
            String nomeClienteFornecedor,
            String nomeFormaPagamento,
            String tipoMovimentoDespesa
    ) {
        return criarMovimentacaoManual(
                idEmpresa,
                debito,
                dataVencimento,
                dataCompetencia,
                dataQuitacao,
                valor,
                nome,
                observacao,
                nomeCategoriaFinanceira,
                nomeContaFinanceira,
                nomeClienteFornecedor,
                nomeFormaPagamento,
                tipoMovimentoDespesa,
                null,
                null,
                null,
                null
        );
    }

    public Map<String, Object> criarMovimentacaoManual(
            Integer idEmpresa,
            Boolean debito,
            LocalDate dataVencimento,
            LocalDate dataCompetencia,
            LocalDate dataQuitacao,
            BigDecimal valor,
            String nome,
            String observacao,
            String nomeCategoriaFinanceira,
            String nomeContaFinanceira,
            String nomeClienteFornecedor,
            String nomeFormaPagamento,
            String tipoMovimentoDespesa,
            String departamento,
            String rateioJson,
            Long idFuncionario,
            String metadataJson
    ) {
        MovimentacaoFinanceira mov = novaMovimentacaoManualEntity(
                idEmpresa,
                debito,
                dataVencimento,
                dataCompetencia,
                dataQuitacao,
                valor,
                nome,
                observacao,
                nomeCategoriaFinanceira,
                nomeContaFinanceira,
                nomeClienteFornecedor,
                1,
                1,
                nomeFormaPagamento,
                tipoMovimentoDespesa,
                departamento,
                rateioJson,
                idFuncionario,
                metadataJson
        );
        MovimentacaoFinanceira saved = movimentacaoRepo.save(mov);
        return entityToMap(saved);
    }

    /**
     * Cria N lançamentos idênticos (valor, categoria, parceiro) com vencimento e competência avançados pela frequência.
     */
    @Transactional
    public List<Map<String, Object>> criarMovimentacoesRecorrentes(
            Integer idEmpresa,
            Boolean debito,
            LocalDate dataVencimento0,
            LocalDate dataCompetencia0,
            BigDecimal valor,
            String nome,
            String observacao,
            String nomeCategoriaFinanceira,
            String nomeContaFinanceira,
            String nomeClienteFornecedor,
            String nomeFormaPagamento,
            String tipoMovimentoDespesa,
            String departamento,
            String rateioJson,
            Long idFuncionario,
            String metadataJson,
            String frequenciaNormalizada,
            int totalParcelas
    ) {
        if (totalParcelas < 2) {
            throw new IllegalArgumentException("Recorrência exige pelo menos 2 parcelas.");
        }
        if (totalParcelas > RECORRENCIA_MAX_PARCELAS) {
            throw new IllegalArgumentException("Máximo de " + RECORRENCIA_MAX_PARCELAS + " parcelas por série.");
        }
        if ("NENHUMA".equals(frequenciaNormalizada)) {
            throw new IllegalArgumentException("Informe uma frequência de recorrência válida.");
        }
        String metaPrimeira = trimMetadataJson(metadataJson);
        String metaDemais = metadataJsonSemBinariosAnexos(metaPrimeira);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < totalParcelas; i++) {
            LocalDate v = avancarRecorrencia(dataVencimento0, frequenciaNormalizada, i);
            LocalDate c = avancarRecorrencia(
                    dataCompetencia0 != null ? dataCompetencia0 : dataVencimento0,
                    frequenciaNormalizada,
                    i
            );
            String metaParcela = (i == 0) ? metaPrimeira : metaDemais;
            MovimentacaoFinanceira mov = novaMovimentacaoManualEntity(
                    idEmpresa,
                    debito,
                    v,
                    c,
                    null,
                    valor,
                    nome,
                    observacao,
                    nomeCategoriaFinanceira,
                    nomeContaFinanceira,
                    nomeClienteFornecedor,
                    i + 1,
                    totalParcelas,
                    nomeFormaPagamento,
                    tipoMovimentoDespesa,
                    departamento,
                    rateioJson,
                    idFuncionario,
                    metaParcela
            );
            out.add(entityToMap(movimentacaoRepo.save(mov)));
        }
        return out;
    }

    /**
     * Atualiza lançamento existente da empresa. Lançamentos OFX ainda não aprovados não podem ser editados.
     */
    public Map<String, Object> atualizarMovimentacaoManual(
            Integer idEmpresa,
            String idMovimentacao,
            Boolean debito,
            LocalDate dataVencimento,
            LocalDate dataCompetencia,
            LocalDate dataQuitacao,
            BigDecimal valor,
            String nome,
            String observacao,
            String nomeCategoriaFinanceira,
            String nomeContaFinanceira,
            String nomeClienteFornecedor,
            String nomeFormaPagamento,
            String tipoMovimentoDespesa,
            String departamento,
            String rateioJson,
            Long idFuncionario,
            String metadataJson
    ) {
        MovimentacaoFinanceira mov = movimentacaoRepo
                .findByIdMovimentacaoAndIdEmpresa(idMovimentacao, idEmpresa)
                .orElseThrow(() -> new IllegalArgumentException("Movimentação não encontrada"));
        if (Boolean.FALSE.equals(mov.getOfxAprovado())) {
            throw new IllegalArgumentException("Lançamento importado ainda pendente de aprovação e não pode ser editado.");
        }
        String contaTrim = nomeContaFinanceira == null || nomeContaFinanceira.isBlank()
                ? null
                : nomeContaFinanceira.trim();
        String formaTrim = nomeFormaPagamento == null || nomeFormaPagamento.isBlank()
                ? null
                : nomeFormaPagamento.trim();
        String deptTrim = departamento == null || departamento.isBlank() ? null : departamento.trim();
        String rateioTrim = rateioJson == null || rateioJson.isBlank() ? null : rateioJson.trim();
        mov.setDebito(Boolean.TRUE.equals(debito));
        mov.setDataVencimento(dataVencimento);
        mov.setDataCompetencia(dataCompetencia != null ? dataCompetencia : dataVencimento);
        mov.setDataQuitacao(dataQuitacao);
        mov.setValor(valor);
        mov.setNome(nome);
        mov.setObservacao(observacao);
        mov.setNomeCategoriaFinanceira(nomeCategoriaFinanceira);
        mov.setNomeContaFinanceira(contaTrim);
        mov.setNomeClienteFornecedor(nomeClienteFornecedor);
        mov.setNomeFormaPagamento(formaTrim);
        mov.setNomeTipoMovimentacao(resolverNomeTipoMovimentacao(debito, tipoMovimentoDespesa));
        mov.setDepartamento(deptTrim);
        mov.setRateioJson(rateioTrim);
        if (metadataJson != null) {
            mov.setMetadataJson(trimMetadataJson(metadataJson));
        }
        mov.setIdFuncionario(idFuncionario);
        mov.setStatusPagamento(dataQuitacao != null ? "pago" : "pendente");
        mov.setSincronizadoEm(LocalDateTime.now());
        MovimentacaoFinanceira saved = movimentacaoRepo.save(mov);
        return entityToMap(saved);
    }

    public Optional<MovimentacaoFinanceira> buscarMovimentacao(Integer idEmpresa, String idMovimentacao) {
        if (idEmpresa == null || idMovimentacao == null || idMovimentacao.isBlank()) {
            return Optional.empty();
        }
        return movimentacaoRepo.findByIdMovimentacaoAndIdEmpresa(idMovimentacao, idEmpresa);
    }

    /**
     * Busca movimentações no banco local no formato esperado pelo frontend.
     * orderBy: "data" | "valor" | "status" | "tipo" (campo da entidade: dataVencimento, valor, statusPagamento, debito)
     * orderDirection: "asc" | "desc"
     */
    public Map<String, Object> buscarMovimentacoes(
            LocalDate dataInicio, LocalDate dataTermino,
            String tipoData,
            Integer idEmpresa,
            Boolean debito,           // null = todos; true = despesas; false = receitas
            String statusPagamento,   // null | "pendente" | "pago"
            String orderBy,
            String orderDirection,
            int itensPorPagina,
            int numeroDaPagina
    ) {
        String sortField = "dataVencimento";
        if (orderBy != null && !orderBy.isBlank()) {
            switch (orderBy.trim().toLowerCase()) {
                case "valor" -> sortField = "valor";
                case "status" -> sortField = "statusPagamento";
                case "tipo" -> sortField = "debito";
                case "data" -> sortField = "dataVencimento";
                default -> {
                }
            }
        }
        Sort.Direction direction =
                (orderDirection != null && orderDirection.trim().equalsIgnoreCase("desc"))
                        ? Sort.Direction.DESC
                        : Sort.Direction.ASC;
        Sort sort = Sort.by(direction, sortField);
        PageRequest pageable = PageRequest.of(Math.max(0, numeroDaPagina - 1), Math.max(1, itensPorPagina), sort);

        Page<MovimentacaoFinanceira> page;
        boolean useCompetencia = "DataCompetencia".equalsIgnoreCase(tipoData);

        if (debito != null && statusPagamento != null) {
            // debito + statusPagamento — suportado por vencimento
            page = movimentacaoRepo.findByIdEmpresaAndDebitoAndStatusPagamentoAndDataVencimentoBetween(
                    idEmpresa, debito, statusPagamento, dataInicio, dataTermino, pageable);
        } else if (debito != null) {
            if (useCompetencia) {
                page = movimentacaoRepo.findByIdEmpresaAndDebitoAndDataCompetenciaBetween(
                        idEmpresa, debito, dataInicio, dataTermino, pageable);
            } else {
                page = movimentacaoRepo.findByIdEmpresaAndDebitoAndDataVencimentoBetween(
                        idEmpresa, debito, dataInicio, dataTermino, pageable);
            }
        } else {
            if (useCompetencia) {
                page = movimentacaoRepo.findByIdEmpresaAndDataCompetenciaBetween(
                        idEmpresa, dataInicio, dataTermino, pageable);
            } else {
                page = movimentacaoRepo.findByIdEmpresaAndDataVencimentoBetween(
                        idEmpresa, dataInicio, dataTermino, pageable);
            }
        }

        BigDecimal somaReceitas = movimentacaoRepo.sumValorByEmpresaAndDebitoAndVencimento(
                idEmpresa, false, dataInicio, dataTermino);
        BigDecimal somaDespesas = movimentacaoRepo.sumValorByEmpresaAndDebitoAndVencimento(
                idEmpresa, true, dataInicio, dataTermino);

        List<Map<String, Object>> itens = page.getContent().stream()
                .map(this::entityToMap)
                .collect(Collectors.toList());

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("movimentacoes", itens);
        resultado.put("total", page.getTotalElements());
        resultado.put("totalReceitas", somaReceitas != null ? somaReceitas.doubleValue() : 0.0);
        resultado.put("totalDespesas", somaDespesas != null ? somaDespesas.doubleValue() : 0.0);
        resultado.put("saldoLiquido", (somaReceitas != null ? somaReceitas : BigDecimal.ZERO)
                .subtract(somaDespesas != null ? somaDespesas : BigDecimal.ZERO).doubleValue());
        resultado.put("dataInicio", dataInicio.toString());
        resultado.put("dataTermino", dataTermino.toString());
        resultado.put("tipoData", tipoData != null ? tipoData : "DataVencimento");
        resultado.put("endpointUsado", "erp-db");
        resultado.put("fonteDados", "erp-db");
        resultado.put("usandoCache", false);
        resultado.put("atualizadoEm", LocalDateTime.now().toString());
        Map<String, Object> paginacao = new LinkedHashMap<>();
        paginacao.put("itensPorPagina", itensPorPagina);
        paginacao.put("numeroDaPagina", numeroDaPagina);
        paginacao.put("totalItens", page.getTotalElements());
        resultado.put("paginacao", paginacao);
        return resultado;
    }

    /**
     * Gera ResumoFinanceiroDTO a partir dos dados do banco.
     */
    public ResumoFinanceiroDTO gerarResumo(LocalDate dataInicio, LocalDate dataTermino, Integer idEmpresa) {
        List<MovimentacaoFinanceira> all =
                movimentacaoRepo.findAllByIdEmpresaAndDataVencimentoBetween(idEmpresa, dataInicio, dataTermino);

        ResumoFinanceiroDTO.BlocoResumo blocoReceber = calcularBlocoResumo(
                all.stream().filter(m -> Boolean.FALSE.equals(m.getDebito())).collect(Collectors.toList()));
        ResumoFinanceiroDTO.BlocoResumo blocoPagar = calcularBlocoResumo(
                all.stream().filter(m -> Boolean.TRUE.equals(m.getDebito())).collect(Collectors.toList()));

        double saldoDisponivel = blocoReceber.getTotalLiquidado() - blocoPagar.getTotalLiquidado();
        double saldoProjetado = blocoReceber.getTotalGeral() - blocoPagar.getTotalGeral();

        ResumoFinanceiroDTO resumo = ResumoFinanceiroDTO.builder()
                .periodo(ResumoFinanceiroDTO.PeriodoResumo.builder()
                        .dataInicio(dataInicio.toString())
                        .dataTermino(dataTermino.toString())
                        .build())
                .contasReceber(blocoReceber)
                .contasPagar(blocoPagar)
                .saldoDisponivel(saldoDisponivel)
                .saldoProjetado(saldoProjetado)
                .totalMovimentacoes(all.size())
                .usandoCache(false)
                .fonteDados("erp-db")
                .atualizadoEm(LocalDateTime.now().toString())
                .fallbackAtivo(false)
                .build();

        // Enriquecimento de KPIs para cards do dashboard (sempre preenche com valor numérico, inclusive 0).
        dashboardKpiService.preencherKPIs(resumo, dataInicio, dataTermino, idEmpresa);
        return resumo;
    }

    /**
     * Lista empresas disponíveis no ERP (derivadas das movimentações persistidas).
     * O nome exibido prioriza {@code empresa_usuario.nome_empresa} (cadastro local Postgres);
     * só usa o nome gravado na movimentação como fallback.
     * Formato compatível com o frontend existente: [{ "Id": 1, "Nome": "..." }, ...]
     */
    public Map<String, Object> listarEmpresas() {
        List<Integer> ids = movimentacaoRepo.findDistinctIdEmpresas();

        Map<Integer, String> nomeCadastro = new HashMap<>();
        for (Object[] row : empresaUsuarioRepository.findNomesEmpresaCadastroAtivos()) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) {
                continue;
            }
            nomeCadastro.put((Integer) row[0], String.valueOf(row[1]).trim());
        }

        Map<Integer, String> nomeMovimentacao = new HashMap<>();
        for (Object[] r : movimentacaoRepo.listarEmpresasDistinct()) {
            if (r == null || r.length < 2 || r[0] == null) {
                continue;
            }
            Integer id = (Integer) r[0];
            String nm = r[1] != null ? String.valueOf(r[1]).trim() : "";
            if (!nm.isEmpty()) {
                nomeMovimentacao.put(id, nm);
            }
        }

        List<Map<String, Object>> empresas = new ArrayList<>();
        for (Integer id : ids) {
            Map<String, Object> emp = new LinkedHashMap<>();
            emp.put("Id", id);
            String nome = nomeCadastro.get(id);
            if (nome == null || nome.isBlank()) {
                nome = nomeMovimentacao.get(id);
            }
            if (nome == null || nome.isBlank()) {
                nome = "Empresa " + id;
            }
            emp.put("Nome", nome);
            empresas.add(emp);
        }

        Map<String, Object> resposta = new LinkedHashMap<>();
        resposta.put("empresas", empresas);
        return resposta;
    }

    /**
     * Fluxo single-tenant: retorna o primeiro ID de empresa disponível nas movimentações.
     */
    public Optional<Integer> obterPrimeiraEmpresaDisponivelId() {
        return movimentacaoRepo.findFirstByIdEmpresaIsNotNullOrderByIdEmpresaAsc()
                .map(MovimentacaoFinanceira::getIdEmpresa)
                .filter(id -> id != null && id > 0);
    }

    /**
     * Gera DFC orientado ao Plano de Contas (categorias/subcategorias reais da empresa).
     */
    public DfcResponseDTO gerarDfc(LocalDate dataInicio, LocalDate dataTermino, Integer idEmpresa) {
        long t0 = System.currentTimeMillis();

        List<MovimentacaoFinanceira> all =
                movimentacaoRepo.findAllByIdEmpresaAndDataVencimentoBetween(idEmpresa, dataInicio, dataTermino);
        List<CategoriaFinanceiraEmpresa> categorias =
                categoriaFinanceiraRepo.findAllByDeletedFalseAndIdEmpresaOrderByTipoAscParentIdAscOrdemAscNomeAsc(idEmpresa);

        List<YearMonth> meses = new ArrayList<>();
        YearMonth cur = YearMonth.from(dataInicio);
        YearMonth end = YearMonth.from(dataTermino);
        while (!cur.isAfter(end)) {
            meses.add(cur);
            cur = cur.plusMonths(1);
        }

        List<String> mesesLabel = meses.stream().map(this::formatarMesPt).collect(Collectors.toList());

        Map<YearMonth, Integer> monthIndex = new HashMap<>();
        for (int i = 0; i < meses.size(); i++) {
            monthIndex.put(meses.get(i), i);
        }

        List<CategoriaFinanceiraEmpresa> receitaRoots = categorias.stream()
                .filter(c -> c.getTipo() == CategoriaFinanceiraEmpresa.TipoCategoria.RECEITA && c.getParentId() == null)
                .sorted(Comparator
                        .comparing(CategoriaFinanceiraEmpresa::getOrdem, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(CategoriaFinanceiraEmpresa::getNome, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
        List<CategoriaFinanceiraEmpresa> despesaRoots = categorias.stream()
                .filter(c -> c.getTipo() == CategoriaFinanceiraEmpresa.TipoCategoria.DESPESA && c.getParentId() == null)
                .sorted(Comparator
                        .comparing(CategoriaFinanceiraEmpresa::getOrdem, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(CategoriaFinanceiraEmpresa::getNome, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        Map<Long, List<CategoriaFinanceiraEmpresa>> filhosPorParent = categorias.stream()
                .filter(c -> c.getParentId() != null)
                .collect(Collectors.groupingBy(
                        CategoriaFinanceiraEmpresa::getParentId,
                        Collectors.collectingAndThen(Collectors.toList(), list -> {
                            list.sort(Comparator
                                    .comparing(CategoriaFinanceiraEmpresa::getOrdem, Comparator.nullsLast(Integer::compareTo))
                                    .thenComparing(CategoriaFinanceiraEmpresa::getNome, String.CASE_INSENSITIVE_ORDER));
                            return list;
                        })
                ));

        Map<Long, double[]> valoresPorNo = new HashMap<>();
        for (CategoriaFinanceiraEmpresa cat : categorias) {
            valoresPorNo.put(cat.getId(), new double[meses.size()]);
        }

        Map<String, Long> receitaPorNome = indexarPorNome(categorias, CategoriaFinanceiraEmpresa.TipoCategoria.RECEITA);
        Map<String, Long> despesaPorNome = indexarPorNome(categorias, CategoriaFinanceiraEmpresa.TipoCategoria.DESPESA);
        double[] receitasSemCategoria = new double[meses.size()];
        double[] despesasSemCategoria = new double[meses.size()];

        for (MovimentacaoFinanceira mov : all) {
            if (mov.getDataVencimento() == null || mov.getValor() == null) {
                continue;
            }
            Integer idx = monthIndex.get(YearMonth.from(mov.getDataVencimento()));
            if (idx == null) {
                continue;
            }
            double valor = mov.getValor().doubleValue();
            String catNome = normalizarChaveCategoria(mov.getNomeCategoriaFinanceira());

            if (Boolean.TRUE.equals(mov.getDebito())) {
                Long nodeId = despesaPorNome.get(catNome);
                if (nodeId == null) {
                    despesasSemCategoria[idx] += valor;
                } else {
                    valoresPorNo.get(nodeId)[idx] += valor;
                }
            } else {
                Long nodeId = receitaPorNome.get(catNome);
                if (nodeId == null) {
                    receitasSemCategoria[idx] += valor;
                } else {
                    valoresPorNo.get(nodeId)[idx] += valor;
                }
            }
        }

        Set<Long> rootsIds = new HashSet<>();
        receitaRoots.forEach(r -> rootsIds.add(r.getId()));
        despesaRoots.forEach(r -> rootsIds.add(r.getId()));
        for (Long rootId : rootsIds) {
            acumularFilhos(rootId, filhosPorParent, valoresPorNo);
        }

        List<DfcResponseDTO.Linha> linhas = new ArrayList<>();
        linhas.add(buildLinha("RECEITAS", "SECAO", 0, arrayZeros(meses.size()), meses.size()));
        for (CategoriaFinanceiraEmpresa root : receitaRoots) {
            appendArvoreDfc(linhas, root, filhosPorParent, valoresPorNo, "RECEITA", 0);
        }
        if (temValor(receitasSemCategoria)) {
            linhas.add(buildLinha("Sem categoria (Receitas)", "RECEITA", 0, receitasSemCategoria, meses.size()));
        }

        double[] subtotalReceitas = somarPorPrefixo(linhas, "RECEITA", meses.size());
        linhas.add(buildLinha("Subtotal Receitas", "SUBTOTAL_RECEITA", 0, subtotalReceitas, meses.size()));

        linhas.add(buildLinha("DESPESAS", "SECAO", 0, arrayZeros(meses.size()), meses.size()));
        for (CategoriaFinanceiraEmpresa root : despesaRoots) {
            appendArvoreDfc(linhas, root, filhosPorParent, valoresPorNo, "DESPESA", 0);
        }
        if (temValor(despesasSemCategoria)) {
            linhas.add(buildLinha("Sem categoria (Despesas)", "DESPESA", 0, despesasSemCategoria, meses.size()));
        }

        double[] subtotalDespesas = somarPorPrefixo(linhas, "DESPESA", meses.size());
        linhas.add(buildLinha("Subtotal Despesas", "SUBTOTAL_DESPESA", 0, subtotalDespesas, meses.size()));

        double[] resultadoPorMes = new double[meses.size()];
        for (int i = 0; i < meses.size(); i++) {
            resultadoPorMes[i] = subtotalReceitas[i] - subtotalDespesas[i];
        }
        linhas.add(buildLinha("Resultado", "RESULTADO", 0, resultadoPorMes, meses.size()));

        double totalReceitas = sumArray(subtotalReceitas);
        double totalDespesas = sumArray(subtotalDespesas);

        DfcResponseDTO.Indicadores ind = DfcResponseDTO.Indicadores.builder()
                .totalReceitas(totalReceitas)
                .totalDespesas(totalDespesas)
                .resultado(totalReceitas - totalDespesas)
                .margemPercentual(totalReceitas == 0 ? 0.0 : ((totalReceitas - totalDespesas) / totalReceitas) * 100.0)
                .ticketMedio(meses.isEmpty() ? 0.0 : totalReceitas / meses.size())
                .burnRateMensal(meses.isEmpty() ? 0.0 : totalDespesas / meses.size())
                // campos não usados no frontend atual: mantém 0
                .faturamentoNovosContratos(0.0)
                .receitasOperacionais(0.0)
                .outrasEntradas(0.0)
                .custosOperacionais(0.0)
                .despesasOperacionais(0.0)
                .atividadesEstrategicas(0.0)
                .investimentos(0.0)
                .financiamentos(0.0)
                .build();

        double elapsed = System.currentTimeMillis() - t0;
        return DfcResponseDTO.builder()
                .periodo(DfcResponseDTO.Periodo.builder()
                        .dataInicio(dataInicio.toString())
                        .dataTermino(dataTermino.toString())
                        .build())
                .meses(mesesLabel)
                .linhas(linhas)
                .indicadores(ind)
                .fonteDados("erp-db")
                .fallbackAtivo(false)
                .fallbackMetadata(null)
                .totalMovimentacoesProcessadas(all.size())
                .totalMovimentacoesDisponiveis(all.size())
                .paginasProcessadas(1)
                .paginasEstimadas(1)
                .tempoProcessamentoMs(elapsed)
                .usandoCache(false)
                .atualizadoEm(LocalDateTime.now().toString())
                .build();
    }

    private String formatarMesPt(YearMonth ym) {
        final String[] mesesPt = {"Jan","Fev","Mar","Abr","Mai","Jun","Jul","Ago","Set","Out","Nov","Dez"};
        String mm = mesesPt[ym.getMonthValue() - 1];
        String yy = String.valueOf(ym.getYear()).substring(2);
        return mm + "/" + yy;
    }

    private Map<String, Long> indexarPorNome(
            List<CategoriaFinanceiraEmpresa> categorias,
            CategoriaFinanceiraEmpresa.TipoCategoria tipo
    ) {
        Map<String, Long> out = new HashMap<>();
        for (CategoriaFinanceiraEmpresa c : categorias) {
            if (c.getTipo() != tipo) {
                continue;
            }
            String key = normalizarChaveCategoria(c.getNome());
            out.putIfAbsent(key, c.getId());
        }
        return out;
    }

    private String normalizarChaveCategoria(String nome) {
        return nome == null ? "" : nome.trim().toLowerCase();
    }

    private void acumularFilhos(
            Long nodeId,
            Map<Long, List<CategoriaFinanceiraEmpresa>> filhosPorParent,
            Map<Long, double[]> valoresPorNo
    ) {
        List<CategoriaFinanceiraEmpresa> filhos = filhosPorParent.getOrDefault(nodeId, List.of());
        for (CategoriaFinanceiraEmpresa filho : filhos) {
            acumularFilhos(filho.getId(), filhosPorParent, valoresPorNo);
            double[] pai = valoresPorNo.get(nodeId);
            double[] valFilho = valoresPorNo.get(filho.getId());
            for (int i = 0; i < pai.length; i++) {
                pai[i] += valFilho[i];
            }
        }
    }

    private void appendArvoreDfc(
            List<DfcResponseDTO.Linha> out,
            CategoriaFinanceiraEmpresa node,
            Map<Long, List<CategoriaFinanceiraEmpresa>> filhosPorParent,
            Map<Long, double[]> valoresPorNo,
            String tipoLinha,
            int depth
    ) {
        int nivel = depth <= 0 ? 0 : 1;
        out.add(buildLinha(node.getNome(), tipoLinha, nivel, valoresPorNo.get(node.getId()), valoresPorNo.get(node.getId()).length));
        for (CategoriaFinanceiraEmpresa filho : filhosPorParent.getOrDefault(node.getId(), List.of())) {
            appendArvoreDfc(out, filho, filhosPorParent, valoresPorNo, tipoLinha, depth + 1);
        }
    }

    private DfcResponseDTO.Linha buildLinha(String nome, String tipo, int nivel, double[] valores, int mesesCount) {
        List<Double> vals = new ArrayList<>(mesesCount);
        double total = 0.0;
        for (double v : valores) {
            vals.add(v);
            total += v;
        }
        return DfcResponseDTO.Linha.builder()
                .nome(nome)
                .tipo(tipo)
                .nivel(nivel)
                .valores(vals)
                .total(total)
                .media(mesesCount == 0 ? 0.0 : total / mesesCount)
                .build();
    }

    private double[] arrayZeros(int size) {
        return new double[size];
    }

    private boolean temValor(double[] values) {
        for (double v : values) {
            if (Math.abs(v) > 1e-9) {
                return true;
            }
        }
        return false;
    }

    private double[] somarPorPrefixo(List<DfcResponseDTO.Linha> linhas, String tipo, int size) {
        double[] out = new double[size];
        for (DfcResponseDTO.Linha l : linhas) {
            if (!tipo.equals(l.getTipo())) {
                continue;
            }
            List<Double> vals = l.getValores();
            for (int i = 0; i < size && i < vals.size(); i++) {
                out[i] += vals.get(i) != null ? vals.get(i) : 0.0;
            }
        }
        return out;
    }

    private double sumArray(double[] values) {
        double total = 0;
        for (double v : values) {
            total += v;
        }
        return total;
    }

    private ResumoFinanceiroDTO.BlocoResumo calcularBlocoResumo(List<MovimentacaoFinanceira> movs) {
        double totalGeral = movs.stream()
                .mapToDouble(m -> m.getValor() != null ? m.getValor().doubleValue() : 0.0)
                .sum();
        List<MovimentacaoFinanceira> pagos = movs.stream()
                .filter(m -> "pago".equalsIgnoreCase(m.getStatusPagamento()))
                .collect(Collectors.toList());
        List<MovimentacaoFinanceira> pendentes = movs.stream()
                .filter(m -> !"pago".equalsIgnoreCase(m.getStatusPagamento()))
                .collect(Collectors.toList());
        double totalLiquidado = pagos.stream()
                .mapToDouble(m -> m.getValor() != null ? m.getValor().doubleValue() : 0.0).sum();
        double totalPendente = pendentes.stream()
                .mapToDouble(m -> m.getValor() != null ? m.getValor().doubleValue() : 0.0).sum();
        return ResumoFinanceiroDTO.BlocoResumo.builder()
                .totalGeral(totalGeral)
                .totalLiquidado(totalLiquidado)
                .totalPendente(totalPendente)
                .totalContas(movs.size())
                .contasPendentes(pendentes.size())
                .build();
    }

    private Map<String, Object> entityToMap(MovimentacaoFinanceira m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("IdMovimentacaoFinanceiraParcela", m.getIdMovimentacao());
        map.put("Debito", m.getDebito());
        map.put("DataVencimento", m.getDataVencimento() != null ? m.getDataVencimento().toString() : null);
        map.put("DataCompetencia", m.getDataCompetencia() != null ? m.getDataCompetencia().toString() : null);
        map.put("DataQuitacao", m.getDataQuitacao() != null ? m.getDataQuitacao().toString() : null);
        map.put("DataConciliacao", m.getDataConciliacao() != null ? m.getDataConciliacao().toString() : null);
        map.put("Valor", m.getValor() != null ? m.getValor().doubleValue() : 0.0);
        map.put("FormaPagamento", m.getFormaPagamento());
        map.put("NomeFormaPagamento", m.getNomeFormaPagamento());
        map.put("TipoMovimentacao", m.getTipoMovimentacao());
        map.put("NomeTipoMovimentacao", m.getNomeTipoMovimentacao());
        map.put("Nome", m.getNome());
        map.put("Observacao", m.getObservacao());
        map.put("NumeroParcela", m.getNumeroParcela());
        map.put("QuantidadeParcela", m.getQuantidadeParcela());
        map.put("IdCategoriaFinanceira", m.getIdCategoriaFinanceira());
        map.put("NomeCategoriaFinanceira", m.getNomeCategoriaFinanceira());
        map.put("IdContaFinanceira", m.getIdContaFinanceira());
        map.put("NomeContaFinanceira", m.getNomeContaFinanceira());
        map.put("NomeEmpresa", m.getNomeEmpresa());
        map.put("IdCliente", m.getIdCliente());
        map.put("IdFornecedor", m.getIdFornecedor());
        map.put("NomeClienteFornecedor", m.getNomeClienteFornecedor());
        map.put("Departamento", m.getDepartamento());
        map.put("RateioJson", m.getRateioJson());
        map.put("MetadataJson", m.getMetadataJson());
        map.put("IdFuncionario", m.getIdFuncionario());
        return map;
    }
}

