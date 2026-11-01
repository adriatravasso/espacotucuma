CREATE TABLE estorno_recebimento (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    recebimento_id TEXT NOT NULL REFERENCES recebimento(id) ON DELETE RESTRICT,
    movimento_caixa_id TEXT NOT NULL UNIQUE REFERENCES movimento_caixa(id) ON DELETE RESTRICT,
    valor_centavos INTEGER NOT NULL CHECK (valor_centavos > 0),
    chave_idempotencia TEXT NOT NULL UNIQUE CHECK (length(trim(chave_idempotencia)) BETWEEN 1 AND 120),
    motivo TEXT NOT NULL CHECK (length(trim(motivo)) BETWEEN 2 AND 240),
    estornado_por_usuario_id TEXT NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
    estornado_em_utc TEXT NOT NULL CHECK (length(estornado_em_utc) = 20 AND estornado_em_utc GLOB '????-??-??T??:??:??Z')
);

CREATE INDEX idx_estorno_recebimento_origem ON estorno_recebimento (recebimento_id, estornado_em_utc);

CREATE TRIGGER impedir_exclusao_recebimento_confirmado
BEFORE DELETE ON recebimento
BEGIN
    SELECT RAISE(ABORT, 'Recebimentos confirmados não podem ser excluídos.');
END;
