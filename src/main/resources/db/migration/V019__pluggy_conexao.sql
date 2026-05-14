-- Conexões Pluggy (Open Finance) vinculadas ao usuário Finnza.

CREATE TABLE IF NOT EXISTS public.pluggy_conexao (
    id BIGSERIAL PRIMARY KEY,
    usuario_id BIGINT NOT NULL REFERENCES public.usuarios (id),
    pluggy_item_id VARCHAR(64) NOT NULL,
    connector_id VARCHAR(64),
    connector_name VARCHAR(255),
    status VARCHAR(64),
    ultimo_evento TEXT,
    data_criacao TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data_atualizacao TIMESTAMP WITHOUT TIME ZONE
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_pluggy_conexao_item ON public.pluggy_conexao (pluggy_item_id);
CREATE INDEX IF NOT EXISTS idx_pluggy_conexao_usuario ON public.pluggy_conexao (usuario_id);
