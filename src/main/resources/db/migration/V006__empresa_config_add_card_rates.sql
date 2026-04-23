DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = 'empresa_config'
    ) THEN
        ALTER TABLE public.empresa_config
            ADD COLUMN IF NOT EXISTS taxa_cartao_credito NUMERIC(7,4),
            ADD COLUMN IF NOT EXISTS taxa_antecipacao_credito NUMERIC(7,4);
    END IF;
END $$;
