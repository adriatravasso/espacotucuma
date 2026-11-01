ALTER TABLE backup_remoto
    ALTER COLUMN checksum_sha256 DROP NOT NULL;

ALTER TABLE backup_remoto
    DROP CONSTRAINT backup_remoto_checksum_sha256_check,
    ADD CONSTRAINT ck_backup_checksum_format
        CHECK (checksum_sha256 IS NULL OR checksum_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_validated_backup_has_checksum
        CHECK (estado <> 'VALIDADO' OR checksum_sha256 IS NOT NULL);
