ALTER TABLE alteracao_remota_recebida
    ADD COLUMN payload_local_json TEXT;

ALTER TABLE alteracao_remota_recebida
    ADD COLUMN recebido_em_utc TEXT NOT NULL DEFAULT '1970-01-01T00:00:00Z'
        CHECK (length(recebido_em_utc) = 20 AND recebido_em_utc GLOB '????-??-??T??:??:??Z');

ALTER TABLE alteracao_remota_recebida
    ADD COLUMN acao_necessaria TEXT NOT NULL DEFAULT 'REVISAR_E_DECIDIR_MANUALMENTE'
        CHECK (acao_necessaria = 'REVISAR_E_DECIDIR_MANUALMENTE');

ALTER TABLE alteracao_remota_recebida
    ADD COLUMN estado_reconciliacao TEXT NOT NULL DEFAULT 'NAO_APLICA'
        CHECK (estado_reconciliacao IN ('NAO_APLICA', 'ABERTO', 'RESOLVIDO'));

ALTER TABLE alteracao_remota_recebida
    ADD COLUMN decisao TEXT
        CHECK (decisao IS NULL OR decisao = 'MANTER_LOCAL_PRESERVAR_REMOTO');

ALTER TABLE alteracao_remota_recebida
    ADD COLUMN decidido_por_usuario_id TEXT
        CHECK (decidido_por_usuario_id IS NULL OR
            (length(decidido_por_usuario_id) = 36 AND
             decidido_por_usuario_id GLOB '????????-????-????-????-????????????'));

ALTER TABLE alteracao_remota_recebida
    ADD COLUMN decidido_em_utc TEXT
        CHECK (decidido_em_utc IS NULL OR
            (length(decidido_em_utc) = 20 AND decidido_em_utc GLOB '????-??-??T??:??:??Z'));

ALTER TABLE alteracao_remota_recebida
    ADD COLUMN motivo_decisao TEXT
        CHECK (motivo_decisao IS NULL OR length(trim(motivo_decisao)) BETWEEN 2 AND 240);

UPDATE alteracao_remota_recebida
SET estado_reconciliacao = 'ABERTO'
WHERE estado_local = 'CONFLITO';

UPDATE alteracao_remota_recebida
SET recebido_em_utc = strftime('%Y-%m-%dT%H:%M:%SZ', 'now')
WHERE estado_local = 'CONFLITO';

UPDATE alteracao_remota_recebida AS conflito
SET payload_local_json = (
    SELECT operacao.payload_json
    FROM operacao_pendente AS operacao
    WHERE operacao.entidade = conflito.entidade
      AND operacao.entidade_id = conflito.entidade_id
    ORDER BY CASE WHEN operacao.id = conflito.operacao_id THEN 0 ELSE 1 END, operacao.rowid DESC
    LIMIT 1
)
WHERE conflito.estado_local = 'CONFLITO';

CREATE INDEX idx_alteracao_remota_estado_reconciliacao
    ON alteracao_remota_recebida (estado_reconciliacao, sequencia DESC);
