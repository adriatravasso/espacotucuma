ALTER TABLE atendimento
    ADD COLUMN observacoes_administrativas TEXT
        CHECK (observacoes_administrativas IS NULL OR length(observacoes_administrativas) <= 500);

ALTER TABLE atendimento
    ADD COLUMN atualizado_em_utc TEXT NOT NULL DEFAULT '1970-01-01T00:00:00Z'
        CHECK (length(atualizado_em_utc) = 20 AND atualizado_em_utc GLOB '????-??-??T??:??:??Z');

UPDATE atendimento
SET atualizado_em_utc = COALESCE(concluido_em_utc, iniciado_em_utc);
