package com.finnza.dto.response;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PluggyStatusResponse {
    boolean configured;
    /** Credenciais de desenvolvimento Pluggy (banner “Aplicação demo” no widget). */
    boolean sandboxMode;
    /** Conector Sandbox no widget (env {@code PLUGGY_INCLUDE_SANDBOX} no backend). */
    boolean includeSandbox;
}
