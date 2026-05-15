package com.finnza.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Credenciais e URL da API Pluggy (Open Finance).
 * Nunca exponha {@code clientSecret} ao frontend.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "pluggy")
public class PluggyProperties {

    /** Se false, endpoints Pluggy retornam 503 até você configurar credenciais. */
    private boolean enabled = false;

    private String clientId = "";

    private String clientSecret = "";

    /** Base da API (produção e sandbox usam o mesmo host; o conector Sandbox define o ambiente de teste). */
    private String apiBaseUrl = "https://api.pluggy.ai";

    /**
     * URL pública do seu backend para webhooks Pluggy (ex.: https://seu-backend.onrender.com/api/webhooks/pluggy).
     * Opcional: se vazio, o connect token não envia webhookUrl (pode configurar só no dashboard Pluggy).
     */
    private String webhookUrl = "";

    /**
     * {@code true} quando as credenciais são do ambiente <strong>Development/Sandbox</strong> no dashboard Pluggy
     * (widget exibe “Aplicação demo”). Em produção use {@code false} e Client ID/Secret da aba <strong>Production</strong>.
     */
    private boolean sandboxMode = false;

    /**
     * Se {@code true}, o frontend passa {@code includeSandbox: true} ao Pluggy Connect (conector Sandbox na lista).
     * Defina {@code PLUGGY_INCLUDE_SANDBOX=true} no Render — lido em runtime pelo {@code GET /api/pluggy/status}.
     */
    private boolean includeSandbox = false;
}
