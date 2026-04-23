DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = 'bc_movimentacoes'
    ) THEN
        ALTER TABLE public.bc_movimentacoes
            ADD COLUMN IF NOT EXISTS ofx_aprovado BOOLEAN;

        -- Regra padrão: movimentações normais ficam visíveis.
        UPDATE public.bc_movimentacoes
           SET ofx_aprovado = TRUE
         WHERE ofx_aprovado IS NULL;

        -- Lançamentos OFX sem conciliação exigem aceite manual.
        UPDATE public.bc_movimentacoes
           SET ofx_aprovado = FALSE
         WHERE id_bom_controle LIKE 'ofx:%'
           AND data_conciliacao IS NULL;

        CREATE INDEX IF NOT EXISTS idx_bc_mov_ofx_aprovado
            ON public.bc_movimentacoes (ofx_aprovado);
    END IF;
END $$;
