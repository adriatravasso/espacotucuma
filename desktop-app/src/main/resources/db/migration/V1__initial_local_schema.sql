CREATE TABLE usuario (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    nome TEXT NOT NULL CHECK (length(trim(nome)) BETWEEN 2 AND 120),
    login TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK (length(trim(login)) BETWEEN 3 AND 80),
    senha_hash TEXT NOT NULL CHECK (length(senha_hash) >= 20),
    perfil TEXT NOT NULL CHECK (perfil IN ('ADMINISTRADOR', 'RECEPCAO', 'PROFISSIONAL')),
    ativo INTEGER NOT NULL DEFAULT 1 CHECK (ativo IN (0, 1)),
    criado_em_utc TEXT NOT NULL CHECK (length(criado_em_utc) = 20 AND criado_em_utc GLOB '????-??-??T??:??:??Z'),
    atualizado_em_utc TEXT NOT NULL CHECK (length(atualizado_em_utc) = 20 AND atualizado_em_utc GLOB '????-??-??T??:??:??Z')
);

CREATE TABLE cliente (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    nome TEXT NOT NULL CHECK (length(trim(nome)) BETWEEN 2 AND 160),
    telefone TEXT,
    contato_opcional TEXT,
    observacao_operacional TEXT,
    ativo INTEGER NOT NULL DEFAULT 1 CHECK (ativo IN (0, 1)),
    criado_em_utc TEXT NOT NULL CHECK (length(criado_em_utc) = 20 AND criado_em_utc GLOB '????-??-??T??:??:??Z'),
    atualizado_em_utc TEXT NOT NULL CHECK (length(atualizado_em_utc) = 20 AND atualizado_em_utc GLOB '????-??-??T??:??:??Z')
);

CREATE INDEX idx_cliente_nome ON cliente (nome COLLATE NOCASE);
CREATE INDEX idx_cliente_telefone ON cliente (telefone);

CREATE TABLE profissional (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    nome TEXT NOT NULL CHECK (length(trim(nome)) BETWEEN 2 AND 120),
    telefone TEXT,
    ativo INTEGER NOT NULL DEFAULT 1 CHECK (ativo IN (0, 1)),
    criado_em_utc TEXT NOT NULL CHECK (length(criado_em_utc) = 20 AND criado_em_utc GLOB '????-??-??T??:??:??Z'),
    atualizado_em_utc TEXT NOT NULL CHECK (length(atualizado_em_utc) = 20 AND atualizado_em_utc GLOB '????-??-??T??:??:??Z')
);

CREATE INDEX idx_profissional_nome ON profissional (nome COLLATE NOCASE);

CREATE TABLE disponibilidade_profissional (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    profissional_id TEXT NOT NULL REFERENCES profissional(id) ON DELETE RESTRICT,
    dia_semana INTEGER NOT NULL CHECK (dia_semana BETWEEN 0 AND 6),
    inicio_local TEXT NOT NULL CHECK (inicio_local GLOB '??:??'),
    fim_local TEXT NOT NULL CHECK (fim_local GLOB '??:??' AND fim_local > inicio_local),
    ativo INTEGER NOT NULL DEFAULT 1 CHECK (ativo IN (0, 1)),
    UNIQUE (profissional_id, dia_semana, inicio_local, fim_local)
);

CREATE TABLE servico (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    nome TEXT NOT NULL CHECK (length(trim(nome)) BETWEEN 2 AND 120),
    categoria TEXT NOT NULL CHECK (length(trim(categoria)) BETWEEN 2 AND 80),
    duracao_minutos INTEGER NOT NULL CHECK (duracao_minutos BETWEEN 5 AND 1440),
    preco_centavos INTEGER NOT NULL CHECK (preco_centavos >= 0),
    comissao_pontos_base INTEGER NOT NULL DEFAULT 0 CHECK (comissao_pontos_base BETWEEN 0 AND 10000),
    ativo INTEGER NOT NULL DEFAULT 1 CHECK (ativo IN (0, 1)),
    criado_em_utc TEXT NOT NULL CHECK (length(criado_em_utc) = 20 AND criado_em_utc GLOB '????-??-??T??:??:??Z'),
    atualizado_em_utc TEXT NOT NULL CHECK (length(atualizado_em_utc) = 20 AND atualizado_em_utc GLOB '????-??-??T??:??:??Z'),
    UNIQUE (nome COLLATE NOCASE)
);

CREATE TABLE pacote (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    nome TEXT NOT NULL CHECK (length(trim(nome)) BETWEEN 2 AND 120),
    sessoes_contratadas INTEGER NOT NULL CHECK (sessoes_contratadas > 0),
    validade_dias INTEGER NOT NULL CHECK (validade_dias > 0),
    preco_centavos INTEGER NOT NULL CHECK (preco_centavos >= 0),
    ativo INTEGER NOT NULL DEFAULT 1 CHECK (ativo IN (0, 1)),
    criado_em_utc TEXT NOT NULL CHECK (length(criado_em_utc) = 20 AND criado_em_utc GLOB '????-??-??T??:??:??Z'),
    atualizado_em_utc TEXT NOT NULL CHECK (length(atualizado_em_utc) = 20 AND atualizado_em_utc GLOB '????-??-??T??:??:??Z'),
    UNIQUE (nome COLLATE NOCASE)
);

CREATE TABLE saldo_sessoes (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    cliente_id TEXT NOT NULL REFERENCES cliente(id) ON DELETE RESTRICT,
    pacote_id TEXT NOT NULL REFERENCES pacote(id) ON DELETE RESTRICT,
    sessoes_contratadas INTEGER NOT NULL CHECK (sessoes_contratadas > 0),
    sessoes_restantes INTEGER NOT NULL CHECK (sessoes_restantes BETWEEN 0 AND sessoes_contratadas),
    adquirido_em_utc TEXT NOT NULL CHECK (length(adquirido_em_utc) = 20 AND adquirido_em_utc GLOB '????-??-??T??:??:??Z'),
    valido_ate TEXT NOT NULL CHECK (length(valido_ate) = 10 AND valido_ate GLOB '????-??-??'),
    estado TEXT NOT NULL CHECK (estado IN ('ATIVO', 'ESGOTADO', 'EXPIRADO', 'CANCELADO')),
    criado_por_usuario_id TEXT NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
    CHECK ((estado = 'ESGOTADO' AND sessoes_restantes = 0) OR estado <> 'ESGOTADO')
);

CREATE INDEX idx_saldo_sessoes_cliente_estado ON saldo_sessoes (cliente_id, estado, valido_ate);

CREATE TABLE agendamento (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    cliente_id TEXT NOT NULL REFERENCES cliente(id) ON DELETE RESTRICT,
    profissional_id TEXT NOT NULL REFERENCES profissional(id) ON DELETE RESTRICT,
    servico_id TEXT NOT NULL REFERENCES servico(id) ON DELETE RESTRICT,
    inicio_em_utc TEXT NOT NULL CHECK (length(inicio_em_utc) = 20 AND inicio_em_utc GLOB '????-??-??T??:??:??Z'),
    fim_em_utc TEXT NOT NULL CHECK (length(fim_em_utc) = 20 AND fim_em_utc GLOB '????-??-??T??:??:??Z' AND fim_em_utc > inicio_em_utc),
    estado TEXT NOT NULL CHECK (estado IN ('AGENDADO', 'CONFIRMADO', 'EM_ATENDIMENTO', 'CONCLUIDO', 'CANCELADO', 'NAO_COMPARECEU')),
    observacao TEXT,
    criado_por_usuario_id TEXT REFERENCES usuario(id) ON DELETE RESTRICT,
    criado_em_utc TEXT NOT NULL CHECK (length(criado_em_utc) = 20 AND criado_em_utc GLOB '????-??-??T??:??:??Z'),
    atualizado_em_utc TEXT NOT NULL CHECK (length(atualizado_em_utc) = 20 AND atualizado_em_utc GLOB '????-??-??T??:??:??Z')
);

CREATE INDEX idx_agendamento_profissional_inicio ON agendamento (profissional_id, inicio_em_utc);
CREATE INDEX idx_agendamento_cliente_inicio ON agendamento (cliente_id, inicio_em_utc);

CREATE TABLE atendimento (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    agendamento_id TEXT UNIQUE REFERENCES agendamento(id) ON DELETE RESTRICT,
    cliente_id TEXT NOT NULL REFERENCES cliente(id) ON DELETE RESTRICT,
    profissional_id TEXT NOT NULL REFERENCES profissional(id) ON DELETE RESTRICT,
    estado TEXT NOT NULL CHECK (estado IN ('EM_ANDAMENTO', 'CONCLUIDO', 'CANCELADO')),
    subtotal_centavos INTEGER NOT NULL CHECK (subtotal_centavos >= 0),
    desconto_centavos INTEGER NOT NULL DEFAULT 0 CHECK (desconto_centavos >= 0 AND desconto_centavos <= subtotal_centavos),
    total_centavos INTEGER NOT NULL CHECK (total_centavos = subtotal_centavos - desconto_centavos),
    iniciado_em_utc TEXT NOT NULL CHECK (length(iniciado_em_utc) = 20 AND iniciado_em_utc GLOB '????-??-??T??:??:??Z'),
    concluido_em_utc TEXT CHECK (concluido_em_utc IS NULL OR (length(concluido_em_utc) = 20 AND concluido_em_utc GLOB '????-??-??T??:??:??Z')),
    criado_por_usuario_id TEXT REFERENCES usuario(id) ON DELETE RESTRICT,
    criado_em_utc TEXT NOT NULL CHECK (length(criado_em_utc) = 20 AND criado_em_utc GLOB '????-??-??T??:??:??Z')
);

CREATE INDEX idx_atendimento_cliente_data ON atendimento (cliente_id, iniciado_em_utc);
CREATE INDEX idx_atendimento_profissional_data ON atendimento (profissional_id, iniciado_em_utc);

CREATE TABLE utilizacao_pacote (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    saldo_sessoes_id TEXT NOT NULL REFERENCES saldo_sessoes(id) ON DELETE RESTRICT,
    atendimento_id TEXT NOT NULL UNIQUE REFERENCES atendimento(id) ON DELETE RESTRICT,
    sessoes_utilizadas INTEGER NOT NULL CHECK (sessoes_utilizadas > 0),
    utilizado_em_utc TEXT NOT NULL CHECK (length(utilizado_em_utc) = 20 AND utilizado_em_utc GLOB '????-??-??T??:??:??Z'),
    criado_por_usuario_id TEXT NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT
);

CREATE TABLE atendimento_servico (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    atendimento_id TEXT NOT NULL REFERENCES atendimento(id) ON DELETE RESTRICT,
    servico_id TEXT NOT NULL REFERENCES servico(id) ON DELETE RESTRICT,
    nome_snapshot TEXT NOT NULL CHECK (length(trim(nome_snapshot)) BETWEEN 2 AND 120),
    quantidade INTEGER NOT NULL DEFAULT 1 CHECK (quantidade > 0),
    preco_unitario_centavos INTEGER NOT NULL CHECK (preco_unitario_centavos >= 0),
    comissao_pontos_base INTEGER NOT NULL CHECK (comissao_pontos_base BETWEEN 0 AND 10000),
    UNIQUE (atendimento_id, servico_id)
);

CREATE TABLE comissao (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    atendimento_servico_id TEXT NOT NULL UNIQUE REFERENCES atendimento_servico(id) ON DELETE RESTRICT,
    profissional_id TEXT NOT NULL REFERENCES profissional(id) ON DELETE RESTRICT,
    base_centavos INTEGER NOT NULL CHECK (base_centavos >= 0),
    percentual_pontos_base INTEGER NOT NULL CHECK (percentual_pontos_base BETWEEN 0 AND 10000),
    valor_centavos INTEGER NOT NULL CHECK (valor_centavos >= 0),
    estado TEXT NOT NULL CHECK (estado IN ('PENDENTE', 'PAGA', 'ESTORNADA')),
    apurada_em_utc TEXT NOT NULL CHECK (length(apurada_em_utc) = 20 AND apurada_em_utc GLOB '????-??-??T??:??:??Z')
);

CREATE INDEX idx_comissao_profissional_data ON comissao (profissional_id, apurada_em_utc);

CREATE TABLE caixa_turno (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    operador_usuario_id TEXT NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
    estado TEXT NOT NULL CHECK (estado IN ('ABERTO', 'FECHADO')),
    abertura_em_utc TEXT NOT NULL CHECK (length(abertura_em_utc) = 20 AND abertura_em_utc GLOB '????-??-??T??:??:??Z'),
    fechamento_em_utc TEXT CHECK (fechamento_em_utc IS NULL OR (length(fechamento_em_utc) = 20 AND fechamento_em_utc GLOB '????-??-??T??:??:??Z')),
    saldo_abertura_centavos INTEGER NOT NULL CHECK (saldo_abertura_centavos >= 0),
    saldo_fechamento_centavos INTEGER CHECK (saldo_fechamento_centavos IS NULL OR saldo_fechamento_centavos >= 0),
    CHECK ((estado = 'ABERTO' AND fechamento_em_utc IS NULL) OR (estado = 'FECHADO' AND fechamento_em_utc IS NOT NULL))
);

CREATE UNIQUE INDEX idx_caixa_turno_aberto_por_operador ON caixa_turno (operador_usuario_id) WHERE estado = 'ABERTO';

CREATE TABLE recebimento (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    atendimento_id TEXT NOT NULL REFERENCES atendimento(id) ON DELETE RESTRICT,
    forma TEXT NOT NULL CHECK (forma IN ('DINHEIRO', 'PIX', 'CARTAO')),
    valor_centavos INTEGER NOT NULL CHECK (valor_centavos > 0),
    estado TEXT NOT NULL CHECK (estado IN ('CONFIRMADO', 'ESTORNADO')),
    chave_idempotencia TEXT NOT NULL UNIQUE CHECK (length(trim(chave_idempotencia)) BETWEEN 1 AND 120),
    recebido_em_utc TEXT NOT NULL CHECK (length(recebido_em_utc) = 20 AND recebido_em_utc GLOB '????-??-??T??:??:??Z'),
    recebido_por_usuario_id TEXT NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT
);

CREATE INDEX idx_recebimento_atendimento ON recebimento (atendimento_id, recebido_em_utc);

CREATE TABLE movimento_caixa (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    caixa_turno_id TEXT NOT NULL REFERENCES caixa_turno(id) ON DELETE RESTRICT,
    recebimento_id TEXT UNIQUE REFERENCES recebimento(id) ON DELETE RESTRICT,
    tipo TEXT NOT NULL CHECK (tipo IN ('ENTRADA', 'SAIDA', 'SANGRIA', 'SUPRIMENTO', 'ESTORNO')),
    valor_centavos INTEGER NOT NULL CHECK (valor_centavos > 0),
    motivo TEXT NOT NULL CHECK (length(trim(motivo)) BETWEEN 2 AND 240),
    criado_por_usuario_id TEXT NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
    criado_em_utc TEXT NOT NULL CHECK (length(criado_em_utc) = 20 AND criado_em_utc GLOB '????-??-??T??:??:??Z')
);

CREATE TABLE fornecedor (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    nome TEXT NOT NULL CHECK (length(trim(nome)) BETWEEN 2 AND 160),
    telefone TEXT,
    email TEXT,
    ativo INTEGER NOT NULL DEFAULT 1 CHECK (ativo IN (0, 1)),
    criado_em_utc TEXT NOT NULL CHECK (length(criado_em_utc) = 20 AND criado_em_utc GLOB '????-??-??T??:??:??Z')
);

CREATE TABLE produto (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    fornecedor_id TEXT REFERENCES fornecedor(id) ON DELETE RESTRICT,
    sku TEXT UNIQUE,
    nome TEXT NOT NULL CHECK (length(trim(nome)) BETWEEN 2 AND 160),
    unidade TEXT NOT NULL CHECK (unidade IN ('UNIDADE', 'MILILITRO', 'GRAMA', 'PACOTE')),
    custo_unitario_centavos INTEGER NOT NULL CHECK (custo_unitario_centavos >= 0),
    preco_venda_centavos INTEGER CHECK (preco_venda_centavos IS NULL OR preco_venda_centavos >= 0),
    estoque_minimo_milesimos INTEGER NOT NULL DEFAULT 0 CHECK (estoque_minimo_milesimos >= 0),
    ativo INTEGER NOT NULL DEFAULT 1 CHECK (ativo IN (0, 1)),
    criado_em_utc TEXT NOT NULL CHECK (length(criado_em_utc) = 20 AND criado_em_utc GLOB '????-??-??T??:??:??Z'),
    atualizado_em_utc TEXT NOT NULL CHECK (length(atualizado_em_utc) = 20 AND atualizado_em_utc GLOB '????-??-??T??:??:??Z')
);

CREATE INDEX idx_produto_nome ON produto (nome COLLATE NOCASE);

CREATE TABLE movimento_estoque (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    produto_id TEXT NOT NULL REFERENCES produto(id) ON DELETE RESTRICT,
    atendimento_id TEXT REFERENCES atendimento(id) ON DELETE RESTRICT,
    tipo TEXT NOT NULL CHECK (tipo IN ('ENTRADA', 'SAIDA', 'AJUSTE', 'CONSUMO_SERVICO')),
    quantidade_milesimos INTEGER NOT NULL CHECK (quantidade_milesimos > 0),
    custo_unitario_centavos INTEGER NOT NULL CHECK (custo_unitario_centavos >= 0),
    motivo TEXT NOT NULL CHECK (length(trim(motivo)) BETWEEN 2 AND 240),
    criado_por_usuario_id TEXT NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
    criado_em_utc TEXT NOT NULL CHECK (length(criado_em_utc) = 20 AND criado_em_utc GLOB '????-??-??T??:??:??Z')
);

CREATE INDEX idx_movimento_estoque_produto_data ON movimento_estoque (produto_id, criado_em_utc);

CREATE TABLE evento_auditoria (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    usuario_id TEXT REFERENCES usuario(id) ON DELETE RESTRICT,
    operacao_id TEXT NOT NULL CHECK (length(operacao_id) = 36 AND operacao_id GLOB '????????-????-????-????-????????????'),
    acao TEXT NOT NULL CHECK (length(trim(acao)) BETWEEN 2 AND 80),
    entidade TEXT NOT NULL CHECK (length(trim(entidade)) BETWEEN 2 AND 80),
    entidade_id TEXT NOT NULL CHECK (length(entidade_id) = 36 AND entidade_id GLOB '????????-????-????-????-????????????'),
    detalhes_json TEXT NOT NULL DEFAULT '{}',
    ocorrido_em_utc TEXT NOT NULL CHECK (length(ocorrido_em_utc) = 20 AND ocorrido_em_utc GLOB '????-??-??T??:??:??Z')
);

CREATE INDEX idx_evento_auditoria_entidade_data ON evento_auditoria (entidade, entidade_id, ocorrido_em_utc);

CREATE TABLE operacao_pendente (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    entidade TEXT NOT NULL CHECK (length(trim(entidade)) BETWEEN 2 AND 80),
    entidade_id TEXT NOT NULL CHECK (length(entidade_id) = 36 AND entidade_id GLOB '????????-????-????-????-????????????'),
    tipo_operacao TEXT NOT NULL CHECK (length(trim(tipo_operacao)) BETWEEN 2 AND 80),
    chave_idempotencia TEXT NOT NULL UNIQUE CHECK (length(trim(chave_idempotencia)) BETWEEN 1 AND 120),
    payload_json TEXT NOT NULL,
    estado TEXT NOT NULL DEFAULT 'PENDENTE' CHECK (estado IN ('PENDENTE', 'SINCRONIZADA', 'REJEITADA')),
    tentativas INTEGER NOT NULL DEFAULT 0 CHECK (tentativas >= 0),
    criada_em_utc TEXT NOT NULL CHECK (length(criada_em_utc) = 20 AND criada_em_utc GLOB '????-??-??T??:??:??Z'),
    confirmada_em_utc TEXT CHECK (confirmada_em_utc IS NULL OR (length(confirmada_em_utc) = 20 AND confirmada_em_utc GLOB '????-??-??T??:??:??Z'))
);

CREATE INDEX idx_operacao_pendente_estado_data ON operacao_pendente (estado, criada_em_utc);

CREATE TABLE checkpoint_sincronizacao (
    estacao TEXT PRIMARY KEY NOT NULL CHECK (estacao = 'principal'),
    checkpoint_remoto TEXT,
    ultima_sincronizacao_em_utc TEXT CHECK (ultima_sincronizacao_em_utc IS NULL OR (length(ultima_sincronizacao_em_utc) = 20 AND ultima_sincronizacao_em_utc GLOB '????-??-??T??:??:??Z')),
    ultimo_backup_id TEXT,
    ultimo_backup_validado_em_utc TEXT CHECK (ultimo_backup_validado_em_utc IS NULL OR (length(ultimo_backup_validado_em_utc) = 20 AND ultimo_backup_validado_em_utc GLOB '????-??-??T??:??:??Z'))
);

CREATE TABLE estacao_autorizada (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    identificador TEXT NOT NULL UNIQUE CHECK (length(trim(identificador)) BETWEEN 1 AND 120),
    estado TEXT NOT NULL CHECK (estado IN ('ATIVA', 'REVOGADA')),
    autorizada_em_utc TEXT NOT NULL CHECK (length(autorizada_em_utc) = 20 AND autorizada_em_utc GLOB '????-??-??T??:??:??Z'),
    revogada_em_utc TEXT CHECK (revogada_em_utc IS NULL OR (length(revogada_em_utc) = 20 AND revogada_em_utc GLOB '????-??-??T??:??:??Z')),
    CHECK ((estado = 'ATIVA' AND revogada_em_utc IS NULL) OR (estado = 'REVOGADA' AND revogada_em_utc IS NOT NULL))
);

CREATE UNIQUE INDEX idx_estacao_autorizada_unica_ativa
    ON estacao_autorizada (estado)
    WHERE estado = 'ATIVA';

CREATE TABLE backup_remoto (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36 AND id GLOB '????????-????-????-????-????????????'),
    checkpoint_remoto TEXT NOT NULL,
    chave_objeto TEXT NOT NULL UNIQUE,
    checksum_sha256 TEXT NOT NULL CHECK (length(checksum_sha256) = 64),
    estado TEXT NOT NULL CHECK (estado IN ('GERANDO', 'VALIDADO', 'FALHOU')),
    criado_em_utc TEXT NOT NULL CHECK (length(criado_em_utc) = 20 AND criado_em_utc GLOB '????-??-??T??:??:??Z'),
    validado_em_utc TEXT CHECK (validado_em_utc IS NULL OR (length(validado_em_utc) = 20 AND validado_em_utc GLOB '????-??-??T??:??:??Z')),
    CHECK ((estado = 'VALIDADO' AND validado_em_utc IS NOT NULL) OR estado <> 'VALIDADO')
);
