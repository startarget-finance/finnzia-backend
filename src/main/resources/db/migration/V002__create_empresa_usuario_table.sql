-- V002__create_empresa_usuario_table.sql
-- Cria tabela many-to-many entre usuários e empresas do BOMControle

CREATE TABLE IF NOT EXISTS empresa_usuario (
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

    -- Constraint: Uma empresa por usuário apenas uma vez
    CONSTRAINT uk_empresa_usuario UNIQUE(usuario_id, id_empresa)
);

-- Índices para performance
CREATE INDEX idx_empresa_usuario_usuario ON empresa_usuario(usuario_id);
CREATE INDEX idx_empresa_usuario_empresa ON empresa_usuario(id_empresa);
CREATE INDEX idx_empresa_usuario_padrao ON empresa_usuario(usuario_id, padrao);
CREATE INDEX idx_empresa_usuario_ativo ON empresa_usuario(usuario_id, ativo);
CREATE INDEX idx_empresa_usuario_removido ON empresa_usuario(data_remocao) WHERE data_remocao IS NOT NULL;

-- Comentários para documentação
COMMENT ON TABLE empresa_usuario IS 'Relação many-to-many entre usuarios e empresas do BOMControle. Controla permissões de acesso.';
COMMENT ON COLUMN empresa_usuario.id_empresa IS 'ID da empresa no BOMControle (não é PK)';
COMMENT ON COLUMN empresa_usuario.padrao IS 'Indica se esta é a empresa padrão do usuário. Apenas uma por usuário deve ter TRUE.';
COMMENT ON COLUMN empresa_usuario.ativo IS 'Flag para soft delete: FALSE = removido logicamente mas registrado na auditoria';
COMMENT ON COLUMN empresa_usuario.removido_por IS 'Quem removeu o acesso (username ou SISTEMA)';
COMMENT ON COLUMN empresa_usuario.data_remocao IS 'Quando o acesso foi removido (soft delete)';

-- FK defensiva: só cria quando a tabela usuarios existir e a FK ainda não existir.
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
            REFERENCES public.usuarios(id)
            ON DELETE CASCADE
            ON UPDATE CASCADE;
    END IF;
END $$;
