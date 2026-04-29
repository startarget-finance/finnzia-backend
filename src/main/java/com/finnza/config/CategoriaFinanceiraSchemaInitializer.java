package com.finnza.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Failsafe de schema: em alguns ambientes locais (IntelliJ) o recurso de migration
 * pode não entrar no classpath compilado, então garantimos a tabela mínima para
 * categorias financeiras sem depender exclusivamente do Flyway.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CategoriaFinanceiraSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS categoria_financeira_empresa (
                    id BIGSERIAL PRIMARY KEY,
                    id_empresa INTEGER NOT NULL,
                    tipo VARCHAR(16) NOT NULL,
                    nome_categoria VARCHAR(120) NOT NULL,
                    nome_subcategoria VARCHAR(120),
                    data_criacao TIMESTAMP NOT NULL DEFAULT NOW(),
                    data_atualizacao TIMESTAMP,
                    data_exclusao TIMESTAMP,
                    deleted BOOLEAN NOT NULL DEFAULT FALSE
                )
                """);
            jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_cat_fin_emp_empresa_tipo
                    ON categoria_financeira_empresa (id_empresa, tipo)
                """);
            jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_cat_fin_emp_deleted
                    ON categoria_financeira_empresa (deleted)
                """);
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS movimentacao_historico (
                    id BIGSERIAL PRIMARY KEY,
                    id_empresa INTEGER NOT NULL,
                    acao VARCHAR(20) NOT NULL,
                    origem_movimentacao_id VARCHAR(150),
                    data_evento TIMESTAMP NOT NULL DEFAULT NOW(),
                    descricao VARCHAR(500),
                    restaurado_em TIMESTAMP,
                    debito BOOLEAN,
                    data_vencimento DATE,
                    data_competencia DATE,
                    data_quitacao DATE,
                    valor NUMERIC(15,2),
                    nome VARCHAR(500),
                    observacao TEXT,
                    nome_categoria_financeira VARCHAR(500),
                    nome_conta_financeira VARCHAR(500),
                    nome_cliente_fornecedor VARCHAR(500)
                )
                """);
            jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_mov_hist_empresa_evento
                    ON movimentacao_historico (id_empresa, data_evento DESC)
                """);
            jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_mov_hist_origem
                    ON movimentacao_historico (origem_movimentacao_id)
                """);
            log.info("Schema categorias financeiras validado (tabela/índices).");
        } catch (Exception ex) {
            log.error("Falha ao garantir schema de categorias financeiras.", ex);
            throw ex;
        }
    }
}
