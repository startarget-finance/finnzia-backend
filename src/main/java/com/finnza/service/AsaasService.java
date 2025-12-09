package com.finnza.service;

import com.finnza.domain.entity.Cliente;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service para integração com API do Asaas
 * Suporta modo mock para desenvolvimento sem conta Asaas
 */
@Slf4j
@Service
public class AsaasService {

    private final WebClient webClient;
    private final String apiKey;
    private final String baseUrl;
    private final boolean mockEnabled;

    public AsaasService(
            @Value("${asaas.api.key:}") String apiKey,
            @Value("${asaas.api.url:https://sandbox.asaas.com/api/v3}") String baseUrl,
            @Value("${asaas.mock.enabled:true}") boolean mockEnabled) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.mockEnabled = mockEnabled || apiKey == null || apiKey.isEmpty();

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

        if (this.mockEnabled) {
            log.info("AsaasService iniciado em modo MOCK - API real desabilitada");
        } else {
            log.info("AsaasService iniciado - Conectando ao Asaas: {}", baseUrl);
        }
    }

    /**
     * Busca cliente no Asaas por CPF/CNPJ
     */
    public String buscarClientePorCpfCnpj(String cpfCnpj) {
        if (mockEnabled) {
            return null;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/customers")
                            .queryParam("cpfCnpj", cpfCnpj)
                            .build())
                    .header("access_token", apiKey)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> data = (java.util.List<Map<String, Object>>) response.get("data");
            
            if (data != null && !data.isEmpty()) {
                String customerId = (String) data.get(0).get("id");
                log.info("Cliente encontrado no Asaas com ID: {} para CPF/CNPJ: {}", customerId, cpfCnpj);
                return customerId;
            }
            
            return null;
        } catch (Exception e) {
            log.warn("Erro ao buscar cliente no Asaas por CPF/CNPJ: {}", cpfCnpj, e);
            return null;
        }
    }

    /**
     * Cria um cliente no Asaas
     */
    public String criarCliente(Cliente cliente) {
        if (mockEnabled) {
            return criarClienteMock(cliente);
        }

        // Primeiro, tenta buscar se já existe
        if (cliente.getCpfCnpj() != null && !cliente.getCpfCnpj().isEmpty()) {
            String clienteExistente = buscarClientePorCpfCnpj(cliente.getCpfCnpj());
            if (clienteExistente != null) {
                log.info("Cliente já existe no Asaas com ID: {}", clienteExistente);
                return clienteExistente;
            }
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("name", cliente.getRazaoSocial());
        
        // Campos opcionais - só adiciona se não for null
        if (cliente.getEmailFinanceiro() != null && !cliente.getEmailFinanceiro().isEmpty()) {
            requestBody.put("email", cliente.getEmailFinanceiro());
        }
        // Telefone é opcional no Asaas - só envia se tiver formato válido
        // O Asaas pode rejeitar telefones em alguns formatos, então vamos tentar sem telefone primeiro
        // if (cliente.getCelularFinanceiro() != null && !cliente.getCelularFinanceiro().isEmpty()) {
        //     String telefoneFormatado = formatarTelefone(cliente.getCelularFinanceiro());
        //     requestBody.put("phone", telefoneFormatado);
        //     requestBody.put("mobilePhone", telefoneFormatado);
        // }
        if (cliente.getCpfCnpj() != null && !cliente.getCpfCnpj().isEmpty()) {
            requestBody.put("cpfCnpj", cliente.getCpfCnpj());
        }
        if (cliente.getCep() != null && !cliente.getCep().isEmpty()) {
            requestBody.put("postalCode", cliente.getCep());
        }
        if (cliente.getEnderecoCompleto() != null && !cliente.getEnderecoCompleto().isEmpty()) {
            requestBody.put("address", cliente.getEnderecoCompleto());
        }

        try {
            log.info("Criando cliente no Asaas com dados: {}", requestBody);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.post()
                    .uri("/customers")
                    .header("access_token", apiKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            String customerId = (String) response.get("id");
            log.info("Cliente criado no Asaas com ID: {}", customerId);
            return customerId;
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException.BadRequest e) {
            String errorBody = e.getResponseBodyAsString();
            log.error("=== ERRO 400 DO ASAAS ===");
            log.error("Request enviado: {}", requestBody);
            log.error("Response do Asaas: {}", errorBody);
            log.error("Status Code: {}", e.getStatusCode());
            log.error("=========================");
            // Não lança exceção, apenas loga - permite que o contrato seja criado mesmo sem Asaas
            return null;
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            String errorBody = e.getResponseBodyAsString();
            log.error("=== ERRO HTTP DO ASAAS ===");
            log.error("Request enviado: {}", requestBody);
            log.error("Response do Asaas: {}", errorBody);
            log.error("Status Code: {}", e.getStatusCode());
            log.error("=========================");
            // Não lança exceção, apenas loga - permite que o contrato seja criado mesmo sem Asaas
            return null;
        } catch (Exception e) {
            log.error("Erro inesperado ao criar cliente no Asaas", e);
            // Não lança exceção, apenas loga - permite que o contrato seja criado mesmo sem Asaas
            return null;
        }
    }

    /**
     * Cria uma cobrança única no Asaas
     */
    public Map<String, Object> criarCobrancaUnica(String customerId, BigDecimal valor, LocalDate dataVencimento, String descricao) {
        return criarCobrancaUnica(customerId, valor, dataVencimento, descricao, null, null, null, null, null, null, null);
    }

    /**
     * Cria uma cobrança única no Asaas com configurações avançadas
     */
    public Map<String, Object> criarCobrancaUnica(
            String customerId, 
            BigDecimal valor, 
            LocalDate dataVencimento, 
            String descricao,
            String formaPagamento,
            BigDecimal jurosAoMes,
            BigDecimal multaPorAtraso,
            BigDecimal descontoPercentual,
            BigDecimal descontoValorFixo,
            Integer prazoMaximoDesconto,
            Integer numeroParcelas) {
        if (mockEnabled) {
            return criarCobrancaMock(customerId, valor, dataVencimento, descricao);
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("customer", customerId);
        requestBody.put("billingType", formaPagamento != null && !formaPagamento.isEmpty() ? formaPagamento : "BOLETO");
        requestBody.put("value", valor);
        requestBody.put("dueDate", dataVencimento.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        requestBody.put("description", descricao);
        
        // Configurações de juros e multa
        if (jurosAoMes != null && jurosAoMes.compareTo(BigDecimal.ZERO) > 0) {
            requestBody.put("interest", jurosAoMes);
        }
        if (multaPorAtraso != null && multaPorAtraso.compareTo(BigDecimal.ZERO) > 0) {
            requestBody.put("fine", Map.of("value", multaPorAtraso, "type", "PERCENTAGE"));
        }
        
        // Configurações de desconto
        if (descontoPercentual != null && descontoPercentual.compareTo(BigDecimal.ZERO) > 0) {
            Map<String, Object> discount = new HashMap<>();
            discount.put("value", descontoPercentual);
            discount.put("type", "PERCENTAGE");
            if (prazoMaximoDesconto != null && prazoMaximoDesconto > 0) {
                discount.put("dueDateLimitDays", prazoMaximoDesconto);
            }
            requestBody.put("discount", discount);
        } else if (descontoValorFixo != null && descontoValorFixo.compareTo(BigDecimal.ZERO) > 0) {
            Map<String, Object> discount = new HashMap<>();
            discount.put("value", descontoValorFixo);
            discount.put("type", "FIXED");
            if (prazoMaximoDesconto != null && prazoMaximoDesconto > 0) {
                discount.put("dueDateLimitDays", prazoMaximoDesconto);
            }
            requestBody.put("discount", discount);
        }
        
        // Parcelas (se for mais de 1)
        if (numeroParcelas != null && numeroParcelas > 1) {
            requestBody.put("installmentCount", numeroParcelas);
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.post()
                    .uri("/payments")
                    .header("access_token", apiKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            log.info("Cobrança criada no Asaas com ID: {}", response.get("id"));
            return response;
        } catch (Exception e) {
            log.error("Erro ao criar cobrança no Asaas", e);
            throw new RuntimeException("Erro ao criar cobrança no Asaas: " + e.getMessage(), e);
        }
    }

    /**
     * Cria uma assinatura (cobrança recorrente) no Asaas
     */
    public Map<String, Object> criarAssinatura(String customerId, BigDecimal valor, LocalDate dataInicio, String descricao) {
        if (mockEnabled) {
            return criarAssinaturaMock(customerId, valor, dataInicio, descricao);
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("customer", customerId);
        requestBody.put("billingType", "BOLETO");
        requestBody.put("value", valor);
        requestBody.put("nextDueDate", dataInicio.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        requestBody.put("cycle", "MONTHLY"); // MONTHLY, WEEKLY, YEARLY
        requestBody.put("description", descricao);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.post()
                    .uri("/subscriptions")
                    .header("access_token", apiKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            log.info("Assinatura criada no Asaas com ID: {}", response.get("id"));
            return response;
        } catch (Exception e) {
            log.error("Erro ao criar assinatura no Asaas", e);
            throw new RuntimeException("Erro ao criar assinatura no Asaas: " + e.getMessage(), e);
        }
    }

    /**
     * Consulta status de uma cobrança no Asaas
     */
    public Map<String, Object> consultarCobranca(String paymentId) {
        if (mockEnabled) {
            return consultarCobrancaMock(paymentId);
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.get()
                    .uri("/payments/{id}", paymentId)
                    .header("access_token", apiKey)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            return response;
        } catch (Exception e) {
            log.error("Erro ao consultar cobrança no Asaas", e);
            throw new RuntimeException("Erro ao consultar cobrança no Asaas: " + e.getMessage(), e);
        }
    }

    // ========== MÉTODOS MOCK ==========

    private String criarClienteMock(Cliente cliente) {
        String mockId = "cus_" + UUID.randomUUID().toString().substring(0, 12);
        log.info("MOCK: Cliente criado no Asaas com ID: {} - Nome: {}", mockId, cliente.getRazaoSocial());
        return mockId;
    }

    private Map<String, Object> criarCobrancaMock(String customerId, BigDecimal valor, LocalDate dataVencimento, String descricao) {
        String mockId = "pay_" + UUID.randomUUID().toString().substring(0, 12);
        Map<String, Object> response = new HashMap<>();
        response.put("id", mockId);
        response.put("customer", customerId);
        response.put("value", valor);
        response.put("dueDate", dataVencimento.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        response.put("status", "PENDING");
        response.put("invoiceUrl", "https://sandbox.asaas.com/invoice/" + mockId);
        response.put("barcode", "34191.09008 01234.567890 12345.678901 2 12345678901234");
        log.info("MOCK: Cobrança criada no Asaas com ID: {} - Valor: {}", mockId, valor);
        return response;
    }

    private Map<String, Object> criarAssinaturaMock(String customerId, BigDecimal valor, LocalDate dataInicio, String descricao) {
        String mockId = "sub_" + UUID.randomUUID().toString().substring(0, 12);
        Map<String, Object> response = new HashMap<>();
        response.put("id", mockId);
        response.put("customer", customerId);
        response.put("value", valor);
        response.put("nextDueDate", dataInicio.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        response.put("cycle", "MONTHLY");
        response.put("status", "ACTIVE");
        log.info("MOCK: Assinatura criada no Asaas com ID: {} - Valor: {}", mockId, valor);
        return response;
    }

    private Map<String, Object> consultarCobrancaMock(String paymentId) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", paymentId);
        response.put("status", "PENDING");
        response.put("value", 100.00);
        log.info("MOCK: Consultando cobrança no Asaas: {}", paymentId);
        return response;
    }

    /**
     * Formata telefone para o padrão do Asaas
     * O Asaas aceita apenas números (sem formatação)
     */
    private String formatarTelefone(String telefone) {
        if (telefone == null || telefone.isEmpty()) {
            return telefone;
        }
        
        // Remove todos os caracteres não numéricos
        String apenasNumeros = telefone.replaceAll("[^0-9]", "");
        
        // O Asaas aceita apenas números, sem formatação
        // Retorna apenas os números
        return apenasNumeros;
    }

    /**
     * Lista assinaturas do Asaas (com suporte a paginação)
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> listarAssinaturas() {
        if (mockEnabled) {
            return new java.util.ArrayList<>();
        }

        java.util.List<Map<String, Object>> todasAssinaturas = new java.util.ArrayList<>();
        final int limit = 100; // Máximo por página da API do Asaas
        int currentOffset = 0;
        boolean temMais = true;

        try {
            while (temMais) {
                final int offset = currentOffset;
                Map<String, Object> response = webClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/subscriptions")
                                .queryParam("offset", offset)
                                .queryParam("limit", limit)
                                .build())
                        .header("access_token", apiKey)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();

                if (response == null) {
                    break;
                }

                java.util.List<Map<String, Object>> data = (java.util.List<Map<String, Object>>) response.get("data");
                if (data == null || data.isEmpty()) {
                    temMais = false;
                } else {
                    todasAssinaturas.addAll(data);
                    log.info("Buscando assinaturas: offset={}, encontradas={}, total acumulado={}", 
                            offset, data.size(), todasAssinaturas.size());
                    
                    // Verifica se há mais páginas
                    Boolean hasMore = (Boolean) response.get("hasMore");
                    if (hasMore == null || !hasMore) {
                        temMais = false;
                    } else {
                        currentOffset += limit;
                    }
                }
            }
            
            log.info("Total de assinaturas encontradas no Asaas: {}", todasAssinaturas.size());
            return todasAssinaturas;
        } catch (Exception e) {
            log.error("Erro ao listar assinaturas do Asaas", e);
            return todasAssinaturas; // Retorna o que conseguiu buscar até o erro
        }
    }

    /**
     * Lista cobranças do Asaas (com suporte a paginação)
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> listarCobrancas() {
        if (mockEnabled) {
            return new java.util.ArrayList<>();
        }

        java.util.List<Map<String, Object>> todasCobrancas = new java.util.ArrayList<>();
        final int limit = 100; // Máximo por página da API do Asaas
        int currentOffset = 0;
        boolean temMais = true;

        try {
            while (temMais) {
                final int offset = currentOffset;
                Map<String, Object> response = webClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/payments")
                                .queryParam("offset", offset)
                                .queryParam("limit", limit)
                                .build())
                        .header("access_token", apiKey)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();

                if (response == null) {
                    break;
                }

                java.util.List<Map<String, Object>> data = (java.util.List<Map<String, Object>>) response.get("data");
                if (data == null || data.isEmpty()) {
                    temMais = false;
                } else {
                    todasCobrancas.addAll(data);
                    log.info("Buscando cobranças: offset={}, encontradas={}, total acumulado={}", 
                            offset, data.size(), todasCobrancas.size());
                    
                    // Verifica se há mais páginas
                    Boolean hasMore = (Boolean) response.get("hasMore");
                    if (hasMore == null || !hasMore) {
                        temMais = false;
                    } else {
                        currentOffset += limit;
                    }
                }
            }
            
            log.info("Total de cobranças encontradas no Asaas: {}", todasCobrancas.size());
            return todasCobrancas;
        } catch (Exception e) {
            log.error("Erro ao listar cobranças do Asaas", e);
            return todasCobrancas; // Retorna o que conseguiu buscar até o erro
        }
    }

    /**
     * Busca detalhes de um cliente no Asaas
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buscarClientePorId(String customerId) {
        if (mockEnabled) {
            return new HashMap<>();
        }

        try {
            Map<String, Object> response = webClient.get()
                    .uri("/customers/{id}", customerId)
                    .header("access_token", apiKey)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            return response != null ? response : new HashMap<>();
        } catch (Exception e) {
            log.error("Erro ao buscar cliente no Asaas por ID: {}", customerId, e);
            return new HashMap<>();
        }
    }

    /**
     * Verifica se está em modo mock
     */
    public boolean isMockEnabled() {
        return mockEnabled;
    }
}

