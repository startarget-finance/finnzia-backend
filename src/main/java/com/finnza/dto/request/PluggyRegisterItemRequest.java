package com.finnza.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PluggyRegisterItemRequest {

    @NotBlank
    private String itemId;

    private String connectorId;

    private String connectorName;

    /** Ex.: UPDATED, LOGIN_SUCCEEDED — o widget pode enviar string livre. */
    private String status;
}
