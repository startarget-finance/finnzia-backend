-- Banco novo (ex.: Render com baseline em V2): nenhuma migration anterior cria `usuarios`.
-- Garante a tabela antes dos ALTERs; em bancos legados o CREATE é ignorado (IF NOT EXISTS).

CREATE TABLE IF NOT EXISTS public.usuarios (
    id BIGSERIAL PRIMARY KEY,
    nome VARCHAR(100) NOT NULL,
    email VARCHAR(100) NOT NULL,
    senha VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'CLIENTE',
    status VARCHAR(20) NOT NULL DEFAULT 'ATIVO',
    ultimo_acesso TIMESTAMP,
    data_criacao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data_atualizacao TIMESTAMP,
    data_exclusao TIMESTAMP,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    token_reset_senha VARCHAR(255),
    token_reset_senha_expiracao TIMESTAMP,
    omie_app_key VARCHAR(100),
    omie_app_secret VARCHAR(200),
    CONSTRAINT uk_usuarios_email UNIQUE (email)
);

CREATE INDEX IF NOT EXISTS idx_usuario_email ON public.usuarios (email);
CREATE INDEX IF NOT EXISTS idx_usuario_status ON public.usuarios (status);
CREATE INDEX IF NOT EXISTS idx_usuario_deleted ON public.usuarios (deleted);
CREATE INDEX IF NOT EXISTS idx_usuario_email_deleted ON public.usuarios (email, deleted);
CREATE INDEX IF NOT EXISTS idx_usuario_status_deleted ON public.usuarios (status, deleted);
CREATE INDEX IF NOT EXISTS idx_usuario_role_status ON public.usuarios (role, status);

-- Login Google (subject) + hash do código numérico de recuperação de senha
ALTER TABLE public.usuarios
    ADD COLUMN IF NOT EXISTS google_sub VARCHAR(255);

ALTER TABLE public.usuarios
    ADD COLUMN IF NOT EXISTS reset_senha_codigo_hash VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS uk_usuarios_google_sub
    ON public.usuarios (google_sub)
    WHERE google_sub IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_usuarios_google_sub_lookup
    ON public.usuarios (google_sub)
    WHERE google_sub IS NOT NULL;

COMMENT ON COLUMN public.usuarios.google_sub IS 'Identificador estável da conta Google (JWT sub); único quando preenchido.';
COMMENT ON COLUMN public.usuarios.reset_senha_codigo_hash IS 'BCrypt do código de 6 dígitos enviado por e-mail na recuperação de senha.';
