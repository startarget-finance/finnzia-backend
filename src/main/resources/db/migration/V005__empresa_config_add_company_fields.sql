DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = 'empresa_config'
    ) THEN
        ALTER TABLE public.empresa_config
            ADD COLUMN IF NOT EXISTS cnpj VARCHAR(14),
            ADD COLUMN IF NOT EXISTS razao_social VARCHAR(255),
            ADD COLUMN IF NOT EXISTS nome_fantasia VARCHAR(255),
            ADD COLUMN IF NOT EXISTS email_empresa VARCHAR(255),
            ADD COLUMN IF NOT EXISTS telefone_empresa VARCHAR(40);

        CREATE INDEX IF NOT EXISTS idx_empresa_config_cnpj ON public.empresa_config (cnpj);
    END IF;
END $$;
