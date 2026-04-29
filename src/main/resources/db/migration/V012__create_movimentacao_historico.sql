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
);

CREATE INDEX IF NOT EXISTS idx_mov_hist_empresa_evento
    ON movimentacao_historico (id_empresa, data_evento DESC);

CREATE INDEX IF NOT EXISTS idx_mov_hist_origem
    ON movimentacao_historico (origem_movimentacao_id);
