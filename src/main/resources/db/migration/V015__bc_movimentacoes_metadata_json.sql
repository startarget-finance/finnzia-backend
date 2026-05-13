DO $$

BEGIN

    IF EXISTS (

        SELECT 1

        FROM information_schema.tables

        WHERE table_schema = 'public'

          AND table_name = 'bc_movimentacoes'

    ) THEN

        ALTER TABLE public.bc_movimentacoes

            ADD COLUMN IF NOT EXISTS metadata_json TEXT;

    END IF;

END $$;

