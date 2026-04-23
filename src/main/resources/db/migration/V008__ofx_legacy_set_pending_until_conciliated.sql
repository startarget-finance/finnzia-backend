DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = 'bc_movimentacoes'
    ) THEN
        -- Alinha lançamentos OFX legados ao fluxo de pré-aprovação:
        -- se ainda não possuem data de conciliação, devem permanecer pendentes
        -- e sem data de quitação automática.
        UPDATE public.bc_movimentacoes
           SET status_pagamento = 'pendente',
               data_quitacao = NULL
         WHERE id_bom_controle LIKE 'ofx:%'
           AND data_conciliacao IS NULL
           AND (status_pagamento IS DISTINCT FROM 'pendente' OR data_quitacao IS NOT NULL);
    END IF;
END $$;
