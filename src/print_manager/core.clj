(ns print-manager.core
  (:require [print-manager.api :as api]
            [print-manager.database :as db]
            [print-manager.sync-service :as sync]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn setup-inicial!
  "Verifica e cria estrutura inicial se necessário"
  []
  (log/info "Verificando configuração inicial...")

  ;; Verificar se há configurações
  (when (empty? (db/get-all-configs))
    (log/warn "Nenhuma configuração encontrada. Execute schema.sql primeiro!"))

  ;; Verificar autenticação Bambu
  (if-let [creds (db/buscar-bambu-credentials)]
    (log/info "Credenciais Bambu encontradas para:" (:bambu_credentials/email creds))
    (log/warn "Nenhuma credencial Bambu configurada. Use POST /api/auth/bambu"))

  ;; Verificar filamentos
  (let [filamentos (db/listar-filamentos)]
    (if (empty? filamentos)
      (log/warn "Nenhum filamento cadastrado. Cadastre pelo menos um filamento.")
      (log/info (str "Encontrados " (count filamentos) " filamentos ativos"))))

  (log/info "Setup inicial concluído!"))

(defn -main
  "Ponto de entrada da aplicação"
  [& args]
  (let [port (or (some-> (System/getenv "PORT") Integer/parseInt) 3000)]
    (log/info "=== Print Manager - Sistema de Gestão de Impressões 3D ===")
    (log/info "Iniciando servidor...")

    ;; Setup inicial
    (try
      (setup-inicial!)
      (catch Exception e
        (log/error e "Erro no setup inicial. Verifique se o banco está configurado.")))

    ;; Iniciar servidor
    (api/start-server! :port port)
    (log/info (str "Servidor rodando em http://localhost:" port))
    (log/info "Documentação: http://localhost:" port "/api/health")
    (log/info "Pressione Ctrl+C para parar")

    ;; Manter rodando
    @(promise)))

(comment
  ;; Para desenvolvimento, use estas funções diretamente:

  ;; 1. Iniciar servidor manualmente
  (api/start-server! :port 3000)

  ;; 2. Setup inicial
  (setup-inicial!)

  ;; 3. Fluxo completo de teste:

  ;; a) Autenticar
  (sync/autenticar-bambu! "seu@email.com" "senha")

  ;; b) Criar filamento de teste
  (db/criar-filamento!
    {:nome "PLA Branco"
     :marca "Bambu Lab"
     :tipo "PLA"
     :cor "Branco"
     :peso-inicial-g 1000
     :preco-compra 89.90
     :data-compra (java.time.Instant/now)})

  ;; c) Sincronizar impressões
  (sync/sincronizar-impressoes!)

  ;; d) Ver estatísticas
  (sync/estatisticas-gerais)

  ;; e) Simular custo de impressão
  (require '[print-manager.cost-calculator :as calc])
  (calc/calcular-impressao-completa
    {:tempo-minutos 240
     :peso-usado-g 50}
    (db/get-all-configs))

  ;; f) Gerar relatório mensal
  (db/relatorio-mensal 2025 11)

  ;; g) Top 5 impressões mais lucrativas
  (db/top-impressoes-lucrativas 5)
  )