(ns print-manager.sync-service
  (:require [print-manager.bambu-api :as bambu]
            [print-manager.database :as db]
            [print-manager.cost-calculator :as calc]
            [clojure.tools.logging :as log]))

;; ---------------------------------------------------------
;; TOKEN: pegar do banco (sem renovar por enquanto)
;; ---------------------------------------------------------

(defn obter-ou-renovar-token
  "Por enquanto, só devolve o access-token salvo no banco."
  []
  (when-let [creds (db/buscar-bambu-credentials)]
    (:bambu_credentials/access_token creds)))

;; ---------------------------------------------------------
;; AUTENTICAÇÃO EM 2 ETAPAS
;; ---------------------------------------------------------

(defn autenticar-bambu-com-senha!
  "1º passo: usa email+senha. Para contas Google, a Bambu responde loginType=verifyCode
   e manda um código por e-mail."
  [email password]
  (let [resp (bambu/login-password email password)]
    (cond
      (nil? resp)
      {:success false
       :message "Falha na comunicação com a Bambu Cloud."}

      (= "verifyCode" (:loginType resp))
      {:success false
       :requires-code true
       :message "Código de verificação enviado para o seu e-mail. Chame novamente com email+code."}

      (:accessToken resp)
      (let [token   (:accessToken resp)
            refresh (:refreshToken resp)
            expires (long (or (:expiresIn resp) 0))
            devices (bambu/get-device-list token)
            device-id (:dev_id (first devices))
            expiry-instant (.plusSeconds (java.time.Instant/now) expires)]
        (db/salvar-bambu-credentials!
          {:email email
           :access-token token
           :refresh-token refresh
           :token-expiry expiry-instant
           :device-id device-id})
        {:success true
         :message "Autenticado com sucesso"
         :device-id device-id})

      :else
      {:success false
       :message (str "Resposta inesperada da Bambu Cloud ao autenticar com senha: "
                     (pr-str resp))})))

(defn autenticar-bambu-com-codigo!
  "2º passo: usa email+code (recebido por e-mail) para obter accessToken e salvar no banco."
  [email code]
  (let [resp (bambu/login-code email code)]
    (cond
      (nil? resp)
      {:success false
       :message "Falha ao validar o código na Bambu Cloud."}

      (not (:accessToken resp))
      {:success false
       :message (str "Código inválido ou expirado. Resposta: " (pr-str resp))}

      :else
      (let [token   (:accessToken resp)
            refresh (:refreshToken resp)
            expires (long (or (:expiresIn resp) 0))
            devices (bambu/get-device-list token)
            device-id (:dev_id (first devices))
            expiry-instant (.plusSeconds (java.time.Instant/now) expires)]
        (db/salvar-bambu-credentials!
          {:email email
           :access-token token
           :refresh-token refresh
           :token-expiry expiry-instant
           :device-id device-id})
        {:success true
         :message "Autenticado com sucesso com código"
         :device-id device-id}))))

;; ---------------------------------------------------------
;; PROCESSAMENTO DE IMPRESSÃO
;; ---------------------------------------------------------

(defn processar-impressao
  "Processa uma impressão do Bambu: calcula custos e salva no banco"
  [task-data filamento-id config]
  (let [;; Buscar o filamento para pegar o custo-por-kg
        filamento     (db/buscar-filamento filamento-id)
        custo-por-kg  (:filamentos/custo_por_kg filamento)

        ;; Dados básicos da task
        impressao-base {:bambu_task_id (:bambu-task-id task-data)
                        :nome           (:nome task-data)
                        :filamento_id   filamento-id
                        :data_inicio    (:data-inicio task-data)
                        :data_fim       (:data-fim task-data)
                        :tempo_minutos  (:tempo-minutos task-data)
                        :peso_usado_g   (:peso-usado-g task-data)
                        :status         (:status task-data)}

        ;; Calcular custos
        custos (calc/calcular-impressao-completa
                 {:tempo-minutos (:tempo-minutos task-data)
                  :peso-usado-g  (:peso-usado-g task-data)}
                 (assoc config :custo-por-kg custo-por-kg))

        ;; Monta o mapa final que vai pro banco
        impressao-completa
        (merge impressao-base
               {:custo_filamento           (:custo-filamento custos)
                :custo_energia             (:custo-energia custos)
                :custo_fixo                (:custo-fixo custos)
                :custo_amortizacao         (:custo-amortizacao custos)
                :custo_total               (:custo-total custos)

                ;; 🔹 preços sugeridos
                :preco_consumidor_sugerido (:preco-consumidor-sugerido custos)
                :preco_lojista_sugerido    (:preco-lojista-sugerido custos)

                ;; 🔹 preço efetivo de venda (inicial = sugerido consumidor)
                :preco_venda               (:preco-venda-real custos)
                :preco_venda_real          (:preco-venda-real custos)

                ;; lucro líquido vai pra margem_lucro
                :margem_lucro              (:lucro-liquido custos)
                :sincronizado              true})]


    ;; Salvar no banco
    (db/criar-impressao! impressao-completa)

    ;; Atualizar estoque de filamento
    (when (= (:status task-data) "success")
      (db/atualizar-estoque-filamento! filamento-id (:peso-usado-g task-data)))

    impressao-completa))


;; ---------------------------------------------------------
;; SINCRONIZAÇÃO COM Bambu Cloud
;; ---------------------------------------------------------

(defn sincronizar-impressoes!
  "Sincroniza todas as impressões do Bambu Cloud que ainda não estão no banco."
  [& {:keys [filamento-id-padrao] :or {filamento-id-padrao nil}}]
  (if-let [token (obter-ou-renovar-token)]
    (let [creds        (db/buscar-bambu-credentials)
          device-id    (:bambu_credentials/device_id creds)
          tasks        (bambu/sync-tasks token device-id)
          config       (db/get-all-configs)
          novas-tasks  (filter
                         (fn [task]
                           (nil? (db/buscar-impressao-por-bambu-id
                                   (:bambu-task-id task))))
                         tasks)
          filamento-id (or filamento-id-padrao
                           (:filamentos/id (first (db/listar-filamentos))))
          resultados   (doall
                         (map (fn [t]
                                (try
                                  (processar-impressao t filamento-id config)
                                  (catch Exception e
                                    (log/error e "Erro ao processar impressão" t)
                                    nil)))
                              novas-tasks))]
      {:success           true
       :total-tasks       (count tasks)
       :novas-sincronizadas (count (filter some? resultados))
       :tasks             resultados})
    {:success false
     :message "Não autenticado. Faça auth na Bambu primeiro (senha + código)."}))

;; ---------------------------------------------------------
;; RECÁLCULO DE CUSTOS
;; ---------------------------------------------------------

(defn recalcular-custos-impressao!
  "Recalcula custos de uma impressão (útil após mudar configurações)."
  [impressao-id]
  (when-let [impressao (db/buscar-impressao impressao-id)]
    (let [config (db/get-all-configs)
          custos (calc/calcular-impressao-completa
                   {:tempo-minutos    (:impressoes/tempo_minutos impressao)
                    :peso-usado-g     (:impressoes/peso_usado_g impressao)
                    :custo-acessorios (:impressoes/custo_acessorios impressao)
                    :preco-venda      (:impressoes/preco_venda impressao)}
                   config)]
      (db/atualizar-impressao!
        impressao-id
        {:custo_filamento           (:custo-filamento custos)
         :custo_energia             (:custo-energia custos)
         :custo_fixo                (:custo-fixo custos)
         :custo_amortizacao         (:custo-amortizacao custos)
         :custo_total               (:custo-total custos)
         :preco_consumidor_sugerido (:preco-consumidor-sugerido custos)
         :preco_lojista_sugerido    (:preco-lojista-sugerido custos)
         :preco_venda_real          (:preco-venda-real custos)
         :margem_lucro              (:lucro-liquido custos)})
      custos)))


;; ---------------------------------------------------------
;; ESTATÍSTICAS GERAIS
;; ---------------------------------------------------------

(defn estatisticas-gerais
  "Retorna estatísticas gerais do sistema."
  []
  (let [config             (db/get-all-configs)
        filamentos         (db/listar-filamentos)
        impressoes-recentes (db/listar-impressoes :limit 10)
        hoje               (java.time.LocalDate/now)
        mes-atual          (.getMonthValue hoje)
        ano-atual          (.getYear hoje)
        relatorio-mes      (db/relatorio-mensal ano-atual mes-atual)]
    {:filamentos {:total          (count filamentos)
                  :estoque-total-g (reduce + (map :filamentos/peso_atual_g filamentos))
                  :valor-estoque  (reduce + (map #(* (:filamentos/peso_atual_g %)
                                                     (/ (:filamentos/preco_compra %)
                                                        (:filamentos/peso_inicial_g %)))
                                                 filamentos))}
     :mes-atual {:ano             ano-atual
                 :mes             mes-atual
                 :total-impressoes (:total_impressoes relatorio-mes)
                 :total-filamento-g (:total_filamento_g relatorio-mes)
                 :total-custos    (:total_custos relatorio-mes)
                 :total-receitas  (:total_receitas relatorio-mes)
                 :total-lucro     (:total_lucro relatorio-mes)
                 :tempo-medio-min (:tempo_medio_min relatorio-mes)}
     :impressoes-recentes impressoes-recentes
     :config config}))
