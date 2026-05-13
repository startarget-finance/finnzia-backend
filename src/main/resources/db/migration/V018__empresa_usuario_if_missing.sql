-- Baseline Flyway em V2 não aplica V001/V002 em banco vazio (ex.: Render).
-- Replica idempotente de V002 para garantir empresa_usuario + FK em usuarios.

CREATE TABLE IF NOT EXISTS public.empresa_usuario (
    id SERIAL PRIMARY KEY,
    usuario_id BIGINT NOT NULL,
    id_empresa INTEGER NOT NULL,
    nome_empresa VARCHAR(255),
    padrao BOOLEAN NOT NULL DEFAULT FALSE,
    ativo BOOLEAN NOT NULL DEFAULT TRUE,
    data_criacao TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data_atualizacao TIMESTAMP WITHOUT TIME ZONE,
    removido_por VARCHAR(100),
    motivo_remocao VARCHAR(500),
    data_remocao TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT uk_empresa_usuario UNIQUE (usuario_id, id_empresa)
);

CREATE INDEX IF NOT EXISTS idx_empresa_usuario_usuario ON public.empresa_usuario (usuario_id);
CREATE INDEX IF NOT EXISTS idx_empresa_usuario_empresa ON public.empresa_usuario (id_empresa);
CREATE INDEX IF NOT EXISTS idx_empresa_usuario_padrao ON public.empresa_usuario (usuario_id, padrao);
CREATE INDEX IF NOT EXISTS idx_empresa_usuario_ativo ON public.empresa_usuario (usuario_id, ativo);
CREATE INDEX IF NOT EXISTS idx_empresa_usuario_removido ON public.empresa_usuario (data_remocao) WHERE data_remocao IS NOT NULL;

COMMENT ON TABLE public.empresa_usuario IS 'Relação many-to-many entre usuarios e empresas do BOMControle. Controla permissões de acesso.';
COMMENT ON COLUMN public.empresa_usuario.id_empresa IS 'ID da empresa no BOMControle (não é PK)';
COMMENT ON COLUMN public.empresa_usuario.padrao IS 'Indica se esta é a empresa padrão do usuário. Apenas uma por usuário deve ter TRUE.';
COMMENT ON COLUMN public.empresa_usuario.ativo IS 'Flag para soft delete: FALSE = removido logicamente mas registrado na auditoria';
COMMENT ON COLUMN public.empresa_usuario.removido_por IS 'Quem removeu o acesso (username ou SISTEMA)';
COMMENT ON COLUMN public.empresa_usuario.data_remocao IS 'Quando o acesso foi removido (soft delete)';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = 'usuarios'
    ) AND NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_empresa_usuario_usuario'
    ) THEN
        ALTER TABLE public.empresa_usuario
            ADD CONSTRAINT fk_empresa_usuario_usuario
            FOREIGN KEY (usuario_id)
            REFERENCES public.usuarios (id)
            ON DELETE CASCADE
            ON UPDATE CASCADE;
    END IF;
END $$;
