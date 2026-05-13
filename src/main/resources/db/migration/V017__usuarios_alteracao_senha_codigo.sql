-- Código por e-mail para alterar senha logado (Meu perfil)
ALTER TABLE public.usuarios
    ADD COLUMN IF NOT EXISTS alteracao_senha_codigo_hash VARCHAR(255);

ALTER TABLE public.usuarios
    ADD COLUMN IF NOT EXISTS alteracao_senha_codigo_expiracao TIMESTAMP;

COMMENT ON COLUMN public.usuarios.alteracao_senha_codigo_hash IS 'BCrypt do código de 6 dígitos para confirmar alteração de senha no perfil.';
COMMENT ON COLUMN public.usuarios.alteracao_senha_codigo_expiracao IS 'Expiração do código de alteração de senha (típico 15 min).';
