DROP INDEX idx_usuario_administrador_unico;

CREATE TABLE tentativa_login (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    usuario_id TEXT REFERENCES usuario(id) ON DELETE SET NULL,
    login TEXT NOT NULL CHECK (length(trim(login)) BETWEEN 1 AND 80),
    sucesso INTEGER NOT NULL CHECK (sucesso IN (0, 1)),
    motivo TEXT NOT NULL CHECK (motivo IN ('AUTENTICADO', 'CREDENCIAIS_INVALIDAS')),
    ocorrido_em_utc TEXT NOT NULL CHECK (length(ocorrido_em_utc) = 20 AND ocorrido_em_utc GLOB '????-??-??T??:??:??Z')
);

CREATE INDEX idx_tentativa_login_data ON tentativa_login (ocorrido_em_utc);
