package com.finnza.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties(PluggyProperties.class)
public class PluggyClientConfig {

    @Bean(name = "pluggyWebClient")
    public WebClient pluggyWebClient(WebClient.Builder builder, PluggyProperties properties) {
        return builder
                .baseUrl(properties.getApiBaseUrl().trim().replaceAll("/+$", ""))
                .build();
    }
}
