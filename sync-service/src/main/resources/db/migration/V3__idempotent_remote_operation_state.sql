CREATE TABLE entidade_remota_sincronizada (
    estabelecimento_id UUID NOT NULL REFERENCES estabelecimento_remoto(id) ON DELETE RESTRICT,
    entidade TEXT NOT NULL CHECK (length(trim(entidade)) BETWEEN 1 AND 80),
    entidade_id UUID NOT NULL,
    versao BIGINT NOT NULL CHECK (versao > 0),
    payload JSONB NOT NULL,
    ultima_operacao_id UUID NOT NULL REFERENCES operacao_recebida(id) ON DELETE RESTRICT,
    atualizada_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (estabelecimento_id, entidade, entidade_id)
);
