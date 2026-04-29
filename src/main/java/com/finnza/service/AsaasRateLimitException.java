package com.finnza.service;

/**
 * Exceção para sinalizar limite de taxa (HTTP 429) na API do Asaas.
 * Permite que camadas superiores parem loops de sincronização para evitar tempestade de requisições.
 */
public class AsaasRateLimitException extends RuntimeException {
    public AsaasRateLimitException(String message) {
        super(message);
    }
}
