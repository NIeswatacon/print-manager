-- Schema para gerenciamento de impressões 3D

-- Tabela de filamentos (estoque)
CREATE TABLE IF NOT EXISTS filamentos (
                                          id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nome VARCHAR(255) NOT NULL,
    marca VARCHAR(255),
    tipo VARCHAR(50), -- PLA, PETG, ABS, etc
    cor VARCHAR(100),
    peso_inicial_g DECIMAL(10,2) NOT NULL, -- gramas
    peso_atual_g DECIMAL(10,2) NOT NULL,
    preco_compra DECIMAL(10,2) NOT NULL, -- R$
    data_compra TIMESTAMP NOT NULL,
    custo_por_kg DECIMAL(10,2) GENERATED ALWAYS AS (preco_compra / (peso_inicial_g / 1000)) STORED,
    ativo BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- Tabela de impressões (sincronizada com Bambu Cloud)
CREATE TABLE IF NOT EXISTS impressoes (
                                          id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bambu_task_id VARCHAR(255) UNIQUE, -- ID da task no Bambu Cloud
    nome VARCHAR(255) NOT NULL,
    filamento_id UUID REFERENCES filamentos(id),
    data_inicio TIMESTAMP NOT NULL,
    data_fim TIMESTAMP,
    tempo_minutos INTEGER NOT NULL,
    peso_usado_g DECIMAL(10,2) NOT NULL,
    custo_filamento DECIMAL(10,2),
    custo_energia DECIMAL(10,2),
    custo_fixo DECIMAL(10,2),
    custo_amortizacao DECIMAL(10,2),
    custo_acessorios DECIMAL(10,2) DEFAULT 0,
    custo_total DECIMAL(10,2),
    preco_venda DECIMAL(10,2),
    margem_lucro DECIMAL(10,2),
    status VARCHAR(50), -- success, failed, cancelled
    sincronizado BOOLEAN DEFAULT false,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- Tabela de configurações (custos fixos, energia, etc)
CREATE TABLE IF NOT EXISTS configuracoes (
                                             id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chave VARCHAR(255) UNIQUE NOT NULL,
    valor TEXT NOT NULL,
    descricao TEXT,
    tipo VARCHAR(50), -- decimal, integer, string, json
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- Inserir configurações padrão baseadas na planilha
INSERT INTO configuracoes (chave, valor, descricao, tipo) VALUES
                                                              ('potencia_impressora_watts', '1300', 'Potência da impressora em watts', 'integer'),
                                                              ('custo_kwh', '0.90', 'Custo por kWh em R$', 'decimal'),
                                                              ('custo_fixo_mensal', '300.00', 'Custo fixo mensal total', 'decimal'),
                                                              ('impressoes_mes', '40', 'Número médio de impressões por mês', 'integer'),
                                                              ('valor_impressora', '4500.00', 'Valor de compra da impressora', 'decimal'),
                                                              ('vida_util_horas', '20000', 'Vida útil da impressora em horas', 'integer'),
                                                              ('percentual_falhas', '15', 'Percentual de margem para falhas', 'integer'),
                                                              ('markup_padrao', '5', 'Markup padrão para precificação', 'decimal'),
                                                              ('imposto_percentual', '8', 'Percentual de impostos', 'decimal'),
                                                              ('taxa_cartao_percentual', '5', 'Taxa de cartão de crédito', 'decimal'),
                                                              ('custo_anuncio_percentual', '20', 'Custo de anúncios/marketing', 'decimal')
    ON CONFLICT (chave) DO NOTHING;

-- Tabela de credenciais Bambu (criptografada)
CREATE TABLE IF NOT EXISTS bambu_credentials (
                                                 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL,
    access_token TEXT,
    refresh_token TEXT,
    token_expiry TIMESTAMP,
    device_id VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- Índices para performance
CREATE INDEX IF NOT EXISTS idx_impressoes_data ON impressoes(data_inicio DESC);
CREATE INDEX IF NOT EXISTS idx_impressoes_filamento ON impressoes(filamento_id);
CREATE INDEX IF NOT EXISTS idx_impressoes_bambu_task ON impressoes(bambu_task_id);
CREATE INDEX IF NOT EXISTS idx_filamentos_ativo ON filamentos(ativo);

-- Trigger para atualizar updated_at automaticamente
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_filamentos_updated_at BEFORE UPDATE ON filamentos
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_impressoes_updated_at BEFORE UPDATE ON impressoes
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_configuracoes_updated_at BEFORE UPDATE ON configuracoes
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();