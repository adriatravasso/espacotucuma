ALTER TABLE evento_auditoria ADD COLUMN origem_estacao_id TEXT
    CHECK (origem_estacao_id IS NULL OR
        (length(origem_estacao_id) = 36 AND origem_estacao_id GLOB '????????-????-????-????-????????????'));

ALTER TABLE evento_auditoria ADD COLUMN motivo TEXT
    CHECK (motivo IS NULL OR length(trim(motivo)) BETWEEN 2 AND 240);

CREATE TRIGGER trg_evento_auditoria_no_update
BEFORE UPDATE ON evento_auditoria
BEGIN
    SELECT RAISE(ABORT, 'eventos de auditoria são imutáveis');
END;

CREATE TRIGGER trg_evento_auditoria_no_delete
BEFORE DELETE ON evento_auditoria
BEGIN
    SELECT RAISE(ABORT, 'eventos de auditoria são imutáveis');
END;

CREATE TRIGGER trg_tentativa_login_no_update
BEFORE UPDATE ON tentativa_login
BEGIN
    SELECT RAISE(ABORT, 'tentativas de login são imutáveis');
END;

CREATE TRIGGER trg_tentativa_login_no_delete
BEFORE DELETE ON tentativa_login
BEGIN
    SELECT RAISE(ABORT, 'tentativas de login são imutáveis');
END;
