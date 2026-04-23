CREATE TABLE IF NOT EXISTS public.cartoes_credito_empresa (
    id BIGSERIAL PRIMARY KEY,
    id_empresa INTEGER NOT NULL,
    nome VARCHAR(120) NOT NULL,
    bandeira VARCHAR(40),
    final_cartao VARCHAR(4),
    limite NUMERIC(15,2),
    dia_fechamento INTEGER,
    dia_vencimento INTEGER,
    conta_referencia VARCHAR(120),
    ativo BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_cartoes_credito_empresa_id_empresa
    ON public.cartoes_credito_empresa (id_empresa);
