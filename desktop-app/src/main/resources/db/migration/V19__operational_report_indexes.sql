CREATE INDEX idx_movimento_estoque_tipo_data
    ON movimento_estoque (tipo, criado_em_utc, produto_id);

CREATE INDEX idx_comissao_servico_status
    ON comissao (atendimento_servico_id, estado);

CREATE INDEX idx_conferencia_caixa_data_fechamento
    ON conferencia_caixa (conferido_em_utc, fechamento_id);
