ALTER TABLE saldo_sessoes
    ADD COLUMN nome_pacote_snapshot TEXT NOT NULL DEFAULT 'Pacote'
        CHECK (length(trim(nome_pacote_snapshot)) BETWEEN 2 AND 120);

ALTER TABLE saldo_sessoes
    ADD COLUMN preco_pago_centavos INTEGER NOT NULL DEFAULT 0 CHECK (preco_pago_centavos >= 0);

ALTER TABLE saldo_sessoes
    ADD COLUMN forma_pagamento TEXT NOT NULL DEFAULT 'DINHEIRO'
        CHECK (forma_pagamento IN ('DINHEIRO', 'PIX', 'CARTAO'));

CREATE TABLE reversao_utilizacao_pacote (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    utilizacao_pacote_id TEXT NOT NULL UNIQUE REFERENCES utilizacao_pacote(id) ON DELETE RESTRICT,
    motivo TEXT NOT NULL CHECK (length(trim(motivo)) BETWEEN 2 AND 240),
    criado_por_usuario_id TEXT NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
    criado_em_utc TEXT NOT NULL CHECK (length(criado_em_utc) = 20 AND criado_em_utc GLOB '????-??-??T??:??:??Z')
);

CREATE INDEX idx_utilizacao_pacote_saldo_data
    ON utilizacao_pacote (saldo_sessoes_id, utilizado_em_utc);
