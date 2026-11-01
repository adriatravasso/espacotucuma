CREATE TABLE indisponibilidade_profissional (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    profissional_id TEXT NOT NULL REFERENCES profissional(id) ON DELETE RESTRICT,
    inicio_local TEXT NOT NULL CHECK (length(inicio_local) = 16 AND inicio_local GLOB '????-??-??T??:??'),
    fim_local TEXT NOT NULL CHECK (length(fim_local) = 16 AND fim_local GLOB '????-??-??T??:??' AND fim_local > inicio_local),
    motivo TEXT CHECK (motivo IS NULL OR length(trim(motivo)) <= 120),
    criado_em_utc TEXT NOT NULL CHECK (length(criado_em_utc) = 20 AND criado_em_utc GLOB '????-??-??T??:??:??Z'),
    UNIQUE (profissional_id, inicio_local, fim_local)
);

CREATE INDEX idx_indisponibilidade_profissional_periodo
    ON indisponibilidade_profissional (profissional_id, inicio_local, fim_local);

CREATE TRIGGER trg_agendamento_profissional_ativo_insert
BEFORE INSERT ON agendamento
WHEN NEW.estado IN ('AGENDADO', 'CONFIRMADO', 'EM_ATENDIMENTO')
 AND NOT EXISTS (SELECT 1 FROM profissional WHERE id = NEW.profissional_id AND ativo = 1)
BEGIN
    SELECT RAISE(ABORT, 'Profissional inativo não pode receber novo agendamento.');
END;

CREATE TRIGGER trg_agendamento_profissional_ativo_update
BEFORE UPDATE OF cliente_id, profissional_id, servico_id, inicio_em_utc, fim_em_utc, estado ON agendamento
WHEN NEW.estado IN ('AGENDADO', 'CONFIRMADO', 'EM_ATENDIMENTO')
 AND NOT EXISTS (SELECT 1 FROM profissional WHERE id = NEW.profissional_id AND ativo = 1)
BEGIN
    SELECT RAISE(ABORT, 'Profissional inativo não pode receber novo agendamento.');
END;
