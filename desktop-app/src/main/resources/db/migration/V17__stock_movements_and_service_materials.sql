ALTER TABLE movimento_estoque ADD COLUMN direcao_ajuste TEXT
    CHECK (direcao_ajuste IN ('ENTRADA', 'SAIDA'));
ALTER TABLE movimento_estoque ADD COLUMN atendimento_servico_id TEXT
    REFERENCES atendimento_servico(id) ON DELETE RESTRICT;
ALTER TABLE movimento_estoque ADD COLUMN movimento_origem_id TEXT
    REFERENCES movimento_estoque(id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX idx_movimento_estoque_reversal
    ON movimento_estoque (movimento_origem_id) WHERE movimento_origem_id IS NOT NULL;

CREATE TABLE material_servico (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    servico_id TEXT NOT NULL REFERENCES servico(id) ON DELETE RESTRICT,
    produto_id TEXT NOT NULL REFERENCES produto(id) ON DELETE RESTRICT,
    quantidade_milesimos INTEGER NOT NULL CHECK (quantidade_milesimos > 0),
    ativo INTEGER NOT NULL DEFAULT 1 CHECK (ativo IN (0, 1)),
    criado_em_utc TEXT NOT NULL CHECK (length(criado_em_utc) = 20 AND criado_em_utc GLOB '????-??-??T??:??:??Z'),
    atualizado_em_utc TEXT NOT NULL CHECK (length(atualizado_em_utc) = 20 AND atualizado_em_utc GLOB '????-??-??T??:??:??Z'),
    UNIQUE (servico_id, produto_id)
);

CREATE INDEX idx_material_servico_ativo ON material_servico (servico_id, ativo);

CREATE TRIGGER trg_movimento_estoque_no_update
BEFORE UPDATE ON movimento_estoque
BEGIN
    SELECT RAISE(ABORT, 'movimentos de estoque são imutáveis');
END;

CREATE TRIGGER trg_movimento_estoque_no_delete
BEFORE DELETE ON movimento_estoque
BEGIN
    SELECT RAISE(ABORT, 'movimentos de estoque são imutáveis');
END;
