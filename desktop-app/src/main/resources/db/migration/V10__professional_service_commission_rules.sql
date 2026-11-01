CREATE TABLE regra_comissao_profissional_servico (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    profissional_id TEXT NOT NULL REFERENCES profissional(id) ON DELETE RESTRICT,
    servico_id TEXT NOT NULL REFERENCES servico(id) ON DELETE RESTRICT,
    percentual_pontos_base INTEGER NOT NULL CHECK (percentual_pontos_base BETWEEN 0 AND 10000),
    ativo INTEGER NOT NULL DEFAULT 1 CHECK (ativo IN (0, 1)),
    criado_em_utc TEXT NOT NULL CHECK (length(criado_em_utc) = 20 AND criado_em_utc GLOB '????-??-??T??:??:??Z'),
    atualizado_em_utc TEXT NOT NULL CHECK (length(atualizado_em_utc) = 20 AND atualizado_em_utc GLOB '????-??-??T??:??:??Z'),
    UNIQUE (profissional_id, servico_id)
);

CREATE INDEX idx_regra_comissao_profissional_ativo
    ON regra_comissao_profissional_servico (profissional_id, ativo);

ALTER TABLE atendimento_servico
    ADD COLUMN comissao_regra_id TEXT REFERENCES regra_comissao_profissional_servico(id) ON DELETE RESTRICT;

ALTER TABLE atendimento_servico
    ADD COLUMN comissao_origem TEXT NOT NULL DEFAULT 'SERVICO'
        CHECK (comissao_origem IN ('SERVICO', 'PROFISSIONAL_SERVICO'));
