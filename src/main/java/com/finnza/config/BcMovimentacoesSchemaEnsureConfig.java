package com.finnza.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Garante colunas usadas por {@code MovimentacaoFinanceira} em {@code bc_movimentacoes} mesmo quando
 * a migração Flyway V014 não entra no classpath (ex.: run no IntelliJ sem {@code mvn process-resources}).
 * Idempotente: usa {@code ADD COLUMN IF NOT EXISTS}.
 */
@Configuration
@Slf4j
public class BcMovimentacoesSchemaEnsureConfig {

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy(DataSource dataSource) {
        return flyway -> {
            flyway.migrate();
            ensureBcMovimentacoesMetaColumns(dataSource);
        };
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
