DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = 'clientes'
    ) THEN
        ALTER TABLE public.clientes
            ADD COLUMN IF NOT EXISTS tipo_pessoa VARCHAR(2),
            ADD COLUMN IF NOT EXISTS classificacao INTEGER,
            ADD COLUMN IF NOT EXISTS bloqueado BOOLEAN NOT NULL DEFAULT FALSE;

        UPDATE public.clientes
        SET tipo_pessoa = COALESCE(tipo_pessoa, 'PJ'),
            classificacao = COALESCE(classificacao, 3),
            bloqueado = COALESCE(bloqueado, FALSE);

        ALTER TABLE public.clientes
            ALTER COLUMN tipo_pessoa SET DEFAULT 'PJ',
            ALTER COLUMN classificacao SET DEFAULT 3,
            ALTER COLUMN bloqueado SET DEFAULT FALSE;

        CREATE INDEX IF NOT EXISTS idx_cliente_tipo_pessoa ON public.clientes (tipo_pessoa);
    END IF;
END $$;

-- Garante tabela de vinculo cliente-empresa usada na parametrizacao.
CREATE TABLE IF NOT EXISTS public.cliente_empresa (
    cliente_id BIGINT NOT NULL,
    id_empresa INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cliente_empresa_cliente_id ON public.cliente_empresa (cliente_id);

-- FK defensiva (só cria se ainda não existir)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_cliente_empresa_cliente'
    ) THEN
        ALTER TABLE public.cliente_empresa
            ADD CONSTRAINT fk_cliente_empresa_cliente
            FOREIGN KEY (cliente_id) REFERENCES public.clientes (id) ON DELETE CASCADE;
    END IF;
END $$;
