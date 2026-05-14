package com.finnza.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

/**
 * Garante colunas usadas por {@code MovimentacaoFinanceira} em {@code bc_movimentacoes} mesmo quando
 * a migração Flyway V014 não entra no classpath (ex.: run no IntelliJ sem {@code mvn process-resources}).
 * Idempotente: usa {@code ADD COLUMN IF NOT EXISTS}.
 *
 * <p>Antes de {@code migrate()}, em JDBC local ({@code localhost} / {@code 127.0.0.1}) ou com
 * {@code app.flyway.repair-before-migrate=true}, executa {@link Flyway#repair()} para corrigir checksum
 * de migrações já aplicadas quando os arquivos {@code V0xx__*.sql} foram editados (erro típico no dev).
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class BcMovimentacoesSchemaEnsureConfig {

    private final Environment environment;

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy(DataSource dataSource) {
        return flyway -> {
            if (shouldRepairFlywayChecksums(dataSource)) {
                log.info("Flyway: repair() antes de migrate() (checksum de migrações já aplicadas).");
                flyway.repair();
            }
            flyway.migrate();
            ensureBcMovimentacoesMetaColumns(dataSource);
        };
    }

    /**
     * {@code repair} alinha {@code flyway_schema_history.checksum} ao conteúdo atual dos scripts —
     * só em dev local por padrão, para não mascarar drift acidental em URLs remotas.
     */
    private boolean shouldRepairFlywayChecksums(DataSource dataSource) {
        if (Boolean.TRUE.equals(environment.getProperty("app.flyway.repair-before-migrate", Boolean.class, false))) {
            return true;
        }
        try (Connection c = dataSource.getConnection()) {
            String url = c.getMetaData().getURL();
            if (url == null) {
                return false;
            }
            String u = url.toLowerCase(Locale.ROOT);
            return u.contains("localhost") || u.contains("127.0.0.1");
        } catch (Exception e) {
            log.warn("Flyway: não foi possível inspecionar JDBC URL para decidir repair automático: {}", e.getMessage());
            return false;
        }
    }

    private void ensureBcMovimentacoesMetaColumns(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            if (!tableExists(c, "bc_movimentacoes")) {
                return;
            }
            try (Statement st = c.createStatement()) {
                st.executeUpdate(
                        "ALTER TABLE public.bc_movimentacoes ADD COLUMN IF NOT EXISTS departamento VARCHAR(200)");
                st.executeUpdate(
                        "ALTER TABLE public.bc_movimentacoes ADD COLUMN IF NOT EXISTS rateio_json TEXT");
                st.executeUpdate(
                        "ALTER TABLE public.bc_movimentacoes ADD COLUMN IF NOT EXISTS id_funcionario BIGINT");
                st.executeUpdate(
                        "ALTER TABLE public.bc_movimentacoes ADD COLUMN IF NOT EXISTS metadata_json TEXT");
            }
            log.info("Schema bc_movimentacoes: colunas departamento, rateio_json, id_funcionario e metadata_json conferidas.");
        } catch (Exception e) {
            log.warn("Não foi possível garantir colunas extras em bc_movimentacoes: {}", e.getMessage());
        }
    }

    private static boolean tableExists(Connection c, String tableName) throws Exception {
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getTables(c.getCatalog(), "public", tableName, new String[] { "TABLE" })) {
            if (rs.next()) {
                return true;
            }
        }
        try (ResultSet rs = md.getTables(null, "public", tableName, new String[] { "TABLE" })) {
            return rs.next();
        }
    }
}
