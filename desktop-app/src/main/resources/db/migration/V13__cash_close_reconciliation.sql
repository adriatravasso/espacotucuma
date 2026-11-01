CREATE TABLE conferencia_caixa (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    caixa_turno_id TEXT NOT NULL REFERENCES caixa_turno(id) ON DELETE RESTRICT,
    fechamento_id TEXT NOT NULL CHECK (length(fechamento_id) = 36 AND fechamento_id GLOB '????????-????-????-????-????????????'),
    forma TEXT NOT NULL CHECK (forma IN ('DINHEIRO', 'PIX', 'CARTAO')),
    esperado_centavos INTEGER NOT NULL CHECK (esperado_centavos >= 0),
    conferido_centavos INTEGER NOT NULL CHECK (conferido_centavos >= 0),
    justificativa TEXT CHECK (justificativa IS NULL OR length(trim(justificativa)) BETWEEN 2 AND 240),
    conferido_por_usuario_id TEXT NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
    conferido_em_utc TEXT NOT NULL CHECK (length(conferido_em_utc) = 20 AND conferido_em_utc GLOB '????-??-??T??:??:??Z'),
    UNIQUE (fechamento_id, forma)
);

CREATE INDEX idx_conferencia_caixa_turno_data ON conferencia_caixa (caixa_turno_id, conferido_em_utc);
