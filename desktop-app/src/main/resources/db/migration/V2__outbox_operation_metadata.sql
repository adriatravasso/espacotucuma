ALTER TABLE operacao_pendente
    ADD COLUMN payload_versao INTEGER NOT NULL DEFAULT 1 CHECK (payload_versao > 0);

ALTER TABLE operacao_pendente
    ADD COLUMN versao_esperada INTEGER NOT NULL DEFAULT 0 CHECK (versao_esperada >= 0);

ALTER TABLE operacao_pendente
    ADD COLUMN origem_estacao_id TEXT NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'
    CHECK (length(origem_estacao_id) = 36 AND origem_estacao_id GLOB '????????-????-????-????-????????????');

ALTER TABLE operacao_pendente
    ADD COLUMN versao_remota_confirmada INTEGER CHECK (versao_remota_confirmada IS NULL OR versao_remota_confirmada > 0);
