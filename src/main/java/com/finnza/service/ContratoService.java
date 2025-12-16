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
     */
    @Transactional
    public ContratoDTO buscarPorId(Long id) {
        Contrato contrato = contratoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Contrato não encontrado"));

        if (contrato.getDeleted()) {
            throw new RuntimeException("Contrato não encontrado");
        }

        // Recalcular status automaticamente antes de retornar
        recalcularEAtualizarStatus(contrato);
        
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
     * Busca contratos com filtros
     */
    @Transactional
    public Page<ContratoDTO> buscarComFiltros(Long clienteId, Contrato.StatusContrato status, String termo, Pageable pageable) {
        // Normalizar termo para evitar problemas com null
        String termoNormalizado = (termo != null && !termo.trim().isEmpty()) ? termo.trim() : null;
        // Converter status enum para String para a query nativa
        String statusStr = status != null ? status.name() : null;
        return contratoRepository.buscarComFiltros(clienteId, statusStr, termoNormalizado, pageable)
                .map(contrato -> {
                    recalcularEAtualizarStatus(contrato);
                    return toDTO(contrato);
                });
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
                        log.error("Erro ao sincronizar cobrança {} com Asaas", cobranca.getId(), e);
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
     * Mapeia status do Asaas para enum de cobrança
     */
    private Cobranca.StatusCobranca mapearStatusCobranca(String statusAsaas) {
        if (statusAsaas == null) {
            return Cobranca.StatusCobranca.PENDING;
        }

        try {
            return Cobranca.StatusCobranca.valueOf(statusAsaas);
        } catch (IllegalArgumentException e) {
            log.warn("Status desconhecido do Asaas: {}", statusAsaas);
            return Cobranca.StatusCobranca.PENDING;
        }
    }

    /**
     * Recalcula e atualiza o status do contrato se necessário
     * Sincroniza com Asaas antes de recalcular para garantir dados atualizados
     * Salva apenas se o status mudou para evitar writes desnecessários
     */
    private void recalcularEAtualizarStatus(Contrato contrato) {
        // Garantir que as cobranças sejam carregadas (forçar inicialização do lazy)
        if (contrato.getCobrancas() != null) {
            contrato.getCobrancas().size(); // Força o carregamento do lazy collection
        }
        
        // Sincronizar cobranças com Asaas (apenas se não estiver em modo mock)
        if (!asaasService.isMockEnabled() && contrato.getCobrancas() != null && !contrato.getCobrancas().isEmpty()) {
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
                .dataCriacao(contrato.getDataCriacao())
                .dataAtualizacao(contrato.getDataAtualizacao())
                .build();
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
            java.util.List<Map<String, Object>> cobrancas = asaasService.listarCobrancas();
            log.info("Encontradas {} cobranças no Asaas para importar", cobrancas.size());

            for (Map<String, Object> cobrancaAsaas : cobrancas) {
                cobrancasProcessadas++;
                try {
                    String paymentId = (String) cobrancaAsaas.get("id");
                    String customerId = (String) cobrancaAsaas.get("customer");
                    
                    log.debug("Processando cobrança {}: paymentId={}, customerId={}", 
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

                    // Criar contrato e cobrança em transação separada
                    boolean sucesso = criarContratoUnicoImportado(cobrancaAsaas, cliente, paymentId);
                    if (sucesso) {
                        contratosImportados++;
                    }
                } catch (Exception e) {
                    erros++;
                    log.error("✗ Erro ao importar cobrança {}: {}", cobrancaAsaas.get("id"), e.getMessage(), e);
                }
            }

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
     * Cria contrato recorrente importado em transação separada
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    private Contrato criarContratoRecorrenteImportado(Map<String, Object> assinatura, Cliente cliente, String subscriptionId) {
        try {
            Object valorObj = assinatura.get("value");
            BigDecimal valor = valorObj != null ? new BigDecimal(valorObj.toString()) : BigDecimal.ZERO;
            
            Object nextDueDateObj = assinatura.get("nextDueDate");
            LocalDate dataVencimento = null;
            if (nextDueDateObj != null) {
                String dateStr = nextDueDateObj.toString();
                if (dateStr.length() >= 10) {
                    dataVencimento = LocalDate.parse(dateStr.substring(0, 10));
                }
            }

            Contrato contrato = Contrato.builder()
                    .titulo((String) assinatura.get("description"))
                    .descricao("Contrato importado do Asaas")
                    .cliente(cliente)
                    .valorRecorrencia(valor)
                    .dataVencimento(dataVencimento != null ? dataVencimento : LocalDate.now().plusMonths(1))
                    .status(Contrato.StatusContrato.PENDENTE)
                    .tipoPagamento(Contrato.TipoPagamento.RECORRENTE)
                    .asaasSubscriptionId(subscriptionId)
                    .build();

            return contratoRepository.save(contrato);
        } catch (Exception e) {
            log.error("Erro ao criar contrato recorrente importado: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Cria contrato único e cobrança importados em transação separada
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

            Contrato contrato = Contrato.builder()
                    .titulo((String) cobrancaAsaas.get("description"))
                    .descricao("Contrato único importado do Asaas")
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
                    .status(status)
                    .asaasPaymentId(paymentId)
                    .linkPagamento((String) cobrancaAsaas.get("invoiceUrl"))
                    .codigoBarras((String) cobrancaAsaas.get("barcode"))
                    .build();

            cobrancaRepository.save(cobranca);
            
            log.info("✓ Cobrança única importada com sucesso: ID={}, PaymentId={}, Cliente={}", 
                    cobranca.getId(), paymentId, cliente.getRazaoSocial());
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

