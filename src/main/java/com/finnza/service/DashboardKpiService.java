package com.finnza.service;

import com.finnza.domain.entity.Cobranca;
import com.finnza.domain.entity.Contrato;
import com.finnza.domain.entity.MovimentacaoFinanceira;
import com.finnza.dto.response.ResumoFinanceiroDTO;
import com.finnza.repository.ContratoRepository;
import com.finnza.repository.MovimentacaoFinanceiraRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enriquecimento do endpoint /resumo-financeiro para suportar TODOS os cards do dashboard,
 * sem depender de mocks no frontend.
 *
 * Observação:
 * churn/LTV/inadimplência são calculados com base nos dados disponíveis do banco local
 * (contratos/cobranças). Se a sincronização estiver incompleta para o período,
 * os valores podem vir menores/zerados (porém sempre "reais" do que existe no banco).
 */
@Slf4j
@Service
public class DashboardKpiService {

    private final MovimentacaoFinanceiraRepository movimentacaoRepository;
    private final ContratoRepository contratoRepository;

    public DashboardKpiService(
            MovimentacaoFinanceiraRepository movimentacaoRepository,
            ContratoRepository contratoRepository
    ) {
        this.movimentacaoRepository = movimentacaoRepository;
        this.contratoRepository = contratoRepository;
    }

    @Transactional(readOnly = true)
    public void preencherKPIs(
            ResumoFinanceiroDTO resumo,
            LocalDate dataInicio,
            LocalDate dataTermino,
            Integer idEmpresa
    ) {
        if (resumo == null) {
            return;
        }

        inicializarComZero(resumo);

        if (idEmpresa == null || idEmpresa <= 0) {
            return;
        }

        LocalDate end = (dataTermino != null) ? dataTermino : LocalDate.now();
        LocalDate start3 = end.withDayOfMonth(1).minusMonths(2); // janela 3 meses
        LocalDate start6 = end.withDayOfMonth(1).minusMonths(5); // janela 6 meses
        LocalDate inicioSelecionado = (dataInicio != null) ? dataInicio : start6;

        // =========================
        // Custos / Novos contratos (R$) via movimentações
        // =========================
        try {
            List<MovimentacaoFinanceira> mov3 =
                    movimentacaoRepository.findAllByIdEmpresaAndDataVencimentoBetween(idEmpresa, start3, end);
            List<MovimentacaoFinanceira> mov6 =
                    movimentacaoRepository.findAllByIdEmpresaAndDataVencimentoBetween(idEmpresa, start6, end);
            List<MovimentacaoFinanceira> movPeriodo =
                    movimentacaoRepository.findAllByIdEmpresaAndDataVencimentoBetween(idEmpresa, inicioSelecionado, end);

            // Receita média dos últimos 3 meses (proxy para novos contratos em R$ no contexto atual)
            double receitas3m = mov3.stream()
                    .filter(m -> Boolean.FALSE.equals(m.getDebito()))
                    .mapToDouble(m -> m.getValor() != null ? m.getValor().doubleValue() : 0d)
                    .sum();
            resumo.setMediaNovosContratosReais3m(receitas3m / 3d);

            // Custos médios 6m por heurística de categoria/descrição
            double somaFixo = 0d;
            double somaVariavel = 0d;
            double somaEstrategico = 0d;
            double somaDespesas6m = 0d;
            for (MovimentacaoFinanceira m : mov6) {
                if (!Boolean.TRUE.equals(m.getDebito())) continue;
                double valor = m.getValor() != null ? m.getValor().doubleValue() : 0d;
                somaDespesas6m += valor;
                String texto = textoClassificacao(m);
                if (isCustoFixo(texto)) {
                    somaFixo += valor;
                } else if (isCustoEstrategico(texto)) {
                    somaEstrategico += valor;
                } else {
                    somaVariavel += valor;
                }
            }

            if (somaFixo == 0d && somaVariavel == 0d && somaEstrategico == 0d && somaDespesas6m > 0d) {
                // fallback simples: classifica tudo como variável se não houver pistas
                somaVariavel = somaDespesas6m;
            }
            resumo.setMediaCustoFixo(somaFixo / 6d);
            resumo.setMediaCustoVariavel(somaVariavel / 6d);
            resumo.setMediaCustoEstrategico(somaEstrategico / 6d);

            // Custo financeiro/investimento no período selecionado
            double custoFinInv = movPeriodo.stream()
                    .filter(m -> Boolean.TRUE.equals(m.getDebito()))
                    .filter(m -> isFinanceiroInvestimento(textoClassificacao(m)))
                    .mapToDouble(m -> m.getValor() != null ? m.getValor().doubleValue() : 0d)
                    .sum();
            resumo.setCustoFinanceiroInvestimento(custoFinInv);
        } catch (Exception e) {
            log.warn("Falha ao calcular KPIs financeiros do dashboard", e);
        }

        // =========================
        // Clientes / Churn / LTV
        // =========================
        try {
            List<Contrato> contratos = contratoRepository.findAllNaoDeletadosPorEmpresa(idEmpresa);

            Set<Long> clientesAtivosNoFim = contratos.stream()
                    .filter(c -> !isCancelado(c))
                    .filter(c -> c.getStatus() != Contrato.StatusContrato.PAGO)
                    .filter(c -> c.getCliente() != null && c.getCliente().getId() != null)
                    .map(c -> c.getCliente().getId())
                    .collect(Collectors.toSet());
            resumo.setTotalClientesAtivos((double) clientesAtivosNoFim.size());

            // Churn: clientes que tiveram contrato CANCELADO encerrado na janela 3m.
            Set<Long> churnClientes = contratos.stream()
                    .filter(c -> c.getStatus() == Contrato.StatusContrato.CANCELADO)
                    .filter(c -> c.getCliente() != null && c.getCliente().getId() != null)
                    .filter(c -> {
                        LocalDate encerramento =
                                c.getDataEncerramento() != null
                                        ? c.getDataEncerramento()
                                        : (c.getDataAtualizacao() != null ? c.getDataAtualizacao().toLocalDate() : null);
                        if (encerramento == null) return false;
                        return (!encerramento.isBefore(start3) && !encerramento.isAfter(end));
                    })
                    .map(c -> c.getCliente().getId())
                    .collect(Collectors.toSet());

            // Base: clientes ativos antes do início da janela
            Set<Long> clientesAtivosNoInicio = contratos.stream()
                    .filter(c -> !isCancelado(c))
                    .filter(c -> c.getStatus() != Contrato.StatusContrato.PAGO)
                    .filter(c -> c.getDataCriacao() != null ? c.getDataCriacao().toLocalDate().isBefore(start3) : true)
                    .filter(c -> c.getCliente() != null && c.getCliente().getId() != null)
                    .map(c -> c.getCliente().getId())
                    .collect(Collectors.toSet());

            double churn = 0d;
            if (!clientesAtivosNoInicio.isEmpty()) {
                churn = (churnClientes.size() / (double) clientesAtivosNoInicio.size()) * 100d;
            }
            resumo.setChurnPercent(churn);

            // LTV: média de duração (meses) dos contratos ativos no fim.
            long countLtv = 0;
            double sumMonths = 0d;
            for (Contrato c : contratos) {
                if (c == null || c.getCliente() == null) continue;
                if (c.getStatus() == Contrato.StatusContrato.PAGO || c.getStatus() == Contrato.StatusContrato.CANCELADO) continue;

                LocalDate inicio = c.getInicioRecorrencia() != null
                        ? c.getInicioRecorrencia()
                        : (c.getInicioContrato() != null ? c.getInicioContrato()
                        : (c.getDataCriacao() != null ? c.getDataCriacao().toLocalDate() : null));

                LocalDate fim = (c.getDataVencimento() != null) ? c.getDataVencimento() : end;
                if (inicio == null || fim == null) continue;

                long months = ChronoUnit.MONTHS.between(inicio.withDayOfMonth(1), fim.withDayOfMonth(1)) + 1;
                if (months < 1) months = 1;
                if (months > 120) months = 120;

                sumMonths += months;
                countLtv++;
            }
            resumo.setLtvMeses(countLtv > 0 ? sumMonths / countLtv : 0d);
        } catch (Exception e) {
            log.warn("Falha ao calcular KPIs de churn/LTV", e);
        }

        // Novos contratos em unidades (média mensal últimos 3 meses)
        try {
            List<Contrato> contratos = contratoRepository.findAllNaoDeletadosPorEmpresa(idEmpresa);
            long novosContratos = contratos.stream()
                    .filter(c -> !isCancelado(c))
                    .filter(c -> {
                        LocalDate criacao = c.getDataCriacao() != null
                                ? c.getDataCriacao().toLocalDate()
                                : c.getDataVenda();
                        if (criacao == null) return false;
                        return (!criacao.isBefore(start3) && !criacao.isAfter(end));
                    })
                    .count();
            resumo.setMediaNovosContratosUnidades3m(novosContratos / 3d);
        } catch (Exception e) {
            log.warn("Falha ao calcular KPI de novos contratos (unidades)", e);
        }

        // =========================
        // Inadimplência
        // =========================
        try {
            List<Contrato> contratos = contratoRepository.findAllNaoDeletadosPorEmpresa(idEmpresa);

            double totalValor = 0d;
            double inadimplenciaValor = 0d;

            for (Contrato c : contratos) {
                if (c == null) continue;
                if (c.getStatus() == Contrato.StatusContrato.CANCELADO) continue;

                BigDecimal valorContrato = c.getValorContrato() != null ? c.getValorContrato() : BigDecimal.ZERO;
                totalValor += valorContrato.doubleValue();

                // Garantir carga das cobranças (lazy)
                if (c.getCobrancas() != null) {
                    c.getCobrancas().size();
                }

                long overdueCount = contarParcelasEmAtraso(c, end);
                boolean inAdimplente = overdueCount >= 2;
                if (inAdimplente) {
                    inadimplenciaValor += valorContrato.doubleValue();
                }
            }

            resumo.setInadimplenciaValor(inadimplenciaValor);
            double taxa = totalValor > 0 ? (inadimplenciaValor / totalValor) * 100d : 0d;
            resumo.setInadimplenciaTaxa(taxa);
        } catch (Exception e) {
            log.warn("Falha ao calcular KPIs de inadimplência", e);
        }
    }

    private void inicializarComZero(ResumoFinanceiroDTO resumo) {
        resumo.setMediaNovosContratosReais3m(0d);
        resumo.setMediaNovosContratosUnidades3m(0d);
        resumo.setCustoFinanceiroInvestimento(0d);
        resumo.setMediaCustoFixo(0d);
        resumo.setMediaCustoVariavel(0d);
        resumo.setMediaCustoEstrategico(0d);
        resumo.setTotalClientesAtivos(0d);
        resumo.setChurnPercent(0d);
        resumo.setLtvMeses(0d);
        resumo.setInadimplenciaValor(0d);
        resumo.setInadimplenciaTaxa(0d);
    }

    private String textoClassificacao(MovimentacaoFinanceira m) {
        String categoria = m.getNomeCategoriaFinanceira() != null ? m.getNomeCategoriaFinanceira() : "";
        String tipo = m.getNomeTipoMovimentacao() != null ? m.getNomeTipoMovimentacao() : "";
        String nome = m.getNome() != null ? m.getNome() : "";
        return (categoria + " " + tipo + " " + nome).toLowerCase();
    }

    private boolean isCustoFixo(String texto) {
        return containsAny(texto, "aluguel", "salario", "folha", "pro-labore", "prolabore", "internet", "energia", "contabilidade", "plano", "assinatura");
    }

    private boolean isCustoEstrategico(String texto) {
        return containsAny(texto, "marketing", "trafe", "tráfe", "ads", "consultoria", "branding", "estrateg", "evento", "treinamento");
    }

    private boolean isFinanceiroInvestimento(String texto) {
        return containsAny(texto, "juros", "tarifa", "iof", "financi", "emprest", "invest", "aplicacao", "aplicação", "capital");
    }

    private boolean containsAny(String texto, String... termos) {
        if (texto == null || texto.isBlank()) return false;
        for (String t : termos) {
            if (texto.contains(t)) return true;
        }
        return false;
    }

    private boolean isCancelado(Contrato c) {
        return c != null && c.getStatus() == Contrato.StatusContrato.CANCELADO;
    }

    /**
     * Parcela em atraso = OVERDUE/DUNNING_REQUESTED/CHARGEBACK_REQUESTED
     * OU PENDING com vencimento antes do referenceDate.
     */
    private long contarParcelasEmAtraso(Contrato contrato, LocalDate referenceDate) {
        if (contrato == null || contrato.getCobrancas() == null) return 0;
        return contrato.getCobrancas().stream().filter(cob -> {
            if (cob == null) return false;
            Cobranca.StatusCobranca st = cob.getStatus();
            if (st == null) return false;

            if (st == Cobranca.StatusCobranca.OVERDUE ||
                    st == Cobranca.StatusCobranca.DUNNING_REQUESTED ||
                    st == Cobranca.StatusCobranca.CHARGEBACK_REQUESTED) {
                return true;
            }
            if (st == Cobranca.StatusCobranca.PENDING) {
                LocalDate venc = cob.getDataVencimento();
                return venc != null && venc.isBefore(referenceDate);
            }
            return false;
        }).count();
    }
}

