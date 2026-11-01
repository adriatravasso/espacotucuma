CREATE INDEX idx_servico_categoria_ativo_nome
    ON servico (categoria COLLATE NOCASE, ativo, nome COLLATE NOCASE);
