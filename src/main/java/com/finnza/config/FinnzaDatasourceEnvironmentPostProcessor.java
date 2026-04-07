package com.finnza.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Se {@code spring.datasource.url} vier vazia (ex.: {@code SPRING_DATASOURCE_URL=""} no IntelliJ),
 * o Spring ignora o valor do {@code application.properties} e sobe H2 em memória quando o JAR
 * do H2 está no classpath. Este post-processor reativa o Postgres local por padrão.
 */
public class FinnzaDatasourceEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROP_URL = "spring.datasource.url";
    private static final String PROP_USER = "spring.datasource.username";
    private static final String PROP_DRIVER = "spring.datasource.driver-class-name";

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/finnza_db";
    private static final String DEFAULT_USER = "arthurbowens";
    private static final String DEFAULT_DRIVER = "org.postgresql.Driver";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty(PROP_URL);
        if (url != null && !url.isBlank()) {
            return;
        }

        Map<String, Object> defaults = new HashMap<>();
        defaults.put(PROP_URL, DEFAULT_URL);

        String user = environment.getProperty(PROP_USER);
        if (user == null || user.isBlank()) {
            defaults.put(PROP_USER, DEFAULT_USER);
        }

        String driver = environment.getProperty(PROP_DRIVER);
        if (driver == null || driver.isBlank()) {
            defaults.put(PROP_DRIVER, DEFAULT_DRIVER);
        }

        environment.getPropertySources().addFirst(
                new MapPropertySource("finnza-default-postgres-datasource", defaults));
    }
}
