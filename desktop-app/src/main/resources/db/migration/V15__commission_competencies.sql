CREATE TABLE competencia_comissao (
    competencia TEXT PRIMARY KEY NOT NULL CHECK (competencia GLOB '????-??' AND substr(competencia,6,2) BETWEEN '01' AND '12'),
    estado TEXT NOT NULL CHECK (estado IN ('FECHADA')),
    fechada_por_usuario_id TEXT NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
    fechada_em_utc TEXT NOT NULL CHECK (length(fechada_em_utc) = 20 AND fechada_em_utc GLOB '????-??-??T??:??:??Z')
);

ALTER TABLE comissao ADD COLUMN competencia TEXT REFERENCES competencia_comissao(competencia) ON DELETE RESTRICT;
ALTER TABLE comissao ADD COLUMN profissional_nome_snapshot TEXT;
CREATE INDEX idx_comissao_competencia_profissional ON comissao (competencia, profissional_id);

CREATE TRIGGER trg_comissao_snapshot_immutable
BEFORE UPDATE OF atendimento_servico_id,profissional_id,base_centavos,percentual_pontos_base,valor_centavos,competencia,profissional_nome_snapshot ON comissao
WHEN NEW.atendimento_servico_id IS NOT OLD.atendimento_servico_id
    OR NEW.profissional_id IS NOT OLD.profissional_id
    OR NEW.base_centavos IS NOT OLD.base_centavos
    OR NEW.percentual_pontos_base IS NOT OLD.percentual_pontos_base
    OR NEW.valor_centavos IS NOT OLD.valor_centavos
    OR NEW.competencia IS NOT OLD.competencia
    OR NEW.profissional_nome_snapshot IS NOT OLD.profissional_nome_snapshot
BEGIN
    SELECT RAISE(ABORT, 'snapshot de comissão é imutável');
END;
