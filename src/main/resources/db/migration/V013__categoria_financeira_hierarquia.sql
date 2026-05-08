-- Plano de contas hierárquico: cada linha é um nó (categoria / subcategoria / níveis inferiores).

ALTER TABLE categoria_financeira_empresa
    ADD COLUMN IF NOT EXISTS parent_id BIGINT,
    ADD COLUMN IF NOT EXISTS nome VARCHAR(120),
    ADD COLUMN IF NOT EXISTS ordem INTEGER NOT NULL DEFAULT 0;

-- Raízes: nome = nome_categoria
UPDATE categoria_financeira_empresa
SET nome = TRIM(nome_categoria)
WHERE nome IS NULL
  AND nome_subcategoria IS NULL;

-- Filhos legados: nome = subcategoria; parent = raiz com mesmo tipo/empresa/nome_categoria
UPDATE categoria_financeira_empresa c
SET nome = TRIM(c.nome_subcategoria),
    parent_id = p.id
FROM categoria_financeira_empresa p
WHERE c.nome IS NULL
  AND c.nome_subcategoria IS NOT NULL
  AND TRIM(c.nome_subcategoria) <> ''
  AND c.deleted = FALSE
  AND p.id_empresa = c.id_empresa
  AND p.tipo = c.tipo
  AND LOWER(TRIM(p.nome_categoria)) = LOWER(TRIM(c.nome_categoria))
  AND p.nome_subcategoria IS NULL
  AND p.deleted = FALSE;

-- Órfãos (sem raiz encontrada): nome composto
UPDATE categoria_financeira_empresa
SET nome = TRIM(nome_categoria) || ' / ' || TRIM(nome_subcategoria),
    parent_id = NULL
WHERE nome IS NULL
  AND nome_subcategoria IS NOT NULL
  AND TRIM(nome_subcategoria) <> '';

UPDATE categoria_financeira_empresa
SET nome = COALESCE(NULLIF(TRIM(nome), ''), 'Sem nome')
WHERE nome IS NULL OR TRIM(nome) = '';

ALTER TABLE categoria_financeira_empresa
    ALTER COLUMN nome SET NOT NULL;

ALTER TABLE categoria_financeira_empresa
    DROP COLUMN IF EXISTS nome_categoria;

ALTER TABLE categoria_financeira_empresa
    DROP COLUMN IF EXISTS nome_subcategoria;

ALTER TABLE categoria_financeira_empresa DROP CONSTRAINT IF EXISTS fk_cat_fin_parent;

ALTER TABLE categoria_financeira_empresa
    ADD CONSTRAINT fk_cat_fin_parent
        FOREIGN KEY (parent_id) REFERENCES categoria_financeira_empresa (id);

CREATE INDEX IF NOT EXISTS idx_cat_fin_parent_empresa
    ON categoria_financeira_empresa (id_empresa, parent_id);

CREATE INDEX IF NOT EXISTS idx_cat_fin_empresa_tipo_parent
    ON categoria_financeira_empresa (id_empresa, tipo, parent_id);
