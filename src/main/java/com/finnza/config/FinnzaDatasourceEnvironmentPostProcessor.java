package com.finnza.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Se {@code spring.datasource.url} ficar vazia (ex.: {@code SPRING_DATASOURCE_URL=""} no IntelliJ),
 * o valor do {@code application.properties} deixa de valer; este processador roda
 * {@linkplain Ordered#LOWEST_PRECEDENCE por último} e recoloca o Postgres local no topo
 * das propriedades.
 * <p>Em provedores como Render, Postgres costuma exigir TLS: URLs {@code DB_URL} manuais sem
 * {@code sslmode=require} falham com {@code EOFException} no driver; este processador acrescenta
 * {@code sslmode=require} para JDBC remoto quando faltar.
 */
public class FinnzaDatasourceEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROP_URL = "spring.datasource.url";
    private static final String PROP_USER = "spring.datasource.username";
    private static final String PROP_PASSWORD = "spring.datasource.password";
    private static final String PROP_DRIVER = "spring.datasource.driver-class-name";
    private static final String PROP_FLYWAY_USER = "spring.flyway.user";

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/finnza_db";
    private static final String DEFAULT_LOCAL_USER = "postgres";
    private static final String DEFAULT_DRIVER = "org.postgresql.Driver";

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty(PROP_URL);
        String user = environment.getProperty(PROP_USER);
        String driver = environment.getProperty(PROP_DRIVER);
        boolean missingUrl = url == null || url.isBlank();
        boolean missingUser = user == null || user.isBlank();
        boolean missingDriver = driver == null || driver.isBlank();
        String osUser = System.getProperty("user.name", "");

        Map<String, Object> defaults = new HashMap<>();
        String jdbcDatabaseUrl = environment.getProperty("JDBC_DATABASE_URL");
        String databaseUrl = environment.getProperty("DATABASE_URL");
        String effectiveUrl = url;
        String effectiveUser = user;

        String urlWithSsl = ensureSslModeIfRemotePostgres(url);
        if (urlWithSsl != null && !Objects.equals(url, urlWithSsl)) {
            environment.getPropertySources().addFirst(
                    new MapPropertySource("finnza-jdbc-ssl-normalize", Map.of(PROP_URL, urlWithSsl)));
            url = urlWithSsl;
            missingUrl = url.isBlank();
            effectiveUrl = url;
        }

        if (!missingUrl && !missingUser && !missingDriver
                && !isLocalPostgresUrl(url, user, osUser)) {
            return;
        }

        if (missingUrl) {
            if (jdbcDatabaseUrl != null && !jdbcDatabaseUrl.isBlank()) {
                String jdbcNorm = ensureSslModeIfRemotePostgres(jdbcDatabaseUrl);
                defaults.put(PROP_URL, jdbcNorm != null ? jdbcNorm : jdbcDatabaseUrl);
                effectiveUrl = jdbcNorm != null ? jdbcNorm : jdbcDatabaseUrl;
            } else if (databaseUrl != null && !databaseUrl.isBlank()) {
                ParsedDatabaseUrl parsed = parseDatabaseUrl(databaseUrl);
                if (parsed.jdbcUrl != null && !parsed.jdbcUrl.isBlank()) {
                    defaults.put(PROP_URL, parsed.jdbcUrl);
                    effectiveUrl = parsed.jdbcUrl;
                } else {
                    defaults.put(PROP_URL, DEFAULT_URL);
                    effectiveUrl = DEFAULT_URL;
                }

                if (missingUser && parsed.username != null && !parsed.username.isBlank()) {
                    defaults.put(PROP_USER, parsed.username);
                    effectiveUser = parsed.username;
                }

                String password = environment.getProperty(PROP_PASSWORD);
                if ((password == null || password.isBlank()) && parsed.password != null) {
                    defaults.put(PROP_PASSWORD, parsed.password);
                }
            } else {
                defaults.put(PROP_URL, DEFAULT_URL);
                effectiveUrl = DEFAULT_URL;
            }
        }

        if (missingUser) {
            if (isLocalPostgresUrl(effectiveUrl, effectiveUser, osUser)) {
                defaults.put(PROP_USER, DEFAULT_LOCAL_USER);
                effectiveUser = DEFAULT_LOCAL_USER;
            }
        } else if (isLocalPostgresUrl(effectiveUrl, effectiveUser, osUser)) {
            // Em ambientes locais, evita usar acidentalmente o usuário do sistema operacional.
            defaults.put(PROP_USER, DEFAULT_LOCAL_USER);
            effectiveUser = DEFAULT_LOCAL_USER;
        }

        if (missingDriver) {
            defaults.put(PROP_DRIVER, DEFAULT_DRIVER);
        }

        String flywayUser = environment.getProperty(PROP_FLYWAY_USER);
        boolean missingFlywayUser = flywayUser == null || flywayUser.isBlank();
        boolean flywayUsingOsUser = flywayUser != null && flywayUser.equalsIgnoreCase(osUser);
        if (isLocalPostgresUrl(effectiveUrl, effectiveUser, osUser) && (missingFlywayUser || flywayUsingOsUser)) {
            defaults.put(PROP_FLYWAY_USER, DEFAULT_LOCAL_USER);
        } else if (missingFlywayUser && effectiveUser != null && !effectiveUser.isBlank()) {
            defaults.put(PROP_FLYWAY_USER, effectiveUser);
        }

        environment.getPropertySources().addFirst(
                new MapPropertySource("finnza-default-postgres-datasource", defaults));
    }

    private boolean isLocalPostgresUrl(String jdbcUrl, String configuredUser, String osUser) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return false;
        }
        String normalized = jdbcUrl.toLowerCase();
        if (!(normalized.startsWith("jdbc:postgresql:")
                && (normalized.contains("localhost") || normalized.contains("127.0.0.1")))) {
            return false;
        }
        if (configuredUser == null || configuredUser.isBlank()) {
            return true;
        }
        return configuredUser.equalsIgnoreCase(osUser);
    }

    /**
     * Render e outros Postgres na nuvem exigem TLS; {@code DB_URL} sem {@code sslmode} costuma
     * encerrar a conexão com {@code EOFException}.
     */
    static String ensureSslModeIfRemotePostgres(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return jdbcUrl;
        }
        String lower = jdbcUrl.toLowerCase();
        if (!lower.startsWith("jdbc:postgresql:")) {
            return jdbcUrl;
        }
        if (lower.contains("localhost") || lower.contains("127.0.0.1")) {
            return jdbcUrl;
        }
        if (lower.contains("sslmode=")) {
            return jdbcUrl;
        }
        return jdbcUrl.contains("?") ? jdbcUrl + "&sslmode=require" : jdbcUrl + "?sslmode=require";
    }

    private ParsedDatabaseUrl parseDatabaseUrl(String databaseUrl) {
        try {
            URI uri = URI.create(databaseUrl);
            String scheme = uri.getScheme();
            if (scheme == null || !scheme.startsWith("postgres")) {
                return new ParsedDatabaseUrl(null, null, null);
            }

            String userInfo = uri.getUserInfo();
            String username = null;
            String password = null;
            if (userInfo != null) {
                String[] parts = userInfo.split(":", 2);
                username = parts.length > 0 ? parts[0] : null;
                password = parts.length > 1 ? parts[1] : null;
            }

            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String path = uri.getPath() == null ? "" : uri.getPath();
            String query = uri.getQuery();
            String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + path;

            if (query == null || query.isBlank()) {
                jdbcUrl += "?sslmode=require";
            } else if (!query.contains("sslmode=")) {
                jdbcUrl += "?" + query + "&sslmode=require";
            } else {
                jdbcUrl += "?" + query;
            }

            return new ParsedDatabaseUrl(jdbcUrl, username, password);
        } catch (Exception ignored) {
            return new ParsedDatabaseUrl(null, null, null);
        }
    }

    private record ParsedDatabaseUrl(String jdbcUrl, String username, String password) {}
}
