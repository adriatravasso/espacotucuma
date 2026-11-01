CREATE TABLE estabelecimento (
    singleton_id INTEGER PRIMARY KEY NOT NULL CHECK (singleton_id = 1),
    id TEXT NOT NULL UNIQUE CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    nome_fantasia TEXT NOT NULL CHECK (length(trim(nome_fantasia)) BETWEEN 2 AND 120),
    moeda TEXT NOT NULL DEFAULT 'BRL' CHECK (moeda = 'BRL'),
    fuso_horario TEXT NOT NULL DEFAULT 'America/Manaus' CHECK (fuso_horario = 'America/Manaus'),
    criado_em_utc TEXT NOT NULL CHECK (length(criado_em_utc) = 20 AND criado_em_utc GLOB '????-??-??T??:??:??Z'),
    atualizado_em_utc TEXT NOT NULL CHECK (length(atualizado_em_utc) = 20 AND atualizado_em_utc GLOB '????-??-??T??:??:??Z')
);

CREATE UNIQUE INDEX idx_usuario_administrador_unico
    ON usuario (perfil)
    WHERE perfil = 'ADMINISTRADOR';
