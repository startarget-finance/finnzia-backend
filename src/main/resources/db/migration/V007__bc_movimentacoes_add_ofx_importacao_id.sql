DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = 'bc_movimentacoes'
    ) THEN
        ALTER TABLE public.bc_movimentacoes
            ADD COLUMN IF NOT EXISTS ofx_importacao_id BIGINT;

        CREATE INDEX IF NOT EXISTS idx_bc_mov_ofx_importacao
            ON public.bc_movimentacoes (ofx_importacao_id);
    END IF;
END $$;
