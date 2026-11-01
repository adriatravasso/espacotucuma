CREATE TABLE estado_entidade_restaurada (
    entidade TEXT NOT NULL CHECK (length(trim(entidade)) BETWEEN 1 AND 80),
    entidade_id TEXT NOT NULL CHECK (length(entidade_id) = 36),
    versao_remota INTEGER NOT NULL CHECK (versao_remota > 0),
    payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
    ultima_operacao_id TEXT NOT NULL CHECK (length(ultima_operacao_id) = 36),
    atualizada_em_utc TEXT NOT NULL CHECK (length(atualizada_em_utc) = 20),
    PRIMARY KEY (entidade, entidade_id)
);

CREATE TABLE reconciliacao_restaurada (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36),
    dispositivo_id TEXT NOT NULL CHECK (length(dispositivo_id) = 36),
    operacao_id TEXT CHECK (operacao_id IS NULL OR length(operacao_id) = 36),
    codigo TEXT NOT NULL CHECK (length(trim(codigo)) BETWEEN 1 AND 80),
    estado TEXT NOT NULL CHECK (estado IN ('ABERTO', 'RESOLVIDO')),
    detalhes_json TEXT NOT NULL CHECK (json_valid(detalhes_json)),
    criado_em_utc TEXT NOT NULL CHECK (length(criado_em_utc) = 20),
    resolvido_em_utc TEXT CHECK (resolvido_em_utc IS NULL OR length(resolvido_em_utc) = 20)
);

CREATE TABLE ponto_recuperacao_aplicado (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    estabelecimento_id TEXT NOT NULL CHECK (length(estabelecimento_id) = 36),
    backup_id TEXT NOT NULL CHECK (length(backup_id) = 36),
    checkpoint_remoto INTEGER NOT NULL CHECK (checkpoint_remoto >= 0),
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    restaurado_em_utc TEXT NOT NULL CHECK (length(restaurado_em_utc) = 20)
);
