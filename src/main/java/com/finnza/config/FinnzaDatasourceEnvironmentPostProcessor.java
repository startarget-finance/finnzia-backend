package com.finnza.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Se {@code spring.datasource.url} ficar vazia (ex.: {@code SPRING_DATASOURCE_URL=""} no IntelliJ),
 * o valor do {@code application.properties} deixa de valer; este processador roda
 * {@linkplain Ordered#LOWEST_PRECEDENCE por último} e recoloca o Postgres local no topo
 * das propriedades.
 */
public class FinnzaDatasourceEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROP_URL = "spring.datasource.url";
    private static final String PROP_USER = "spring.datasource.username";
    private static final String PROP_DRIVER = "spring.datasource.driver-class-name";

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/finnza_db";
    private static final String DEFAULT_USER = "arthurbowens";
    private static final String DEFAULT_DRIVER = "org.postgresql.Driver";

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

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
