-- Planilha manual DFC por empresa (id Bom Controle)

CREATE TABLE IF NOT EXISTS dfc_planilha (
    id BIGSERIAL PRIMARY KEY,
    id_empresa INTEGER NOT NULL,
    months_json TEXT NOT NULL,
    rows_json TEXT NOT NULL,
    data_criacao TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data_atualizacao TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT uk_dfc_planilha_id_empresa UNIQUE (id_empresa)
);

CREATE INDEX IF NOT EXISTS idx_dfc_planilha_id_empresa ON dfc_planilha (id_empresa);

COMMENT ON TABLE dfc_planilha IS 'Demonstrativo de fluxo de caixa editável (meses + linhas) por empresa';
