ALTER TABLE cliente ADD COLUMN codigo_interno TEXT;

WITH clientes_numerados AS (
    SELECT id, ROW_NUMBER() OVER (ORDER BY criado_em_utc, id) AS sequencia
    FROM cliente
)
UPDATE cliente
SET codigo_interno = (
    SELECT 'CLI-' || printf('%06d', sequencia)
    FROM clientes_numerados
    WHERE clientes_numerados.id = cliente.id
);

CREATE UNIQUE INDEX idx_cliente_codigo_interno ON cliente (codigo_interno);

CREATE TRIGGER trg_cliente_codigo_interno_no_insert
BEFORE INSERT ON cliente
WHEN NEW.codigo_interno IS NULL
    OR length(NEW.codigo_interno) < 10
    OR substr(NEW.codigo_interno, 1, 4) <> 'CLI-'
    OR substr(NEW.codigo_interno, 5) GLOB '*[^0-9]*'
BEGIN
    SELECT RAISE(ABORT, 'código interno do cliente inválido');
END;

CREATE TRIGGER trg_cliente_codigo_interno_immutable
BEFORE UPDATE OF codigo_interno ON cliente
WHEN NEW.codigo_interno <> OLD.codigo_interno
BEGIN
    SELECT RAISE(ABORT, 'código interno do cliente é imutável');
END;
