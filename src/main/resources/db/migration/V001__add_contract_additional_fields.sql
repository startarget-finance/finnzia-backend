-- Flyway Migration: V001__add_contract_additional_fields.sql
-- Descrição: Adiciona campos adicionais à entidade Contrato
-- Data: 2026-02-13
-- Autor: Finnzia Team

DO $$
BEGIN
    -- Em alguns ambientes (ex.: banco novo), a tabela contratos ainda não existe neste ponto.
    -- Para não quebrar bootstrap, aplica alterações somente quando a tabela existir.
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = 'contratos'
    ) THEN
        ALTER TABLE public.contratos ADD COLUMN IF NOT EXISTS data_venda DATE;
        ALTER TABLE public.contratos ADD COLUMN IF NOT EXISTS data_encerramento DATE;
        ALTER TABLE public.contratos ADD COLUMN IF NOT EXISTS link_contrato VARCHAR(500);
        ALTER TABLE public.contratos ADD COLUMN IF NOT EXISTS status_assinatura VARCHAR(20);
        ALTER TABLE public.contratos ADD COLUMN IF NOT EXISTS projeto VARCHAR(100);
        ALTER TABLE public.contratos ADD COLUMN IF NOT EXISTS valor_entrada DECIMAL(15, 2);

        CREATE INDEX IF NOT EXISTS idx_contrato_data_venda ON public.contratos(data_venda);
        CREATE INDEX IF NOT EXISTS idx_contrato_status_assinatura ON public.contratos(status_assinatura);
        CREATE INDEX IF NOT EXISTS idx_contrato_projeto ON public.contratos(projeto);

        COMMENT ON COLUMN public.contratos.data_venda IS 'Data em que a venda/contrato foi realizado';
        COMMENT ON COLUMN public.contratos.data_encerramento IS 'Data prevista para o encerramento do contrato';
        COMMENT ON COLUMN public.contratos.link_contrato IS 'URL/link para o documento do contrato';
        COMMENT ON COLUMN public.contratos.status_assinatura IS 'Status da assinatura digital: PENDENTE, ASSINADO, CANCELADO';
        COMMENT ON COLUMN public.contratos.projeto IS 'Identificação ou nome do projeto associado ao contrato';
        COMMENT ON COLUMN public.contratos.valor_entrada IS 'Valor de entrada/adiantamento do contrato';
    END IF;
END $$;
