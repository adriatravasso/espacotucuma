ALTER TABLE backup_remoto
    ADD COLUMN verificado_em TIMESTAMPTZ,
    ADD COLUMN resultado_verificacao TEXT;

ALTER TABLE backup_remoto
    DROP CONSTRAINT backup_remoto_estado_check,
    ADD CONSTRAINT ck_backup_state
        CHECK (estado IN ('PENDENTE', 'VALIDADO', 'FALHOU', 'EXPIRADO')),
    ADD CONSTRAINT ck_backup_verification_result
        CHECK (resultado_verificacao IS NULL OR resultado_verificacao IN (
            'INTEGRO', 'CHECKSUM_INVALIDO', 'FORMATO_INVALIDO', 'OBJETO_AUSENTE',
            'ERRO_STORAGE', 'REMOVIDO_RETENCAO'
        )),
    ADD CONSTRAINT ck_expired_backup_was_removed
        CHECK (estado <> 'EXPIRADO' OR resultado_verificacao = 'REMOVIDO_RETENCAO');

CREATE INDEX idx_backup_remoto_retention
    ON backup_remoto (estabelecimento_id, estado, criado_em DESC);
