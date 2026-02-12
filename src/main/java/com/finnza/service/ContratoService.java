package com.finnza.service;

import com.finnza.domain.entity.Cliente;
import com.finnza.domain.entity.Contrato;
import com.finnza.domain.entity.Cobranca;
import com.finnza.dto.request.CriarContratoRequest;
import com.finnza.dto.response.ContratoDTO;
import com.finnza.repository.ClienteRepository;
import com.finnza.repository.ContratoRepository;
import com.finnza.repository.CobrancaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service para gerenciamento de contratos
 */
@Slf4j
@Service
public class ContratoService {

    @Autowired
    private ContratoRepository contratoRepository;

    @Autowired
    private ClienteRepository clienteRepository;

    @Autowired
    private CobrancaRepository cobrancaRepository;

    @Autowired
    private AsaasService asaasService;

    /**
     * Cria um novo contrato
     */
    @Transactional
    public ContratoDTO criarContrato(CriarContratoRequest request) {
        // Buscar ou criar cliente
        Cliente cliente = buscarOuCriarCliente(request.getDadosCliente());

        // Criar contrato
        Contrato contrato = Contrato.builder()
                .titulo(request.getTitulo())
                .descricao(request.getDescricao())
                .conteudo(request.getConteudo())
                .cliente(cliente)
                .valorContrato(request.getValorContrato())
                .valorRecorrencia(request.getValorRecorrencia())
                .dataVencimento(request.getDataVencimento())
                .status(Contrato.StatusContrato.PENDENTE)
                .tipoPagamento(request.getTipoPagamento())
                .servico(request.getServico())
                .inicioContrato(request.getInicioContrato())
                .inicioRecorrencia(request.getInicioRecorrencia())
                .whatsapp(request.getWhatsapp())
                .build();

        contrato = contratoRepository.save(contrato);

        // Integrar com Asaas
        try {
            if (request.getTipoPagamento() == Contrato.TipoPagamento.UNICO) {
                criarCobrancaUnica(contrato, cliente, request);
            } else {
                criarAssinatura(contrato, cliente);
            }
        } catch (Exception e) {
            log.error("Erro ao criar cobrança no Asaas para contrato {}", contrato.getId(), e);
            // Não falha o contrato, apenas loga o erro
        }

        return toDTO(contrato);
    }

    /**
     * Lista contratos com paginação
     */
    @Transactional
    public Page<ContratoDTO> listarTodos(Pageable pageable) {
        return contratoRepository.findAllNaoDeletados(pageable)
                .map(contrato -> {
                    // Recalcular status automaticamente antes de retornar
                    recalcularEAtualizarStatus(contrato);
                    return toDTO(contrato);
                });
    }

    /**
     * Busca contrato por ID
     * Sincroniza com Asaas para garantir dados atualizados ao visualizar um contrato específico
     */
    @Transactional
    public ContratoDTO buscarPorId(Long id) {
        Contrato contrato = contratoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Contrato não encontrado"));

        if (contrato.getDeleted()) {
            throw new RuntimeException("Contrato não encontrado");
        }

        // Sincronizar com Asaas ao buscar um contrato específico (apenas um contrato, não causa rate limit)
        recalcularEAtualizarStatus(contrato, true);
        
        return toDTO(contrato);
    }

    /**
     * Busca contratos por cliente
     */
    @Transactional
    public Page<ContratoDTO> buscarPorCliente(Long clienteId, Pageable pageable) {
        return contratoRepository.findByClienteId(clienteId, pageable)
                .map(contrato -> {
                    recalcularEAtualizarStatus(contrato);
                    return toDTO(contrato);
                });
    }

    /**
     * Busca contratos por status
     */
    @Transactional
    public Page<ContratoDTO> buscarPorStatus(Contrato.StatusContrato status, Pageable pageable) {
        return contratoRepository.findByStatus(status, pageable)
                .map(contrato -> {
                    recalcularEAtualizarStatus(contrato);
                    return toDTO(contrato);
                });
    }

    /**
     * Busca contratos com filtros (igual ao Asaas)
     */
    @Transactional
    public Page<ContratoDTO> buscarComFiltros(
            Long clienteId, 
            String status, 
            String termo, 
            String billingType,
            String dueDateGe,
            String dueDateLe,
            String paymentDateGe,
            String paymentDateLe,
            Pageable pageable) {
        
        // Normalizar termo
        String termoNormalizado = (termo != null && !termo.trim().isEmpty()) ? termo.trim() : null;
        
        // Verificar se há filtros complexos que requerem busca completa
        boolean temFiltrosComplexos = (status != null && !status.trim().isEmpty() && !status.equals("todos")) ||
                                      (billingType != null && !billingType.trim().isEmpty() && !billingType.equals("todos")) ||
                                      (dueDateGe != null && !dueDateGe.trim().isEmpty()) ||
                                      (dueDateLe != null && !dueDateLe.trim().isEmpty()) ||
                                      (paymentDateGe != null && !paymentDateGe.trim().isEmpty()) ||
                                      (paymentDateLe != null && !paymentDateLe.trim().isEmpty());
        
        // Se não há filtros complexos, retornar diretamente a página do banco
        if (!temFiltrosComplexos) {
            Page<Contrato> contratosPage = contratoRepository.buscarComFiltros(clienteId, termoNormalizado, pageable);
            return contratosPage.map(contrato -> {
                recalcularEAtualizarStatus(contrato, false);
                return toDTO(contrato);
            });
        }
        
        // Se há filtros complexos, buscar TODOS os contratos (sem paginação) para filtrar corretamente
        Page<Contrato> todasPaginas = contratoRepository.buscarComFiltros(
            clienteId, 
            termoNormalizado, 
            org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE)
        );
        List<Contrato> todosContratos = todasPaginas.getContent();
        
        // Filtrar por status de cobrança (status do Asaas) e datas
        List<Contrato> contratosFiltrados = todosContratos.stream()
                .filter(contrato -> {
                    // Forçar carregamento das cobranças
                    if (contrato.getCobrancas() != null) {
                        contrato.getCobrancas().size();
                    }
                    
                    // Filtrar por status (status do Asaas nas cobranças)
                    if (status != null && !status.trim().isEmpty() && !status.equals("todos")) {
                        boolean statusMatch = false;
                        String statusUpper = status.toUpperCase();
                        
                        // Mapear status do Asaas para status de cobrança
                        if (contrato.getCobrancas() != null && !contrato.getCobrancas().isEmpty()) {
                            for (Cobranca cobranca : contrato.getCobrancas()) {
                                switch (statusUpper) {
                                    case "PENDING":
                                        statusMatch = cobranca.getStatus() == Cobranca.StatusCobranca.PENDING;
                                        break;
                                    case "OVERDUE":
                                        statusMatch = cobranca.getStatus() == Cobranca.StatusCobranca.OVERDUE;
                                        break;
                                    case "RECEIVED":
                                    case "CONFIRMED":
                                        statusMatch = cobranca.getStatus() == Cobranca.StatusCobranca.RECEIVED;
                                        break;
                                    case "REFUNDED":
                                    case "REFUNDING":
                                        statusMatch = cobranca.getStatus() == Cobranca.StatusCobranca.REFUNDED;
                                        break;
                                    case "RECEIVED_IN_CASH_UNDONE":
                                        statusMatch = cobranca.getStatus() == Cobranca.StatusCobranca.RECEIVED_IN_CASH_UNDONE;
                                        break;
                                    case "CHARGEBACK_REQUESTED":
                                        statusMatch = cobranca.getStatus() == Cobranca.StatusCobranca.CHARGEBACK_REQUESTED;
                                        break;
                                    default:
                                        // Tentar mapear para status de contrato
                                        try {
                                            Contrato.StatusContrato statusContrato = Contrato.StatusContrato.valueOf(statusUpper);
                                            statusMatch = contrato.getStatus() == statusContrato;
                                        } catch (IllegalArgumentException e) {
                                            statusMatch = true; // Se não reconhecer, não filtra
                                        }
                                        break;
                                }
                                if (statusMatch) break;
                            }
                        } else {
                            // Se não tem cobranças, tentar mapear para status de contrato
                            try {
                                Contrato.StatusContrato statusContrato = Contrato.StatusContrato.valueOf(statusUpper);
                                statusMatch = contrato.getStatus() == statusContrato;
                            } catch (IllegalArgumentException e) {
                                statusMatch = false;
                            }
                        }
                        
                        if (!statusMatch) return false;
                    }
                    
                    // Filtrar por data de vencimento
                    if (dueDateGe != null && !dueDateGe.trim().isEmpty()) {
                        LocalDate dataInicio = LocalDate.parse(dueDateGe);
                        if (contrato.getCobrancas() != null && !contrato.getCobrancas().isEmpty()) {
                            boolean temCobrancaValida = contrato.getCobrancas().stream()
                                    .anyMatch(c -> c.getDataVencimento() != null && 
                                                  !c.getDataVencimento().isBefore(dataInicio));
                            if (!temCobrancaValida && contrato.getDataVencimento() != null && 
                                contrato.getDataVencimento().isBefore(dataInicio)) {
                                return false;
                            }
                        } else if (contrato.getDataVencimento() != null && 
                                  contrato.getDataVencimento().isBefore(dataInicio)) {
                            return false;
                        }
                    }
                    
                    if (dueDateLe != null && !dueDateLe.trim().isEmpty()) {
                        LocalDate dataFim = LocalDate.parse(dueDateLe);
                        if (contrato.getCobrancas() != null && !contrato.getCobrancas().isEmpty()) {
                            boolean temCobrancaValida = contrato.getCobrancas().stream()
                                    .anyMatch(c -> c.getDataVencimento() != null && 
                                                  !c.getDataVencimento().isAfter(dataFim));
                            if (!temCobrancaValida && contrato.getDataVencimento() != null && 
                                contrato.getDataVencimento().isAfter(dataFim)) {
                                return false;
                            }
                        } else if (contrato.getDataVencimento() != null && 
                                  contrato.getDataVencimento().isAfter(dataFim)) {
                            return false;
                        }
                    }
                    
                    // Filtrar por data de pagamento
                    if (paymentDateGe != null && !paymentDateGe.trim().isEmpty()) {
                        LocalDate dataInicio = LocalDate.parse(paymentDateGe);
                        if (contrato.getCobrancas() == null || contrato.getCobrancas().isEmpty() ||
                            contrato.getCobrancas().stream().noneMatch(c -> 
                                c.getDataPagamento() != null && !c.getDataPagamento().isBefore(dataInicio))) {
                            return false;
                        }
                    }
                    
                    if (paymentDateLe != null && !paymentDateLe.trim().isEmpty()) {
                        LocalDate dataFim = LocalDate.parse(paymentDateLe);
                        if (contrato.getCobrancas() == null || contrato.getCobrancas().isEmpty() ||
                            contrato.getCobrancas().stream().noneMatch(c -> 
                                c.getDataPagamento() != null && !c.getDataPagamento().isAfter(dataFim))) {
                            return false;
                        }
                    }
                    
                    // Filtrar por tipo de pagamento (billingType)
                    // Nota: billingType não está armazenado na entidade Cobranca
                    // Por enquanto, este filtro não está totalmente funcional pois não temos essa informação persistida
                    // TODO: Adicionar campo billingType à entidade Cobranca para suportar este filtro corretamente
                    if (billingType != null && !billingType.trim().isEmpty() && !billingType.equals("todos")) {
                        // Por enquanto, não filtramos por billingType pois essa informação não está disponível
                        // Isso pode ser implementado no futuro adicionando o campo à entidade Cobranca
                        // Por enquanto, aceita todos os contratos (filtro não aplicado)
                    }
                    
                    return true;
                })
                .collect(java.util.stream.Collectors.toList());
        
        // Aplicar paginação manualmente
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), contratosFiltrados.size());
        List<Contrato> contratosPaginados = start < contratosFiltrados.size() 
                ? contratosFiltrados.subList(start, end) 
                : java.util.Collections.emptyList();
        
        // Recalcular status e converter para DTO
        List<ContratoDTO> contratosDTO = contratosPaginados.stream()
                .map(contrato -> {
                    recalcularEAtualizarStatus(contrato, false);
                    return toDTO(contrato);
                })
                .collect(java.util.stream.Collectors.toList());
        
        return new org.springframework.data.domain.PageImpl<>(
                contratosDTO, 
                pageable, 
                contratosFiltrados.size()
        );
    }

    /**
     * Remove contrato (soft delete)
     */
    @Transactional
    public void removerContrato(Long id) {
        Contrato contrato = contratoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Contrato não encontrado"));

        contrato.softDelete();
        contratoRepository.save(contrato);
    }

    /**
     * Sincroniza status de um contrato com o Asaas
     * Consulta o status atual das cobranças no Asaas e atualiza no banco
     */
    @Transactional
    public ContratoDTO sincronizarStatusComAsaas(Long id) {
        Contrato contrato = contratoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Contrato não encontrado"));

        if (contrato.getDeleted()) {
            throw new RuntimeException("Contrato não encontrado");
        }

        // Sincronizar cobranças
        if (contrato.getCobrancas() != null && !contrato.getCobrancas().isEmpty()) {
            for (Cobranca cobranca : contrato.getCobrancas()) {
                if (cobranca.getAsaasPaymentId() != null && !cobranca.getAsaasPaymentId().isEmpty()) {
                    try {
                        Map<String, Object> paymentData = asaasService.consultarCobranca(cobranca.getAsaasPaymentId());
                        
                        // Se retornou null, a cobrança não existe mais no Asaas (404)
                        if (paymentData == null) {
                            log.warn("Cobrança {} (Asaas: {}) não encontrada no Asaas. Mantendo status local.", 
                                    cobranca.getId(), cobranca.getAsaasPaymentId());
                            continue;
                        }
                        
                        String statusAsaas = (String) paymentData.get("status");
                        
                        // Atualizar status da cobrança
                        Cobranca.StatusCobranca novoStatus = mapearStatusCobranca(statusAsaas);
                        cobranca.setStatus(novoStatus);
                        
                        // Se foi pago, atualizar data de pagamento
                        if (novoStatus == Cobranca.StatusCobranca.RECEIVED || 
                            novoStatus == Cobranca.StatusCobranca.RECEIVED_IN_CASH_UNDONE ||
                            novoStatus == Cobranca.StatusCobranca.DUNNING_RECEIVED) {
                            Object paymentDate = paymentData.get("paymentDate");
                            if (paymentDate != null) {
                                String dateStr = paymentDate.toString();
                                if (dateStr.length() >= 10) {
                                    cobranca.setDataPagamento(LocalDate.parse(dateStr.substring(0, 10), 
                                        java.time.format.DateTimeFormatter.ISO_DATE));
                                }
                            }
                        }
                        
                        cobrancaRepository.save(cobranca);
                        log.info("Cobrança {} sincronizada com Asaas. Status: {}", cobranca.getId(), statusAsaas);
                    } catch (Exception e) {
                        log.warn("Erro ao sincronizar cobrança {} com Asaas: {}", cobranca.getId(), e.getMessage());
                    }
                }
            }
        }

        // Recalcular status do contrato baseado nas cobranças atualizadas
        contrato.calcularStatusBaseadoNasCobrancas();
        contratoRepository.save(contrato);
        
        log.info("Contrato {} sincronizado com Asaas. Novo status: {}", contrato.getId(), contrato.getStatus());
        
        return toDTO(contrato);
    }

    /**
     * Mapeia status do Asaas para enum de cobrança.
     * O Asaas pode retornar status que não existem diretamente no nosso enum,
     * como CONFIRMED e RECEIVED_IN_CASH. Mapeamento explícito para evitar perda de status.
     * 
     * Status do Asaas: PENDING, RECEIVED, CONFIRMED, OVERDUE, REFUNDED, 
     *   RECEIVED_IN_CASH, REFUND_REQUESTED, REFUND_IN_PROGRESS,
     *   CHARGEBACK_REQUESTED, CHARGEBACK_DISPUTE, AWAITING_CHARGEBACK_REVERSAL,
     *   DUNNING_REQUESTED, DUNNING_RECEIVED, AWAITING_RISK_ANALYSIS
     */
    private Cobranca.StatusCobranca mapearStatusCobranca(String statusAsaas) {
        if (statusAsaas == null) {
            return Cobranca.StatusCobranca.PENDING;
        }

        switch (statusAsaas.toUpperCase()) {
            // === PAGOS ===
            case "RECEIVED":
                return Cobranca.StatusCobranca.RECEIVED;
            case "CONFIRMED":
                // Asaas retorna CONFIRMED para pagamentos confirmados (cartão de crédito, PIX, etc.)
                // Deve ser tratado como PAGO
                return Cobranca.StatusCobranca.RECEIVED;
            case "RECEIVED_IN_CASH":
                // Pagamento recebido em dinheiro/manual
                return Cobranca.StatusCobranca.RECEIVED;
            case "DUNNING_RECEIVED":
                return Cobranca.StatusCobranca.DUNNING_RECEIVED;
            
            // === PENDENTES ===
            case "PENDING":
                return Cobranca.StatusCobranca.PENDING;
            case "AWAITING_RISK_ANALYSIS":
                return Cobranca.StatusCobranca.AWAITING_RISK_ANALYSIS;
            
            // === VENCIDOS ===
            case "OVERDUE":
                return Cobranca.StatusCobranca.OVERDUE;
            case "DUNNING_REQUESTED":
                return Cobranca.StatusCobranca.DUNNING_REQUESTED;
            
            // === ESTORNOS/CHARGEBACKS ===
            case "REFUNDED":
            case "REFUND_REQUESTED":
            case "REFUND_IN_PROGRESS":
                return Cobranca.StatusCobranca.REFUNDED;
            case "CHARGEBACK_REQUESTED":
                return Cobranca.StatusCobranca.CHARGEBACK_REQUESTED;
            case "CHARGEBACK_DISPUTE":
                return Cobranca.StatusCobranca.CHARGEBACK_DISPUTE;
            case "AWAITING_CHARGEBACK_REVERSAL":
                return Cobranca.StatusCobranca.AWAITING_CHARGEBACK_REVERSAL;
            case "RECEIVED_IN_CASH_UNDONE":
                return Cobranca.StatusCobranca.RECEIVED_IN_CASH_UNDONE;
            
            default:
                log.warn("Status desconhecido do Asaas: '{}'. Mapeando como PENDING.", statusAsaas);
                return Cobranca.StatusCobranca.PENDING;
        }
    }

    /**
     * Recalcula e atualiza o status do contrato se necessário
     * Sincroniza com Asaas antes de recalcular para garantir dados atualizados (apenas se sincronizar = true)
     * Salva apenas se o status mudou para evitar writes desnecessários
     * 
     * @param contrato Contrato a ser recalculado
     * @param sincronizarComAsaas Se true, sincroniza com Asaas antes de recalcular. Se false, apenas recalcula baseado nos dados já salvos.
     */
    private void recalcularEAtualizarStatus(Contrato contrato, boolean sincronizarComAsaas) {
        // Garantir que as cobranças sejam carregadas (forçar inicialização do lazy)
        if (contrato.getCobrancas() != null) {
            contrato.getCobrancas().size(); // Força o carregamento do lazy collection
        }
        
        // Se o contrato tem asaasSubscriptionId mas não tem cobranças, importar do Asaas
        if (sincronizarComAsaas && !asaasService.isMockEnabled() 
                && contrato.getAsaasSubscriptionId() != null && !contrato.getAsaasSubscriptionId().isEmpty()
                && (contrato.getCobrancas() == null || contrato.getCobrancas().isEmpty())) {
            log.info("Contrato {} tem subscriptionId {} mas 0 cobranças. Importando do Asaas...", 
                    contrato.getId(), contrato.getAsaasSubscriptionId());
            importarCobrancasDoAsaas(contrato);
        }
        
        // Sincronizar cobranças com Asaas apenas se solicitado e não estiver em modo mock
        if (sincronizarComAsaas && !asaasService.isMockEnabled() && contrato.getCobrancas() != null && !contrato.getCobrancas().isEmpty()) {
            log.debug("Sincronizando cobranças do contrato {} com Asaas", contrato.getId());
            sincronizarCobrancasComAsaas(contrato);
        }
        
        // Sempre recalcular status baseado nas cobranças (mesmo em mock, baseado em datas)
        Contrato.StatusContrato statusAnterior = contrato.getStatus();
        contrato.calcularStatusBaseadoNasCobrancas();
        
        // Salvar apenas se o status mudou
        if (statusAnterior != contrato.getStatus()) {
            contratoRepository.save(contrato);
            log.info("Status do contrato {} atualizado automaticamente de {} para {}", 
                    contrato.getId(), statusAnterior, contrato.getStatus());
        }
    }
    
    /**
     * Recalcula e atualiza o status do contrato sem sincronizar com Asaas
     * Usado em buscas/listagens para evitar muitas requisições à API do Asaas
     */
    private void recalcularEAtualizarStatus(Contrato contrato) {
        recalcularEAtualizarStatus(contrato, false);
    }

    /**
     * Sincroniza cobranças com Asaas
     * Sincroniza todas as cobranças que têm asaasPaymentId e não estão pagas/canceladas
     */
    private void sincronizarCobrancasComAsaas(Contrato contrato) {
        LocalDate hoje = LocalDate.now();
        boolean algumaCobrancaAtualizada = false;
        
        for (Cobranca cobranca : contrato.getCobrancas()) {
            // Sincronizar apenas cobranças que têm ID do Asaas
            if (cobranca.getAsaasPaymentId() != null && !cobranca.getAsaasPaymentId().isEmpty()) {
                boolean deveSincronizar = false;
                
                // Sincronizar se:
                // 1. Está PENDING (pode ter mudado para RECEIVED ou OVERDUE)
                // 2. Está OVERDUE mas pode ter sido paga
                // 3. Vence hoje ou já venceu (pode ter mudado)
                if (cobranca.getStatus() == Cobranca.StatusCobranca.PENDING) {
                    deveSincronizar = true;
                } else if (cobranca.getStatus() == Cobranca.StatusCobranca.OVERDUE) {
                    // Pode ter sido paga mesmo estando OVERDUE
                    deveSincronizar = true;
                } else if (cobranca.getDataVencimento() != null) {
                    long diasAteVencimento = java.time.temporal.ChronoUnit.DAYS.between(hoje, cobranca.getDataVencimento());
                    // Sincronizar se vence hoje ou já venceu
                    if (diasAteVencimento <= 0) {
                        deveSincronizar = true;
                    }
                }
                
                if (deveSincronizar) {
                    try {
                        Map<String, Object> paymentData = asaasService.consultarCobranca(cobranca.getAsaasPaymentId());
                        
                        // Se retornou null, a cobrança não existe mais no Asaas (404)
                        if (paymentData == null) {
                            log.warn("Cobrança {} (Asaas: {}) não encontrada no Asaas. Mantendo status local: {}", 
                                    cobranca.getId(), cobranca.getAsaasPaymentId(), cobranca.getStatus());
                            continue;
                        }
                        
                        String statusAsaas = (String) paymentData.get("status");
                        
                        // Atualizar status da cobrança se mudou
                        Cobranca.StatusCobranca statusAnterior = cobranca.getStatus();
                        Cobranca.StatusCobranca novoStatus = mapearStatusCobranca(statusAsaas);
                        if (statusAnterior != novoStatus) {
                            cobranca.setStatus(novoStatus);
                            algumaCobrancaAtualizada = true;
                            log.info("Cobrança {} sincronizada com Asaas: {} -> {}", 
                                    cobranca.getId(), statusAnterior, novoStatus);
                            
                            // Se foi pago, atualizar data de pagamento
                            if (novoStatus == Cobranca.StatusCobranca.RECEIVED || 
                                novoStatus == Cobranca.StatusCobranca.RECEIVED_IN_CASH_UNDONE ||
                                novoStatus == Cobranca.StatusCobranca.DUNNING_RECEIVED) {
                                Object paymentDate = paymentData.get("paymentDate");
                                if (paymentDate != null) {
                                    String dateStr = paymentDate.toString();
                                    if (dateStr.length() >= 10) {
                                        cobranca.setDataPagamento(LocalDate.parse(dateStr.substring(0, 10), 
                                            java.time.format.DateTimeFormatter.ISO_DATE));
                                    }
                                }
                            }
                            
                            cobrancaRepository.save(cobranca);
                        }
                    } catch (Exception e) {
                        // Não falha o processo se uma cobrança não conseguir sincronizar
                        log.warn("Erro ao sincronizar cobrança {} com Asaas: {}", cobranca.getId(), e.getMessage());
                    }
                }
            }
        }
        
        if (algumaCobrancaAtualizada) {
            log.info("Cobranças do contrato {} sincronizadas com Asaas", contrato.getId());
        }
    }

    /**
     * Importa cobranças do Asaas para um contrato que tem asaasSubscriptionId mas não tem cobranças.
     * Isso pode acontecer se a importação original falhou (ex: rate limit 429).
     */
    private void importarCobrancasDoAsaas(Contrato contrato) {
        try {
            java.util.List<Map<String, Object>> cobrancasAsaas = asaasService.listarCobrancasPorAssinatura(contrato.getAsaasSubscriptionId());
            
            if (cobrancasAsaas == null || cobrancasAsaas.isEmpty()) {
                log.warn("Nenhuma cobrança encontrada no Asaas para assinatura {}", contrato.getAsaasSubscriptionId());
                return;
            }
            
            log.info("Encontradas {} cobranças no Asaas para assinatura {}", cobrancasAsaas.size(), contrato.getAsaasSubscriptionId());
            
            int parcelaIndex = 0;
            for (Map<String, Object> cobrancaAsaas : cobrancasAsaas) {
                parcelaIndex++;
                try {
                    String paymentId = (String) cobrancaAsaas.get("id");
                    
                    // Verificar se essa cobrança já existe no banco
                    Optional<Cobranca> cobrancaExistente = cobrancaRepository.findByAsaasPaymentId(paymentId);
                    if (cobrancaExistente.isPresent()) {
                        log.debug("Cobrança {} já existe, pulando...", paymentId);
                        continue;
                    }
                    
                    Object cobValorObj = cobrancaAsaas.get("value");
                    BigDecimal cobValor = cobValorObj != null ? new BigDecimal(cobValorObj.toString()) : BigDecimal.ZERO;
                    
                    Object cobDueDateObj = cobrancaAsaas.get("dueDate");
                    LocalDate cobDataVencimento = null;
                    if (cobDueDateObj != null) {
                        String dateStr = cobDueDateObj.toString();
                        if (dateStr.length() >= 10) {
                            cobDataVencimento = LocalDate.parse(dateStr.substring(0, 10));
                        }
                    }
                    
                    Object cobPaymentDateObj = cobrancaAsaas.get("paymentDate");
                    LocalDate cobDataPagamento = null;
                    if (cobPaymentDateObj != null) {
                        String dateStr = cobPaymentDateObj.toString();
                        if (dateStr.length() >= 10) {
                            cobDataPagamento = LocalDate.parse(dateStr.substring(0, 10));
                        }
                    }
                    
                    String statusStr = (String) cobrancaAsaas.get("status");
                    Cobranca.StatusCobranca status = mapearStatusCobranca(statusStr);
                    
                    Object installmentObj = cobrancaAsaas.get("installmentNumber");
                    Integer numeroParcela = installmentObj != null ? Integer.valueOf(installmentObj.toString()) : parcelaIndex;
                    
                    Cobranca cobranca = Cobranca.builder()
                            .contrato(contrato)
                            .valor(cobValor)
                            .dataVencimento(cobDataVencimento != null ? cobDataVencimento : contrato.getDataVencimento())
                            .dataPagamento(cobDataPagamento)
                            .status(status)
                            .asaasPaymentId(paymentId)
                            .linkPagamento((String) cobrancaAsaas.get("invoiceUrl"))
                            .codigoBarras((String) cobrancaAsaas.get("nossoNumero"))
                            .numeroParcela(numeroParcela)
                            .build();
                    
                    contrato.adicionarCobranca(cobranca);
                    log.debug("Parcela {}/{} importada: paymentId={}, status={}, valor={}", 
                            numeroParcela, cobrancasAsaas.size(), paymentId, statusStr, cobValor);
                } catch (Exception e) {
                    log.warn("Erro ao importar cobrança da assinatura {}: {}", contrato.getAsaasSubscriptionId(), e.getMessage());
                }
            }
            
            // Salvar contrato com as novas cobranças
            contratoRepository.save(contrato);
            log.info("✓ {} cobranças importadas para contrato {} (assinatura {})", 
                    contrato.getCobrancas().size(), contrato.getId(), contrato.getAsaasSubscriptionId());
                    
        } catch (Exception e) {
            log.error("Erro ao importar cobranças do Asaas para contrato {}: {}", contrato.getId(), e.getMessage());
        }
    }

    /**
     * Busca ou cria cliente
     */
    private Cliente buscarOuCriarCliente(CriarContratoRequest.DadosClienteRequest dadosCliente) {
        // Se já tem ID, busca
        if (dadosCliente.getClienteId() != null) {
            return clienteRepository.findById(dadosCliente.getClienteId())
                    .orElseThrow(() -> new RuntimeException("Cliente não encontrado"));
        }

        // Verifica se já existe por CPF/CNPJ
        Cliente cliente = clienteRepository.findByCpfCnpj(dadosCliente.getCpfCnpj())
                .orElse(null);

        if (cliente == null) {
            // Cria novo cliente
            cliente = Cliente.builder()
                    .razaoSocial(dadosCliente.getRazaoSocial())
                    .nomeFantasia(dadosCliente.getNomeFantasia())
                    .cpfCnpj(dadosCliente.getCpfCnpj())
                    .enderecoCompleto(dadosCliente.getEnderecoCompleto())
                    .cep(dadosCliente.getCep())
                    .celularFinanceiro(dadosCliente.getCelularFinanceiro())
                    .emailFinanceiro(dadosCliente.getEmailFinanceiro())
                    .responsavel(dadosCliente.getResponsavel())
                    .cpf(dadosCliente.getCpf())
                    .build();

            cliente = clienteRepository.save(cliente);
        }

        // Se o cliente não tem ID do Asaas, tenta criar
        if (cliente.getAsaasCustomerId() == null || cliente.getAsaasCustomerId().isEmpty()) {
            log.info("Cliente {} não tem ID do Asaas, tentando criar...", cliente.getId());
            String asaasCustomerId = asaasService.criarCliente(cliente);
            if (asaasCustomerId != null) {
                cliente.setAsaasCustomerId(asaasCustomerId);
                cliente = clienteRepository.save(cliente);
                log.info("Cliente {} criado no Asaas com ID: {}", cliente.getId(), asaasCustomerId);
            } else {
                log.warn("Cliente {} não foi criado no Asaas (verifique os logs acima)", cliente.getId());
            }
        }

        return cliente;
    }

    /**
     * Cria cobrança única no Asaas
     */
    private void criarCobrancaUnica(Contrato contrato, Cliente cliente, CriarContratoRequest request) {
        if (cliente.getAsaasCustomerId() == null) {
            log.warn("Cliente {} não tem ID do Asaas, pulando criação de cobrança", cliente.getId());
            return;
        }

        String descricao = String.format("Contrato: %s", contrato.getTitulo());
        if (request.getDescricao() != null && !request.getDescricao().isEmpty()) {
            descricao = request.getDescricao();
        }
        
        Map<String, Object> response = asaasService.criarCobrancaUnica(
                cliente.getAsaasCustomerId(),
                contrato.getValorContrato(),
                contrato.getDataVencimento(),
                descricao,
                request.getFormaPagamento(),
                request.getJurosAoMes(),
                request.getMultaPorAtraso(),
                request.getDescontoPercentual(),
                request.getDescontoValorFixo(),
                request.getPrazoMaximoDesconto(),
                request.getNumeroParcelas()
        );

        // Criar cobrança no banco
        Cobranca cobranca = Cobranca.builder()
                .contrato(contrato)
                .valor(contrato.getValorContrato())
                .dataVencimento(contrato.getDataVencimento())
                .status(Cobranca.StatusCobranca.PENDING)
                .asaasPaymentId((String) response.get("id"))
                .linkPagamento((String) response.get("invoiceUrl"))
                .codigoBarras((String) response.get("barcode"))
                .build();

        contrato.adicionarCobranca(cobranca);
        // Recalcular status do contrato baseado nas cobranças
        contrato.calcularStatusBaseadoNasCobrancas();
        contratoRepository.save(contrato);
    }

    /**
     * Cria assinatura (recorrente) no Asaas
     */
    private void criarAssinatura(Contrato contrato, Cliente cliente) {
        if (cliente.getAsaasCustomerId() == null) {
            log.warn("Cliente {} não tem ID do Asaas, pulando criação de assinatura", cliente.getId());
            return;
        }

        LocalDate dataInicio = contrato.getInicioRecorrencia() != null 
                ? contrato.getInicioRecorrencia() 
                : contrato.getDataVencimento();

        String descricao = String.format("Contrato Recorrente: %s", contrato.getTitulo());
        Map<String, Object> response = asaasService.criarAssinatura(
                cliente.getAsaasCustomerId(),
                contrato.getValorRecorrencia(),
                dataInicio,
                descricao
        );

        contrato.setAsaasSubscriptionId((String) response.get("id"));
        contratoRepository.save(contrato);
    }

    /**
     * Converte entidade para DTO
     */
    private ContratoDTO toDTO(Contrato contrato) {
        ContratoDTO.ClienteDTO clienteDTO = ContratoDTO.ClienteDTO.builder()
                .id(contrato.getCliente().getId())
                .razaoSocial(contrato.getCliente().getRazaoSocial())
                .nomeFantasia(contrato.getCliente().getNomeFantasia())
                .cpfCnpj(contrato.getCliente().getCpfCnpj())
                .emailFinanceiro(contrato.getCliente().getEmailFinanceiro())
                .celularFinanceiro(contrato.getCliente().getCelularFinanceiro())
                .build();

        List<ContratoDTO.CobrancaDTO> cobrancasDTO = contrato.getCobrancas().stream()
                .map(c -> ContratoDTO.CobrancaDTO.builder()
                        .id(c.getId())
                        .valor(c.getValor())
                        .dataVencimento(c.getDataVencimento())
                        .dataPagamento(c.getDataPagamento())
                        .status(c.getStatus())
                        .linkPagamento(c.getLinkPagamento())
                        .codigoBarras(c.getCodigoBarras())
                        .numeroParcela(c.getNumeroParcela())
                        .asaasPaymentId(c.getAsaasPaymentId())
                        .build())
                .collect(Collectors.toList());

        return ContratoDTO.builder()
                .id(contrato.getId())
                .titulo(contrato.getTitulo())
                .descricao(contrato.getDescricao())
                .conteudo(contrato.getConteudo())
                .cliente(clienteDTO)
                .valorContrato(contrato.getValorContrato())
                .valorRecorrencia(contrato.getValorRecorrencia())
                .dataVencimento(contrato.getDataVencimento())
                .status(contrato.getStatus())
                .tipoPagamento(contrato.getTipoPagamento())
                .servico(contrato.getServico())
                .inicioContrato(contrato.getInicioContrato())
                .inicioRecorrencia(contrato.getInicioRecorrencia())
                .whatsapp(contrato.getWhatsapp())
                .asaasSubscriptionId(contrato.getAsaasSubscriptionId())
                .cobrancas(cobrancasDTO)
                .categoria(calcularCategoria(contrato))
                .dataCriacao(contrato.getDataCriacao())
                .dataAtualizacao(contrato.getDataAtualizacao())
                .build();
    }
    
    /**
     * Conta quantas parcelas (cobranças) estão em atraso para um contrato.
     * Parcela em atraso = OVERDUE, DUNNING_REQUESTED, CHARGEBACK_REQUESTED
     * ou PENDING com data de vencimento no passado.
     */
    private long contarParcelasEmAtraso(Contrato contrato) {
        LocalDate hoje = LocalDate.now();
        if (contrato.getCobrancas() == null || contrato.getCobrancas().isEmpty()) {
            return 0;
        }
        return contrato.getCobrancas().stream().filter(cob -> 
            cob.getStatus() == Cobranca.StatusCobranca.OVERDUE || 
            cob.getStatus() == Cobranca.StatusCobranca.DUNNING_REQUESTED ||
            cob.getStatus() == Cobranca.StatusCobranca.CHARGEBACK_REQUESTED ||
            (cob.getStatus() == Cobranca.StatusCobranca.PENDING && 
             cob.getDataVencimento() != null && cob.getDataVencimento().isBefore(hoje))
        ).count();
    }
    
    /**
     * Calcula a categoria do contrato (mutuamente exclusivo)
     * Prioridade: INADIMPLENTE (2+ parcelas em atraso) > EM_ATRASO (1 parcela em atraso) > EM_DIA > PENDENTE
     */
    private ContratoDTO.CategoriaContrato calcularCategoria(Contrato contrato) {
        LocalDate hoje = LocalDate.now();
        
        // Contar parcelas em atraso
        long parcelasEmAtraso = contarParcelasEmAtraso(contrato);
        
        // INADIMPLENTE = 2 ou mais parcelas em atraso
        if (parcelasEmAtraso >= 2) {
            return ContratoDTO.CategoriaContrato.INADIMPLENTE;
        }
        
        // EM_ATRASO = exatamente 1 parcela em atraso
        if (parcelasEmAtraso == 1) {
            return ContratoDTO.CategoriaContrato.EM_ATRASO;
        }
        
        // Se o status é VENCIDO mas não tem cobranças em atraso (caso sem cobranças),
        // considerar como EM_ATRASO (1 atraso genérico do contrato)
        if (contrato.getStatus() == Contrato.StatusContrato.VENCIDO) {
            return ContratoDTO.CategoriaContrato.EM_ATRASO;
        }
        
        // Contrato em dia com data vencida (sem cobranças em atraso)
        if (contrato.getStatus() == Contrato.StatusContrato.EM_DIA) {
            if (contrato.getDataVencimento() != null && contrato.getDataVencimento().isBefore(hoje)) {
                return ContratoDTO.CategoriaContrato.EM_ATRASO;
            }
        }
        
        // PRIORIDADE 2: Em Dia
        if (contrato.getStatus() == Contrato.StatusContrato.PAGO) {
            return ContratoDTO.CategoriaContrato.EM_DIA;
        }
        
        if (contrato.getCobrancas() != null && !contrato.getCobrancas().isEmpty()) {
            // Todas pagas
            boolean todasPagas = contrato.getCobrancas().stream().allMatch(cob -> 
                cob.getStatus() == Cobranca.StatusCobranca.RECEIVED || 
                cob.getStatus() == Cobranca.StatusCobranca.RECEIVED_IN_CASH_UNDONE || 
                cob.getStatus() == Cobranca.StatusCobranca.DUNNING_RECEIVED
            );
            if (todasPagas) {
                return ContratoDTO.CategoriaContrato.EM_DIA;
            }
            
            // Pelo menos uma paga
            boolean temPaga = contrato.getCobrancas().stream().anyMatch(cob -> 
                cob.getStatus() == Cobranca.StatusCobranca.RECEIVED || 
                cob.getStatus() == Cobranca.StatusCobranca.RECEIVED_IN_CASH_UNDONE || 
                cob.getStatus() == Cobranca.StatusCobranca.DUNNING_RECEIVED
            );
            if (temPaga) {
                return ContratoDTO.CategoriaContrato.EM_DIA;
            }
            
            // Todas PENDING e data futura
            boolean todasPendentes = contrato.getCobrancas().stream()
                .allMatch(cob -> cob.getStatus() == Cobranca.StatusCobranca.PENDING);
            if (todasPendentes && contrato.getDataVencimento() != null && 
                !contrato.getDataVencimento().isBefore(hoje)) {
                return ContratoDTO.CategoriaContrato.EM_DIA;
            }
        }
        
        if (contrato.getStatus() == Contrato.StatusContrato.EM_DIA) {
            if (contrato.getDataVencimento() != null && !contrato.getDataVencimento().isBefore(hoje)) {
                return ContratoDTO.CategoriaContrato.EM_DIA;
            }
        }
        
        // PRIORIDADE 4: Pendentes (padrão)
        return ContratoDTO.CategoriaContrato.PENDENTE;
    }
    
    /**
     * Retorna totais por categoria de contratos
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getTotaisPorCategoria() {
        // Buscar todos os contratos não deletados (sem paginação)
        List<Contrato> todosContratos = contratoRepository.findAllNaoDeletados();
        
        long totalContratos = todosContratos.size();
        BigDecimal totalValor = todosContratos.stream()
            .map(Contrato::getValorContrato)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        long emDia = 0;
        long pendente = 0;
        long emAtraso = 0;
        long inadimplente = 0;
        
        BigDecimal valorEmDia = BigDecimal.ZERO;
        BigDecimal valorPendente = BigDecimal.ZERO;
        BigDecimal valorEmAtraso = BigDecimal.ZERO;
        BigDecimal valorInadimplente = BigDecimal.ZERO;
        
        for (Contrato contrato : todosContratos) {
            // Recalcular status antes de calcular categoria
            recalcularEAtualizarStatus(contrato, false);
            ContratoDTO.CategoriaContrato categoria = calcularCategoria(contrato);
            
            BigDecimal valorContrato = contrato.getValorContrato() != null ? contrato.getValorContrato() : BigDecimal.ZERO;
            
            switch (categoria) {
                case EM_DIA:
                    emDia++;
                    valorEmDia = valorEmDia.add(valorContrato);
                    break;
                case PENDENTE:
                    pendente++;
                    valorPendente = valorPendente.add(valorContrato);
                    break;
                case EM_ATRASO:
                    emAtraso++;
                    valorEmAtraso = valorEmAtraso.add(valorContrato);
                    break;
                case INADIMPLENTE:
                    inadimplente++;
                    valorInadimplente = valorInadimplente.add(valorContrato);
                    break;
            }
        }
        
        Map<String, Object> totais = new java.util.HashMap<>();
        totais.put("totalContratos", totalContratos);
        totais.put("totalValor", totalValor);
        totais.put("emDia", emDia);
        totais.put("pendente", pendente);
        totais.put("emAtraso", emAtraso);
        totais.put("inadimplente", inadimplente);
        totais.put("valorEmDia", valorEmDia);
        totais.put("valorPendente", valorPendente);
        totais.put("valorEmAtraso", valorEmAtraso);
        totais.put("valorInadimplente", valorInadimplente);
        
        return totais;
    }

    /**
     * Re-sincroniza TODOS os contratos com o Asaas.
     * Consulta cada cobrança na API do Asaas e atualiza o status local.
     * Corrige cobranças que foram marcadas incorretamente (ex: CONFIRMED → RECEIVED).
     */
    @Transactional
    public Map<String, Object> sincronizarTodosComAsaas() {
        log.info("=== INICIANDO SINCRONIZAÇÃO TOTAL COM ASAAS ===");
        
        List<Contrato> todosContratos = contratoRepository.findAllNaoDeletados();
        int totalContratos = todosContratos.size();
        int contratosAtualizados = 0;
        int cobrancasAtualizadas = 0;
        int erros = 0;
        
        for (Contrato contrato : todosContratos) {
            try {
                // Forçar carregamento das cobranças
                if (contrato.getCobrancas() != null) {
                    contrato.getCobrancas().size();
                }
                
                boolean contratoAtualizado = false;
                
                if (contrato.getCobrancas() != null && !contrato.getCobrancas().isEmpty()) {
                    for (Cobranca cobranca : contrato.getCobrancas()) {
                        if (cobranca.getAsaasPaymentId() != null && !cobranca.getAsaasPaymentId().isEmpty()) {
                            try {
                                Map<String, Object> paymentData = asaasService.consultarCobranca(cobranca.getAsaasPaymentId());
                                
                                if (paymentData == null) {
                                    log.debug("Cobrança {} não encontrada no Asaas (404). Mantendo status local.", cobranca.getAsaasPaymentId());
                                    continue;
                                }
                                
                                String statusAsaas = (String) paymentData.get("status");
                                Cobranca.StatusCobranca novoStatus = mapearStatusCobranca(statusAsaas);
                                
                                if (cobranca.getStatus() != novoStatus) {
                                    log.info("Cobrança {} (Asaas: {}): {} → {} (Asaas retornou: '{}')", 
                                            cobranca.getId(), cobranca.getAsaasPaymentId(), 
                                            cobranca.getStatus(), novoStatus, statusAsaas);
                                    cobranca.setStatus(novoStatus);
                                    contratoAtualizado = true;
                                    cobrancasAtualizadas++;
                                }
                                
                                // Atualizar data de pagamento se foi pago
                                if (cobranca.isPaga() && cobranca.getDataPagamento() == null) {
                                    Object paymentDate = paymentData.get("paymentDate");
                                    if (paymentDate != null) {
                                        String dateStr = paymentDate.toString();
                                        if (dateStr.length() >= 10) {
                                            cobranca.setDataPagamento(LocalDate.parse(dateStr.substring(0, 10)));
                                            contratoAtualizado = true;
                                        }
                                    }
                                }
                                
                                // Atualizar link de pagamento se não tiver
                                if (cobranca.getLinkPagamento() == null || cobranca.getLinkPagamento().isEmpty()) {
                                    String invoiceUrl = (String) paymentData.get("invoiceUrl");
                                    if (invoiceUrl != null && !invoiceUrl.isEmpty()) {
                                        cobranca.setLinkPagamento(invoiceUrl);
                                        contratoAtualizado = true;
                                    }
                                }
                                
                                cobrancaRepository.save(cobranca);
                            } catch (Exception e) {
                                log.warn("Erro ao sincronizar cobrança {} com Asaas: {}", cobranca.getId(), e.getMessage());
                                erros++;
                            }
                        }
                    }
                }
                
                if (contratoAtualizado) {
                    contrato.calcularStatusBaseadoNasCobrancas();
                    contratoRepository.save(contrato);
                    contratosAtualizados++;
                }
            } catch (Exception e) {
                log.error("Erro ao sincronizar contrato {}: {}", contrato.getId(), e.getMessage());
                erros++;
            }
        }
        
        log.info("=== SINCRONIZAÇÃO TOTAL CONCLUÍDA ===");
        log.info("Contratos processados: {}, atualizados: {}", totalContratos, contratosAtualizados);
        log.info("Cobranças atualizadas: {}, erros: {}", cobrancasAtualizadas, erros);
        
        Map<String, Object> resultado = new java.util.HashMap<>();
        resultado.put("totalContratos", totalContratos);
        resultado.put("contratosAtualizados", contratosAtualizados);
        resultado.put("cobrancasAtualizadas", cobrancasAtualizadas);
        resultado.put("erros", erros);
        resultado.put("mensagem", String.format(
            "Sincronização concluída: %d contratos processados, %d atualizados, %d cobranças corrigidas, %d erros",
            totalContratos, contratosAtualizados, cobrancasAtualizadas, erros));
        
        return resultado;
    }

    /**
     * Importa contratos do Asaas para o banco de dados
     * Busca assinaturas e cobranças do Asaas e cria/atualiza no banco
     * Não usa transação única para permitir que erros individuais não revertam tudo
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public int importarContratosDoAsaas() {
        if (asaasService.isMockEnabled()) {
            log.warn("Modo mock ativado. Não é possível importar contratos do Asaas.");
            return 0;
        }

        int contratosImportados = 0;
        int assinaturasProcessadas = 0;
        int cobrancasProcessadas = 0;
        int erros = 0;

        try {
            log.info("=== INICIANDO IMPORTAÇÃO DE CONTRATOS DO ASAAS ===");
            
            // Buscar assinaturas do Asaas
            java.util.List<Map<String, Object>> assinaturas = asaasService.listarAssinaturas();
            log.info("Encontradas {} assinaturas no Asaas para importar", assinaturas.size());

            for (Map<String, Object> assinatura : assinaturas) {
                assinaturasProcessadas++;
                try {
                    String subscriptionId = (String) assinatura.get("id");
                    String customerId = (String) assinatura.get("customer");
                    
                    log.debug("Processando assinatura {}: subscriptionId={}, customerId={}", 
                            assinaturasProcessadas, subscriptionId, customerId);
                    
                    if (subscriptionId == null || subscriptionId.isEmpty()) {
                        log.warn("Assinatura sem ID, pulando...");
                        continue;
                    }
                    
                    if (customerId == null || customerId.isEmpty()) {
                        log.warn("Assinatura {} sem customerId, pulando...", subscriptionId);
                        continue;
                    }
                    
                    // Verificar se já existe contrato com essa assinatura
                    Optional<Contrato> contratoExistente = contratoRepository.findByAsaasSubscriptionId(subscriptionId);
                    if (contratoExistente.isPresent()) {
                        log.debug("Contrato já existe para subscriptionId: {} (ID: {})", 
                                subscriptionId, contratoExistente.get().getId());
                        continue;
                    }

                    // Buscar cliente no Asaas
                    log.debug("Buscando cliente no Asaas: {}", customerId);
                    Map<String, Object> clienteAsaas = asaasService.buscarClientePorId(customerId);
                    if (clienteAsaas == null || clienteAsaas.isEmpty()) {
                        log.warn("Cliente não encontrado no Asaas: {}", customerId);
                        erros++;
                        continue;
                    }
                    
                    log.debug("Cliente encontrado no Asaas: {}", clienteAsaas.get("name"));

                    // Buscar ou criar cliente no banco
                    String cpfCnpj = (String) clienteAsaas.get("cpfCnpj");
                    Cliente cliente = null;
                    if (cpfCnpj != null && !cpfCnpj.isEmpty()) {
                        Optional<Cliente> clienteOpt = clienteRepository.findByCpfCnpj(cpfCnpj);
                        if (clienteOpt.isPresent()) {
                            cliente = clienteOpt.get();
                            // Atualizar ID do Asaas se não tiver
                            if (cliente.getAsaasCustomerId() == null) {
                                cliente.setAsaasCustomerId(customerId);
                                clienteRepository.save(cliente);
                            }
                        }
                    }

                    if (cliente == null) {
                        // Criar novo cliente em transação separada
                        cliente = criarClienteImportado(clienteAsaas, customerId, cpfCnpj);
                    }

                    // Criar contrato em transação separada
                    Contrato contrato = criarContratoRecorrenteImportado(assinatura, cliente, subscriptionId);
                    if (contrato != null) {
                        contratosImportados++;
                        log.info("✓ Contrato importado com sucesso: ID={}, SubscriptionId={}, Cliente={}", 
                                contrato.getId(), subscriptionId, cliente.getRazaoSocial());
                    }
                } catch (Exception e) {
                    erros++;
                    log.error("✗ Erro ao importar assinatura {}: {}", assinatura.get("id"), e.getMessage(), e);
                }
            }
            
            log.info("Assinaturas processadas: {} importadas, {} erros, {} já existiam", 
                    contratosImportados, erros, assinaturasProcessadas - contratosImportados - erros);

            // Buscar cobranças únicas (payments) do Asaas
            // IMPORTANTE: Pular cobranças que pertencem a uma assinatura (já foram importadas no passo anterior)
            java.util.List<Map<String, Object>> cobrancas = asaasService.listarCobrancas();
            log.info("Encontradas {} cobranças no Asaas para importar", cobrancas.size());

            int cobrancasPuladas = 0;
            for (Map<String, Object> cobrancaAsaas : cobrancas) {
                cobrancasProcessadas++;
                try {
                    String paymentId = (String) cobrancaAsaas.get("id");
                    String customerId = (String) cobrancaAsaas.get("customer");
                    
                    // PULAR cobranças que pertencem a uma assinatura
                    // Essas já foram importadas como parcelas do contrato recorrente
                    String subscriptionId = (String) cobrancaAsaas.get("subscription");
                    if (subscriptionId != null && !subscriptionId.isEmpty()) {
                        cobrancasPuladas++;
                        log.debug("Cobrança {} pertence à assinatura {}, pulando (já importada como parcela).", 
                                paymentId, subscriptionId);
                        continue;
                    }
                    
                    log.debug("Processando cobrança avulsa {}: paymentId={}, customerId={}", 
                            cobrancasProcessadas, paymentId, customerId);
                    
                    if (paymentId == null || paymentId.isEmpty()) {
                        log.warn("Cobrança sem ID, pulando...");
                        continue;
                    }
                    
                    if (customerId == null || customerId.isEmpty()) {
                        log.warn("Cobrança {} sem customerId, pulando...", paymentId);
                        continue;
                    }
                    
                    // Verificar se já existe cobrança com esse paymentId
                    Optional<Cobranca> cobrancaExistente = cobrancaRepository.findByAsaasPaymentId(paymentId);
                    if (cobrancaExistente.isPresent()) {
                        log.debug("Cobrança já existe para paymentId: {} (ID: {})", 
                                paymentId, cobrancaExistente.get().getId());
                        continue;
                    }

                    // Buscar cliente
                    Map<String, Object> clienteAsaas = asaasService.buscarClientePorId(customerId);
                    if (clienteAsaas.isEmpty()) {
                        continue;
                    }

                    String cpfCnpj = (String) clienteAsaas.get("cpfCnpj");
                    Cliente cliente = null;
                    if (cpfCnpj != null && !cpfCnpj.isEmpty()) {
                        Optional<Cliente> clienteOpt = clienteRepository.findByCpfCnpj(cpfCnpj);
                        if (clienteOpt.isPresent()) {
                            cliente = clienteOpt.get();
                        }
                    }

                    if (cliente == null) {
                        // Criar cliente se não existir em transação separada
                        cliente = criarClienteImportado(clienteAsaas, customerId, cpfCnpj);
                    }

                    // Criar contrato e cobrança em transação separada (apenas para cobranças AVULSAS)
                    boolean sucesso = criarContratoUnicoImportado(cobrancaAsaas, cliente, paymentId);
                    if (sucesso) {
                        contratosImportados++;
                    }
                } catch (Exception e) {
                    erros++;
                    log.error("✗ Erro ao importar cobrança {}: {}", cobrancaAsaas.get("id"), e.getMessage(), e);
                }
            }
            
            log.info("Cobranças avulsas processadas. {} puladas (pertenciam a assinaturas)", cobrancasPuladas);

            log.info("=== IMPORTAÇÃO CONCLUÍDA ===");
            log.info("Total de contratos importados: {}", contratosImportados);
            log.info("Assinaturas processadas: {}", assinaturasProcessadas);
            log.info("Cobranças processadas: {}", cobrancasProcessadas);
            log.info("Erros encontrados: {}", erros);
            
            return contratosImportados;
        } catch (Exception e) {
            log.error("Erro ao importar contratos do Asaas", e);
            throw new RuntimeException("Erro ao importar contratos do Asaas: " + e.getMessage(), e);
        }
    }

    /**
     * Cria cliente importado em transação separada
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    private Cliente criarClienteImportado(Map<String, Object> clienteAsaas, String customerId, String cpfCnpj) {
        Cliente cliente = Cliente.builder()
                .razaoSocial((String) clienteAsaas.get("name"))
                .cpfCnpj(cpfCnpj)
                .emailFinanceiro((String) clienteAsaas.get("email"))
                .asaasCustomerId(customerId)
                .build();
        return clienteRepository.save(cliente);
    }

    /**
     * Cria contrato recorrente importado em transação separada.
     * Busca TODAS as cobranças (payments) da assinatura no Asaas e as cria como Cobranca vinculadas ao contrato.
     * Isso garante que todas as parcelas fiquem agrupadas sob um único contrato.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    private Contrato criarContratoRecorrenteImportado(Map<String, Object> assinatura, Cliente cliente, String subscriptionId) {
        try {
            Object valorObj = assinatura.get("value");
            BigDecimal valorParcela = valorObj != null ? new BigDecimal(valorObj.toString()) : BigDecimal.ZERO;
            
            // Garantir que valor seja pelo menos 0.01 para evitar problemas com valores zero
            if (valorParcela.compareTo(BigDecimal.ZERO) <= 0) {
                valorParcela = BigDecimal.valueOf(0.01);
            }
            
            Object nextDueDateObj = assinatura.get("nextDueDate");
            LocalDate dataVencimento = null;
            if (nextDueDateObj != null) {
                String dateStr = nextDueDateObj.toString();
                if (dateStr.length() >= 10) {
                    dataVencimento = LocalDate.parse(dateStr.substring(0, 10));
                }
            }

            // Obter título da assinatura ou usar um padrão
            String titulo = (String) assinatura.get("description");
            if (titulo == null || titulo.trim().isEmpty()) {
                titulo = "Contrato Recorrente - " + cliente.getRazaoSocial();
            }

            // Buscar todas as cobranças dessa assinatura no Asaas
            java.util.List<Map<String, Object>> cobrancasAsaas = asaasService.listarCobrancasPorAssinatura(subscriptionId);
            log.info("Assinatura {} possui {} cobranças no Asaas", subscriptionId, cobrancasAsaas.size());

            // Calcular valor total do contrato (valor da parcela × número de parcelas)
            int totalParcelas = cobrancasAsaas.isEmpty() ? 1 : cobrancasAsaas.size();
            BigDecimal valorTotal = valorParcela.multiply(BigDecimal.valueOf(totalParcelas));

            // Determinar a data de vencimento: a próxima cobrança pendente/overdue, ou nextDueDate
            LocalDate dataVencimentoFinal = dataVencimento;
            if (dataVencimentoFinal == null && !cobrancasAsaas.isEmpty()) {
                // Usar a data da primeira cobrança
                Object firstDueDate = cobrancasAsaas.get(0).get("dueDate");
                if (firstDueDate != null) {
                    String dateStr = firstDueDate.toString();
                    if (dateStr.length() >= 10) {
                        dataVencimentoFinal = LocalDate.parse(dateStr.substring(0, 10));
                    }
                }
            }

            Contrato contrato = Contrato.builder()
                    .titulo(titulo)
                    .descricao("Contrato importado do Asaas - Parcelado em " + totalParcelas + "x de R$ " + valorParcela)
                    .cliente(cliente)
                    .valorContrato(valorTotal)
                    .valorRecorrencia(valorParcela)
                    .dataVencimento(dataVencimentoFinal != null ? dataVencimentoFinal : LocalDate.now().plusMonths(1))
                    .status(Contrato.StatusContrato.PENDENTE)
                    .tipoPagamento(Contrato.TipoPagamento.RECORRENTE)
                    .asaasSubscriptionId(subscriptionId)
                    .build();

            contrato = contratoRepository.save(contrato);

            // Criar cobranças (parcelas) vinculadas ao contrato
            int parcelaIndex = 0;
            for (Map<String, Object> cobrancaAsaas : cobrancasAsaas) {
                parcelaIndex++;
                try {
                    String paymentId = (String) cobrancaAsaas.get("id");
                    
                    // Verificar se essa cobrança já existe no banco
                    Optional<Cobranca> cobrancaExistente = cobrancaRepository.findByAsaasPaymentId(paymentId);
                    if (cobrancaExistente.isPresent()) {
                        log.debug("Cobrança {} já existe, pulando...", paymentId);
                        continue;
                    }
                    
                    Object cobValorObj = cobrancaAsaas.get("value");
                    BigDecimal cobValor = cobValorObj != null ? new BigDecimal(cobValorObj.toString()) : valorParcela;
                    
                    Object cobDueDateObj = cobrancaAsaas.get("dueDate");
                    LocalDate cobDataVencimento = null;
                    if (cobDueDateObj != null) {
                        String dateStr = cobDueDateObj.toString();
                        if (dateStr.length() >= 10) {
                            cobDataVencimento = LocalDate.parse(dateStr.substring(0, 10));
                        }
                    }
                    
                    // Data de pagamento (se paga)
                    Object cobPaymentDateObj = cobrancaAsaas.get("paymentDate");
                    LocalDate cobDataPagamento = null;
                    if (cobPaymentDateObj != null) {
                        String dateStr = cobPaymentDateObj.toString();
                        if (dateStr.length() >= 10) {
                            cobDataPagamento = LocalDate.parse(dateStr.substring(0, 10));
                        }
                    }
                    
                    String statusStr = (String) cobrancaAsaas.get("status");
                    Cobranca.StatusCobranca status = mapearStatusCobranca(statusStr);
                    
                    // Tentar obter o número da parcela do Asaas (installmentNumber)
                    Object installmentObj = cobrancaAsaas.get("installmentNumber");
                    Integer numeroParcela = null;
                    if (installmentObj != null) {
                        numeroParcela = Integer.valueOf(installmentObj.toString());
                    } else {
                        numeroParcela = parcelaIndex;
                    }
                    
                    Cobranca cobranca = Cobranca.builder()
                            .contrato(contrato)
                            .valor(cobValor)
                            .dataVencimento(cobDataVencimento != null ? cobDataVencimento : contrato.getDataVencimento())
                            .dataPagamento(cobDataPagamento)
                            .status(status)
                            .asaasPaymentId(paymentId)
                            .linkPagamento((String) cobrancaAsaas.get("invoiceUrl"))
                            .codigoBarras((String) cobrancaAsaas.get("nossoNumero"))
                            .numeroParcela(numeroParcela)
                            .build();
                    
                    contrato.adicionarCobranca(cobranca);
                    log.debug("Parcela {}/{} adicionada: paymentId={}, status={}, valor={}", 
                            numeroParcela, totalParcelas, paymentId, statusStr, cobValor);
                } catch (Exception e) {
                    log.warn("Erro ao criar cobrança da assinatura {}: {}", subscriptionId, e.getMessage());
                }
            }
            
            // Recalcular status do contrato baseado nas cobranças importadas
            contrato.calcularStatusBaseadoNasCobrancas();
            contrato = contratoRepository.save(contrato);
            
            log.info("✓ Contrato recorrente importado: ID={}, {} parcelas, valorTotal={}, status={}", 
                    contrato.getId(), contrato.getCobrancas().size(), valorTotal, contrato.getStatus());

            return contrato;
        } catch (Exception e) {
            log.error("Erro ao criar contrato recorrente importado: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Cria contrato único e cobrança importados em transação separada.
     * Apenas para cobranças avulsas (que não pertencem a uma assinatura).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    private boolean criarContratoUnicoImportado(Map<String, Object> cobrancaAsaas, Cliente cliente, String paymentId) {
        try {
            Object valorObj = cobrancaAsaas.get("value");
            BigDecimal valor = valorObj != null ? new BigDecimal(valorObj.toString()) : BigDecimal.ZERO;
            
            Object dueDateObj = cobrancaAsaas.get("dueDate");
            LocalDate dataVencimento = null;
            if (dueDateObj != null) {
                String dateStr = dueDateObj.toString();
                if (dateStr.length() >= 10) {
                    dataVencimento = LocalDate.parse(dateStr.substring(0, 10));
                }
            }
            
            // Data de pagamento (se paga)
            Object paymentDateObj = cobrancaAsaas.get("paymentDate");
            LocalDate dataPagamento = null;
            if (paymentDateObj != null) {
                String dateStr = paymentDateObj.toString();
                if (dateStr.length() >= 10) {
                    dataPagamento = LocalDate.parse(dateStr.substring(0, 10));
                }
            }

            String tituloCobranca = (String) cobrancaAsaas.get("description");
            if (tituloCobranca == null || tituloCobranca.trim().isEmpty()) {
                tituloCobranca = "Cobrança Avulsa - " + cliente.getRazaoSocial();
            }

            Contrato contrato = Contrato.builder()
                    .titulo(tituloCobranca)
                    .descricao("Cobrança avulsa importada do Asaas")
                    .cliente(cliente)
                    .valorContrato(valor)
                    .dataVencimento(dataVencimento != null ? dataVencimento : LocalDate.now().plusDays(30))
                    .status(Contrato.StatusContrato.PENDENTE)
                    .tipoPagamento(Contrato.TipoPagamento.UNICO)
                    .build();

            contrato = contratoRepository.save(contrato);

            // Criar cobrança
            String statusStr = (String) cobrancaAsaas.get("status");
            Cobranca.StatusCobranca status = mapearStatusCobranca(statusStr);

            Cobranca cobranca = Cobranca.builder()
                    .contrato(contrato)
                    .valor(valor)
                    .dataVencimento(dataVencimento)
                    .dataPagamento(dataPagamento)
                    .status(status)
                    .asaasPaymentId(paymentId)
                    .linkPagamento((String) cobrancaAsaas.get("invoiceUrl"))
                    .codigoBarras((String) cobrancaAsaas.get("nossoNumero"))
                    .numeroParcela(1)
                    .build();

            contrato.adicionarCobranca(cobranca);
            // Recalcular status baseado nas cobranças
            contrato.calcularStatusBaseadoNasCobrancas();
            contratoRepository.save(contrato);
            
            log.info("✓ Cobrança avulsa importada: ID={}, PaymentId={}, Status={}, Cliente={}", 
                    cobranca.getId(), paymentId, statusStr, cliente.getRazaoSocial());
            return true;
        } catch (Exception e) {
            log.error("Erro ao criar contrato único importado: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Limpa todos os contratos do banco de dados (hard delete)
     * ATENÇÃO: Esta operação é irreversível!
     * Deleta também todas as cobranças associadas (cascade)
     */
    @Transactional
    public int limparTodosContratos() {
        log.warn("⚠️ INICIANDO LIMPEZA COMPLETA DE CONTRATOS - OPERAÇÃO IRREVERSÍVEL!");
        
        // Conta antes de deletar
        long totalContratos = contratoRepository.count();
        long totalCobrancas = cobrancaRepository.count();
        
        log.info("📊 Total de contratos a serem deletados: {}", totalContratos);
        log.info("📊 Total de cobranças a serem deletadas: {}", totalCobrancas);
        
        // Deleta todas as cobranças primeiro (para evitar problemas de FK)
        // Mas como temos cascade, podemos deletar direto os contratos
        // Vamos deletar as cobranças primeiro por segurança
        cobrancaRepository.deleteAll();
        log.info("✓ Todas as cobranças foram deletadas");
        
        // Deleta todos os contratos
        contratoRepository.deleteAll();
        log.info("✓ Todos os contratos foram deletados");
        
        log.warn("⚠️ LIMPEZA COMPLETA CONCLUÍDA: {} contratos e {} cobranças removidos", 
                totalContratos, totalCobrancas);
        
        return (int) totalContratos;
    }
}

