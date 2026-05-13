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
