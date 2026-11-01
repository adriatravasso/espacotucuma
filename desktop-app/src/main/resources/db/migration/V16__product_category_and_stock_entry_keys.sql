ALTER TABLE produto ADD COLUMN categoria TEXT NOT NULL DEFAULT 'Geral'
    CHECK (length(trim(categoria)) BETWEEN 2 AND 80);

ALTER TABLE movimento_estoque ADD COLUMN chave_idempotencia TEXT;
CREATE UNIQUE INDEX idx_movimento_estoque_idempotency
    ON movimento_estoque (chave_idempotencia) WHERE chave_idempotencia IS NOT NULL;
