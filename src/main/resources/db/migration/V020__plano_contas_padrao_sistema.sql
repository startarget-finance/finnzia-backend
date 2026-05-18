-- Modelo global de plano de contas (categorias) aplicado quando a empresa está vazia.
CREATE TABLE IF NOT EXISTS plano_contas_padrao_sistema (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    conteudo_json TEXT NOT NULL,
    data_atualizacao TIMESTAMP NOT NULL DEFAULT NOW(),
    atualizado_por_email VARCHAR(255)
);
