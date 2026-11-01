CREATE TABLE autorizacao_download_backup (
    token_hash CHAR(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    backup_id UUID NOT NULL REFERENCES backup_remoto(id) ON DELETE RESTRICT,
    dispositivo_id UUID NOT NULL REFERENCES dispositivo_autorizado(id) ON DELETE RESTRICT,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    expira_em TIMESTAMPTZ NOT NULL,
    usada_em TIMESTAMPTZ,
    CHECK (expira_em > criado_em)
);

CREATE INDEX idx_autorizacao_download_dispositivo_expiracao
    ON autorizacao_download_backup (dispositivo_id, expira_em)
    WHERE usada_em IS NULL;
