DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = 'bc_movimentacoes'
    ) THEN
        ALTER TABLE public.bc_movimentacoes
            ADD COLUMN IF NOT EXISTS departamento VARCHAR(200);
        ALTER TABLE public.bc_movimentacoes
            ADD COLUMN IF NOT EXISTS rateio_json TEXT;
        ALTER TABLE public.bc_movimentacoes
            ADD COLUMN IF NOT EXISTS id_funcionario BIGINT;
    END IF;
END $$;
