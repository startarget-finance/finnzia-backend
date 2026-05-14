package com.finnza.dto.request;

import lombok.Data;

/** Corpo opcional ao pedir connect token (ex.: atualizar item existente). */
@Data
public class PluggyConnectTokenRequest {

    private String itemId;
}
