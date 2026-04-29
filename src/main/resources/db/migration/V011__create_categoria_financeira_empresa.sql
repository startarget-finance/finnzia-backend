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
);

CREATE INDEX IF NOT EXISTS idx_cat_fin_emp_empresa_tipo
    ON categoria_financeira_empresa (id_empresa, tipo);

CREATE INDEX IF NOT EXISTS idx_cat_fin_emp_deleted
    ON categoria_financeira_empresa (deleted);
