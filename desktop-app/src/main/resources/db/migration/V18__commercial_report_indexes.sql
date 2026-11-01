CREATE INDEX idx_agendamento_inicio_estado
    ON agendamento (inicio_em_utc, estado);

CREATE INDEX idx_atendimento_concluido
    ON atendimento (concluido_em_utc, profissional_id)
    WHERE estado = 'CONCLUIDO';

CREATE INDEX idx_atendimento_servico_atendimento
    ON atendimento_servico (atendimento_id);

CREATE INDEX idx_recebimento_data_forma
    ON recebimento (recebido_em_utc, forma);

CREATE INDEX idx_estorno_recebimento_data
    ON estorno_recebimento (estornado_em_utc, recebimento_id);

CREATE INDEX idx_movimento_caixa_tipo_data
    ON movimento_caixa (tipo, criado_em_utc);
