ALTER TABLE dispositivo_autorizado
    ADD COLUMN credencial_hash CHAR(64),
    ADD COLUMN revogado_em TIMESTAMPTZ;

UPDATE dispositivo_autorizado
SET estado = 'REVOGADO', revogado_em = COALESCE(ultimo_acesso_em, criado_em, now());

ALTER TABLE dispositivo_autorizado
    ADD CONSTRAINT ck_dispositivo_credencial_ativa
        CHECK (estado <> 'ATIVO' OR (credencial_hash IS NOT NULL AND revogado_em IS NULL)),
    ADD CONSTRAINT ck_dispositivo_revogacao
        CHECK ((estado = 'ATIVO' AND revogado_em IS NULL) OR (estado = 'REVOGADO' AND revogado_em IS NOT NULL));

CREATE TABLE codigo_pareamento (
    token_hash CHAR(64) PRIMARY KEY,
    estabelecimento_id UUID NOT NULL REFERENCES estabelecimento_remoto(id) ON DELETE CASCADE,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    expira_em TIMESTAMPTZ NOT NULL,
    consumido_em TIMESTAMPTZ,
    CHECK (expira_em > criado_em)
);

CREATE INDEX idx_codigo_pareamento_estabelecimento
    ON codigo_pareamento (estabelecimento_id, expira_em)
    WHERE consumido_em IS NULL;

CREATE TABLE token_acesso_dispositivo (
    token_hash CHAR(64) PRIMARY KEY,
    dispositivo_id UUID NOT NULL REFERENCES dispositivo_autorizado(id) ON DELETE CASCADE,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    expira_em TIMESTAMPTZ NOT NULL,
    CHECK (expira_em > criado_em)
);

CREATE INDEX idx_token_acesso_dispositivo_expiracao
    ON token_acesso_dispositivo (dispositivo_id, expira_em);
