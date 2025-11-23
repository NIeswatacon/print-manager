# Print Manager - Sistema de Gestão de Impressões 3D

Sistema backend em Clojure para gerenciar custos, estoque de filamentos e precificação de impressões 3D integrado com Bambu Lab Cloud API.

## 🎯 Funcionalidades

- ✅ **Integração com Bambu Cloud**: Sincronização automática do histórico de impressões
- ✅ **Gestão de Estoque**: Controle de filamentos com atualização automática
- ✅ **Cálculo de Custos**: Baseado na planilha STLFlix (energia, amortização, fixos)
- ✅ **Precificação Inteligente**: Sugestão de preços considerando markup e taxas
- ✅ **Relatórios**: Análises mensais, por filamento, top lucrativas
- ✅ **API REST**: Endpoints completos para frontend consumir

## 📋 Pré-requisitos

- Java 11+
- Clojure CLI
- PostgreSQL 14+
- Conta Bambu Lab (email/senha)

## 🚀 Setup

### 1. Instalar Dependências

```bash
# Instalar Clojure CLI (caso não tenha)
# macOS
brew install clojure/tools/clojure

# Linux
curl -O https://download.clojure.org/install/linux-install-1.11.1.1165.sh
chmod +x linux-install-1.11.1.1165.sh
sudo ./linux-install-1.11.1.1165.sh
```

### 2. Configurar PostgreSQL

```bash
# Criar banco de dados
createdb print_manager

# Ou via psql
psql -U postgres
CREATE DATABASE print_manager;
```

### 3. Criar Schema

```bash
psql -U postgres -d print_manager -f schema.sql
```

### 4. Configurar Variáveis de Ambiente

Crie um arquivo `.env` ou configure diretamente:

```bash
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=print_manager
export DB_USER=postgres
export DB_PASSWORD=sua_senha
```

### 5. Ajustar Configurações

Edite o arquivo `database.clj` se necessário para ajustar as credenciais do banco.

## 🏃 Executando

### Iniciar REPL

```bash
clj -M:dev
```

### No REPL, carregar namespaces:

```clojure
(require '[print-manager.api :as api])
(require '[print-manager.sync-service :as sync])
(require '[print-manager.database :as db])

;; Iniciar servidor
(api/start-server! :port 3000)
```

### Ou via Script

Crie `start.clj`:

```clojure
(require '[print-manager.api :as api])
(api/start-server! :port 3000)
@(promise) ;; Mantém o servidor rodando
```

Execute:
```bash
clj -M start.clj
```

## 📡 API Endpoints

### Autenticação

**POST** `/api/auth/bambu`
```json
{
  "email": "seu@email.com",
  "password": "sua_senha"
}
```

**GET** `/api/auth/status`

### Filamentos

**GET** `/api/filamentos` - Lista todos os filamentos

**POST** `/api/filamentos` - Criar novo filamento
```json
{
  "nome": "PLA Preto",
  "marca": "Bambu Lab",
  "tipo": "PLA",
  "cor": "Preto",
  "peso-inicial-g": 1000,
  "preco-compra": 89.90
}
```

**GET** `/api/filamentos/:id` - Buscar por ID

**DELETE** `/api/filamentos/:id` - Desativar filamento

**GET** `/api/filamentos/:id/relatorio` - Estatísticas do filamento

### Impressões

**GET** `/api/impressoes?limit=100&offset=0` - Listar impressões

**GET** `/api/impressoes/:id` - Buscar impressão

**POST** `/api/impressoes/sincronizar` - Sincronizar com Bambu Cloud

**PUT** `/api/impressoes/:id/preco` - Atualizar preço de venda
```json
{
  "preco-venda": 150.00
}
```

**GET** `/api/impressoes/top-lucrativas?n=10` - Top N mais lucrativas

### Configurações

**GET** `/api/configuracoes` - Listar todas as configurações

**PUT** `/api/configuracoes/:chave` - Atualizar configuração
```json
{
  "valor": "1.00"
}
```

Configurações disponíveis:
- `potencia_impressora_watts`
- `custo_kwh`
- `custo_fixo_mensal`
- `impressoes_mes`
- `valor_impressora`
- `vida_util_horas`
- `percentual_falhas`
- `markup_padrao`
- `imposto_percentual`
- `taxa_cartao_percentual`
- `custo_anuncio_percentual`

### Relatórios

**GET** `/api/relatorios/mensal?ano=2025&mes=11` - Relatório mensal

**GET** `/api/relatorios/estatisticas` - Estatísticas gerais do sistema

### Calculadora

**POST** `/api/calculadora/simular` - Simular custos
```json
{
  "tempo-minutos": 480,
  "peso-usado-g": 100,
  "preco-venda": 150.00
}
```

## 🔄 Fluxo de Uso

### 1. Primeira configuração

```bash
# No REPL ou via API
curl -X POST http://localhost:3000/api/auth/bambu \
  -H "Content-Type: application/json" \
  -d '{"email":"seu@email.com","password":"senha"}'
```

### 2. Cadastrar filamentos

```bash
curl -X POST http://localhost:3000/api/filamentos \
  -H "Content-Type: application/json" \
  -d '{
    "nome": "PLA Preto Bambu",
    "marca": "Bambu Lab",
    "tipo": "PLA",
    "cor": "Preto",
    "peso-inicial-g": 1000,
    "preco-compra": 89.90
  }'
```

### 3. Sincronizar impressões

```bash
curl -X POST http://localhost:3000/api/impressoes/sincronizar
```

### 4. Ver estatísticas

```bash
curl http://localhost:3000/api/relatorios/estatisticas
```

## 🔧 Desenvolvimento

### Estrutura do Projeto

```
src/
├── print_manager/
│   ├── api.clj              # API REST e rotas
│   ├── bambu_api.clj        # Cliente Bambu Cloud
│   ├── cost_calculator.clj  # Cálculos de custos
│   ├── database.clj         # Acesso ao banco
│   └── sync_service.clj     # Serviço de sincronização
resources/
└── schema.sql               # Schema PostgreSQL
```

### Testes no REPL

```clojure
;; Autenticar
(sync/autenticar-bambu! "seu@email.com" "senha")

;; Criar filamento
(db/criar-filamento!
 {:nome "PLA Preto"
  :marca "Bambu"
  :tipo "PLA"
  :cor "Preto"
  :peso-inicial-g 1000
  :preco-compra 89.90
  :data-compra (java.time.Instant/now)})

;; Sincronizar impressões
(sync/sincronizar-impressoes!)

;; Ver estatísticas
(sync/estatisticas-gerais)

;; Simular custo
(calc/calcular-impressao-completa
 {:tempo-minutos 480
  :peso-usado-g 100}
 (db/get-all-configs))
```

## 📊 Modelo de Cálculo de Custos

Baseado na planilha STLFlix:

1. **Custo Filamento**: `peso_usado_g * (preco_compra / peso_inicial_g)`
2. **Custo Energia**: `(potencia_watts / 1000) * (tempo_h) * custo_kwh`
3. **Custo Fixo**: `custo_fixo_mensal / impressoes_mes`
4. **Amortização**: `(valor_impressora / vida_util_h) * tempo_h`
5. **Custo Total**: `soma_custos * (1 + percentual_falhas/100)`
6. **Preço Consumidor**: `custo_total * markup / (1 - (impostos + taxas + anúncios)/100)`
7. **Preço Lojista**: `preco_consumidor / 2`

## 🛣️ Roadmap

- [ ] Autenticação JWT para API
- [ ] Webhooks para notificações
- [ ] Export de relatórios em PDF/CSV
- [ ] Controle de múltiplas impressoras
- [ ] Histórico de preços de filamento
- [ ] Análise de lucratividade por modelo
- [ ] Dashboard web em ClojureScript

## 📝 Licença

MIT

## 🤝 Contribuindo

Pull requests são bem-vindos! Para mudanças grandes, por favor abra uma issue primeiro.

## ⚠️ Notas

- A API Bambu Lab não é oficial e pode mudar
- Tokens expiram em ~90 dias (renovação automática)
- Custos de energia são estimados (potência média)
- Sempre faça backup do banco de dados