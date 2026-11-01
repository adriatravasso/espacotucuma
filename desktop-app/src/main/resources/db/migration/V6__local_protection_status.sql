CREATE TABLE status_protecao (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    sync_generation INTEGER NOT NULL DEFAULT 0 CHECK (sync_generation >= 0),
    last_synced_at_utc TEXT CHECK (last_synced_at_utc IS NULL OR (length(last_synced_at_utc) = 20 AND last_synced_at_utc GLOB '????-??-??T??:??:??Z')),
    backup_sync_generation INTEGER NOT NULL DEFAULT 0 CHECK (backup_sync_generation >= 0),
    last_backup_id TEXT,
    last_backup_validated_at_utc TEXT CHECK (last_backup_validated_at_utc IS NULL OR (length(last_backup_validated_at_utc) = 20 AND last_backup_validated_at_utc GLOB '????-??-??T??:??:??Z')),
    CHECK ((last_backup_id IS NULL) = (last_backup_validated_at_utc IS NULL)),
    CHECK (backup_sync_generation <= sync_generation)
);

INSERT INTO status_protecao (id) VALUES (1);
