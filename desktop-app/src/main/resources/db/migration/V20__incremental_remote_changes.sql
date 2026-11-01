CREATE TABLE cursor_alteracao_remota (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    sequencia INTEGER NOT NULL DEFAULT 0 CHECK (sequencia >= 0)
);

INSERT INTO cursor_alteracao_remota (id, sequencia) VALUES (1, 0);

CREATE TABLE alteracao_remota_recebida (
    sequencia INTEGER PRIMARY KEY CHECK (sequencia > 0),
    operacao_id TEXT NOT NULL UNIQUE CHECK (length(operacao_id) = 36),
    dispositivo_origem_id TEXT NOT NULL CHECK (length(dispositivo_origem_id) = 36),
    entidade TEXT NOT NULL CHECK (length(trim(entidade)) BETWEEN 1 AND 80),
    entidade_id TEXT NOT NULL CHECK (length(entidade_id) = 36),
    tipo_operacao TEXT NOT NULL CHECK (length(trim(tipo_operacao)) BETWEEN 1 AND 80),
    payload_versao INTEGER NOT NULL CHECK (payload_versao > 0),
    versao_entidade INTEGER NOT NULL CHECK (versao_entidade >= 0),
    resultado TEXT NOT NULL CHECK (resultado IN ('ACEITA', 'REJEITADA', 'CONFLITO')),
    payload_json TEXT NOT NULL,
    estado_local TEXT NOT NULL CHECK (estado_local IN ('RECONCILIADA', 'CONFLITO')),
    motivo_conflito TEXT
);

CREATE INDEX idx_alteracao_remota_conflito
    ON alteracao_remota_recebida (estado_local, sequencia)
    WHERE estado_local = 'CONFLITO';
