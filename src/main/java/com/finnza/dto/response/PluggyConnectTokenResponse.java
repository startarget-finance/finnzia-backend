package com.finnza.dto.response;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PluggyConnectTokenResponse {
    String accessToken;
}
