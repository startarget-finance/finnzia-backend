package com.finnza.service;

import com.finnza.domain.entity.Cobranca;
import com.finnza.domain.entity.Contrato;
import com.finnza.dto.response.DfcResponseDTO;
import com.finnza.dto.response.ResumoFinanceiroDTO;
import com.finnza.repository.ContratoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    private final BomControleSyncService syncService;
    private final ContratoRepository contratoRepository;

    public DashboardKpiService(
            BomControleSyncService syncService,
            ContratoRepository contratoRepository
    ) {
        this.syncService = syncService;
        this.contratoRepository = contratoRepository;
    }

    public void preencherKPIs(
            ResumoFinanceiroDTO resumo,
            LocalDate dataInicio,
            LocalDate dataTermino,
            Integer idEmpresa
    ) {
        if (resumo == null) {
            return;
        }

        if (idEmpresa == null || idEmpresa <= 0) {
            // Mantém campos como null (o frontend exibe —).
            return;
        }

        LocalDate end = (dataTermino != null) ? dataTermino : LocalDate.now();
        LocalDate start3 = end.withDayOfMonth(1).minusMonths(2); // janela 3 meses
        LocalDate start6 = end.withDayOfMonth(1).minusMonths(5); // janela 6 meses

        // =========================
        // Custos e Novos Contratos
        // =========================
        try {
            DfcResponseDTO dfcNovos3m = syncService.gerarDFCDoDb(start3, end, idEmpresa);
            double faturamentoNovos = dfcNovos3m != null && dfcNovos3m.getIndicadores() != null
                    ? dfcNovos3m.getIndicadores().getFaturamentoNovosContratos()
                    : 0d;
            resumo.setMediaNovosContratosReais3m(faturamentoNovos / 3d);

            // Unidades (contratos criados na janela 3m)
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
            log.warn("Falha ao calcular KPIs de novos contratos/custos via DFC", e);
        }

        try {
            // Custos médios (últimos 6 meses)
            DfcResponseDTO dfcCustos6m = syncService.gerarDFCDoDb(start6, end, idEmpresa);
            if (dfcCustos6m != null && dfcCustos6m.getIndicadores() != null) {
                var ind = dfcCustos6m.getIndicadores();
                resumo.setMediaCustoFixo(ind.getCustosOperacionais() / 6d);
                resumo.setMediaCustoVariavel(ind.getDespesasOperacionais() / 6d);
                resumo.setMediaCustoEstrategico(ind.getAtividadesEstrategicas() / 6d);
            }

            // Financeiro/Investimento consolidado no período selecionado
            LocalDate inicioSelecionado = (dataInicio != null) ? dataInicio : start6;
            DfcResponseDTO dfcPeriodo = syncService.gerarDFCDoDb(inicioSelecionado, end, idEmpresa);
            if (dfcPeriodo != null && dfcPeriodo.getIndicadores() != null) {
                var indP = dfcPeriodo.getIndicadores();
                resumo.setCustoFinanceiroInvestimento(indP.getInvestimentos() + indP.getFinanciamentos());
            }
        } catch (Exception e) {
            log.warn("Falha ao calcular KPIs de custos/financeiro via DFC", e);
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

