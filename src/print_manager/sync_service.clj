(ns print-manager.sync-service
  (:require [print-manager.bambu-api :as bambu]
            [print-manager.database :as db]
            [print-manager.cost-calculator :as calc]
            [clojure.tools.logging :as log]))

(defn obter-ou-renovar-token
  "Obtém token válido do banco ou renova se expirado"
  []
  (when-let [creds (db/buscar-bambu-credentials)]
    (let [now (java.time.Instant/now)
          expiry (:bambu_credentials/token_expiry creds)]
      (if (and expiry (.isAfter expiry now))
        ;; Token ainda válido
        (:bambu_credentials/access_token creds)
        ;; Token expirado, renovar
        (when-let [new-token (bambu/refresh-token
                               (:bambu_credentials/refresh_token creds))]
          (db/salvar-bambu-credentials!
            {:email (:bambu_credentials/email creds)
             :access-token new-token
             :refresh-token (:bambu_credentials/refresh_token creds)
             :token-expiry (.plusSeconds now 7776000) ; 90 dias
             :device-id (:bambu_credentials/device_id creds)})
          new-token)))))

(defn autenticar-bambu!
  "Autentica na Bambu Cloud e salva credenciais"
  [email password]
  (if-let [auth (bambu/login email password)]
    (let [token (:access-token auth)
          devices (bambu/get-device-list token)
          device-id (:dev_id (first devices))]
      (db/salvar-bambu-credentials!
        {:email email
         :access-token token
         :refresh-token (:refresh-token auth)
         :token-expiry (.plusSeconds (java.time.Instant/now)
                                     (:expires-in auth))
         :device-id device-id})
      {:success true
       :message "Autenticado com sucesso"
       :device-id device-id})
    {:success false
     :message "Falha na autenticação"}))

(defn processar-impressao
  "Processa uma impressão do Bambu: calcula custos e salva no banco"
  [task-data filamento-id config]
  (let [;; Dados básicos da task
        impressao-base {:bambu_task_id (:bambu-task-id task-data)
                        :nome (:nome task-data)
                        :filamento_id filamento-id
                        :data_inicio (:data-inicio task-data)
                        :data_fim (:data-fim task-data)
                        :tempo_minutos (:tempo-minutos task-data)
                        :peso_usado_g (:peso-usado-g task-data)
                        :status (:status task-data)}

        ;; Calcular custos
        custos (calc/calcular-impressao-completa
                 {:tempo-minutos (:tempo-minutos task-data)
                  :peso-usado-g (:peso-usado-g task-data)}
                 config)

        ;; Montar registro completo
        impressao-completa (merge impressao-base
                                  {:custo_filamento (:custo-filamento custos)
                                   :custo_energia (:custo-energia custos)
                                   :custo_fixo (:custo-fixo custos)
                                   :custo_amortizacao (:custo-amortizacao custos)
                                   :custo_total (:custo-total custos)
                                   :preco_venda (:preco-consumidor-sugerido custos)
                                   :margem_lucro (:lucro-liquido custos)
                                   :sincronizado true})]

    ;; Salvar no banco
    (db/criar-impressao! impressao-completa)

    ;; Atualizar estoque de filamento
    (when (= (:status task-data) "success")
      (db/atualizar-estoque-filamento! filamento-id (:peso-usado-g task-data)))

    impressao-completa))

(defn sincronizar-impressoes!
  "Sincroniza todas as impressões do Bambu Cloud que ainda não estão no banco"
  [& {:keys [filamento-id-padrao]
      :or {filamento-id-padrao nil}}]
  (if-let [token (obter-ou-renovar-token)]
    (let [creds (db/buscar-bambu-credentials)
          device-id (:bambu_credentials/device_id creds)
          tasks (bambu/sync-tasks token device-id)
          config (db/get-all-configs)

          ;; Filtrar tasks que já existem no banco
          novas-tasks (filter
                        (fn [task]
                          (nil? (db/buscar-impressao-por-bambu-id
                                  (:bambu-task-id task))))
                        tasks)

          ;; Se não tem filamento padrão, usar o primeiro ativo
          filamento-id (or filamento-id-padrao
                           (:filamentos/id (first (db/listar-filamentos))))

          ;; Processar cada nova task
          resultados (doall
                       (map #(try
                               (processar-impressao % filamento-id config)
                               (catch Exception e
                                 (log/error e "Erro ao processar impressão" %)
                                 nil))
                            novas-tasks))]

      {:success true
       :total-tasks (count tasks)
       :novas-sincronizadas (count (filter some? resultados))
       :tasks resultados})
    {:success false
     :message "Não autenticado. Execute autenticar-bambu! primeiro"}))

(defn recalcular-custos-impressao!
  "Recalcula custos de uma impressão (útil após mudar configurações)"
  [impressao-id]
  (when-let [impressao (db/buscar-impressao impressao-id)]
    (let [config (db/get-all-configs)
          custos (calc/calcular-impressao-completa
                   {:tempo-minutos (:impressoes/tempo_minutos impressao)
                    :peso-usado-g (:impressoes/peso_usado_g impressao)
                    :custo-acessorios (:impressoes/custo_acessorios impressao)
                    :preco-venda (:impressoes/preco_venda impressao)}
                   config)]
      (db/atualizar-impressao!
        impressao-id
        {:custo_filamento (:custo-filamento custos)
         :custo_energia (:custo-energia custos)
         :custo_fixo (:custo-fixo custos)
         :custo_amortizacao (:custo-amortizacao custos)
         :custo_total (:custo-total custos)
         :margem_lucro (:lucro-liquido custos)})
      custos)))

(defn estatisticas-gerais
  "Retorna estatísticas gerais do sistema"
  []
  (let [config (db/get-all-configs)
        filamentos (db/listar-filamentos)
        impressoes-recentes (db/listar-impressoes :limit 10)
        mes-atual (.getMonthValue (java.time.LocalDate/now))
        ano-atual (.getYear (java.time.LocalDate/now))
        relatorio-mes (db/relatorio-mensal ano-atual mes-atual)]
    {:filamentos {:total (count filamentos)
                  :estoque-total-g (reduce + (map :filamentos/peso_atual_g filamentos))
                  :valor-estoque (reduce + (map #(* (:filamentos/peso_atual_g %)
                                                    (/ (:filamentos/preco_compra %)
                                                       (:filamentos/peso_inicial_g %)))
                                                filamentos))}
     :mes-atual {:ano ano-atual
                 :mes mes-atual
                 :total-impressoes (:total_impressoes relatorio-mes)
                 :total-filamento-g (:total_filamento_g relatorio-mes)
                 :total-custos (:total_custos relatorio-mes)
                 :total-receitas (:total_receitas relatorio-mes)
                 :total-lucro (:total_lucro relatorio-mes)
                 :tempo-medio-min (:tempo_medio_min relatorio-mes)}
     :impressoes-recentes impressoes-recentes
     :config config}))

(comment
  ;; Fluxo de uso:

  ;; 1. Autenticar pela primeira vez
  (autenticar-bambu! "seu-email@example.com" "sua-senha")

  ;; 2. Criar alguns filamentos
  (db/criar-filamento!
    {:nome "PLA Preto Bambu"
     :marca "Bambu Lab"
     :tipo "PLA"
     :cor "Preto"
     :peso-inicial-g 1000
     :preco-compra 89.90
     :data-compra (java.time.Instant/now)})

  ;; 3. Sincronizar impressões
  (sincronizar-impressoes!)

  ;; 4. Ver estatísticas
  (estatisticas-gerais)

  ;; 5. Recalcular custos após ajustar configuração
  (db/update-config! "custo_kwh" "1.00")
  (recalcular-custos-impressao! #uuid "...")
  )