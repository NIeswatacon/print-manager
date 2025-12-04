(ns print-manager.sync-service
  (:require [print-manager.bambu-api :as bambu]
            [print-manager.database :as db]
            [print-manager.cost-calculator :as calc]
            [clojure.tools.logging :as log]))

;; ---------------------------------------------------------
;; TOKEN
;; ---------------------------------------------------------

(defn obter-ou-renovar-token
  []
  (when-let [creds (db/buscar-bambu-credentials)]
    (:bambu_credentials/access_token creds)))

;; ---------------------------------------------------------
;; AUTENTICAÇÃO
;; ---------------------------------------------------------

(defn autenticar-bambu-com-senha!
  [email password]
  (let [resp (bambu/login-password email password)]
    (cond
      (nil? resp)
      {:success false :message "Falha na comunicação com a Bambu Cloud."}

      (= "verifyCode" (:loginType resp))
      {:success false :requires-code true :message "Código enviado para o e-mail."}

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
        {:success true :message "Autenticado com sucesso" :device-id device-id})

      :else
      {:success false :message (str "Erro inesperado: " (pr-str resp))})))

(defn autenticar-bambu-com-codigo!
  [email code]
  (let [resp (bambu/login-code email code)]
    (cond
      (nil? resp)
      {:success false :message "Falha ao validar código."}

      (not (:accessToken resp))
      {:success false :message (str "Código inválido. " (pr-str resp))}

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
        {:success true :message "Autenticado com sucesso" :device-id device-id}))))

;; ---------------------------------------------------------
;; PROCESSAMENTO (COM LÓGICA DE ATUALIZAÇÃO)
;; ---------------------------------------------------------

(defn processar-impressao
  "Processa e salva/atualiza uma impressão"
  [task-data filamento-id config]
  (let [;; Tenta buscar impressão existente
         impressao-existente (db/buscar-impressao-por-bambu-id (:bambu-task-id task-data))

         ;; Verifica se status mudou
         status-mudou? (and impressao-existente
                            (not= (:impressoes/status impressao-existente) (:status task-data)))]

    ;; Se existe e status é igual, ignora (retorna nil)
    (if (and impressao-existente (not status-mudou?))
      nil

      ;; Se é nova OU status mudou, processa
      (let [filamento     (db/buscar-filamento filamento-id)
            custo-por-kg  (or (:filamentos/custo_por_kg filamento)
                              (:custo_por_kg filamento)
                              (:custo-por-kg filamento))

            tempo-minutos (or (:tempo-minutos task-data) 0)
            peso-usado-g  (or (:peso-usado-g task-data) 0)

            ;; Calcula custos (protegido contra nils)
            custos (if (and (pos? tempo-minutos) (pos? peso-usado-g) (pos? (or custo-por-kg 0)))
                     (calc/calcular-impressao-completa
                      {:tempo-minutos tempo-minutos :peso-usado-g peso-usado-g}
                      (assoc config :custo-por-kg custo-por-kg))
                     {:custo-total 0M :lucro-liquido 0M :preco-venda-real 0M :custo-filamento 0M
                      :custo-energia 0M :custo-fixo 0M :custo-amortizacao 0M
                      :preco-consumidor-sugerido 0M :preco-lojista-sugerido 0M})

            base-data (merge {:bambu_task_id (:bambu-task-id task-data)
                              :nome          (:nome task-data)
                              :filamento_id  filamento-id
                              :data_inicio   (:data-inicio task-data)
                              :data_fim      (:data-fim task-data)
                              :tempo_minutos tempo-minutos
                              :peso_usado_g  peso-usado-g
                              :status        (:status task-data)
                              :sincronizado  true}
                             ;; Mapeamento corrigido dos custos
                             {:custo_filamento           (:custo-filamento custos)
                              :custo_energia             (:custo-energia custos)
                              :custo_fixo                (:custo-fixo custos)  ;; <--- CORRIGIDO: UNDERLINE _
                              :custo_amortizacao         (:custo-amortizacao custos)
                              :custo_total               (:custo-total custos)
                              :preco_consumidor_sugerido (:preco-consumidor-sugerido custos)
                              :preco_lojista_sugerido    (:preco-lojista-sugerido custos)
                              :preco_venda               (:preco-venda-real custos)
                              :preco_venda_real          (:preco-venda-real custos)
                              :margem_lucro              (:lucro-liquido custos)})]

        (if impressao-existente
          ;; ATUALIZAR
          (do
            (log/info "Atualizando status:" (:nome task-data) "->" (:status task-data))
            (db/atualizar-impressao! (:impressoes/id impressao-existente) base-data)
            base-data)

          ;; CRIAR NOVA
          (do
            (db/criar-impressao! base-data)
            ;; Só baixa stock se for sucesso
            (when (and (= (:status task-data) "success") (pos? peso-usado-g))
              (db/atualizar-estoque-filamento! filamento-id peso-usado-g))
            base-data))))))

(defn sincronizar-impressoes!
  [& {:keys [filamento-id-padrao] :or {filamento-id-padrao nil}}]
  (if-let [token (obter-ou-renovar-token)]
    (let [creds        (db/buscar-bambu-credentials)
          device-id    (:bambu_credentials/device_id creds)
          tasks        (bambu/sync-tasks token device-id)
          config       (db/get-all-configs)

          filamento-id (or filamento-id-padrao
                           (let [f (first (db/listar-filamentos))]
                             (or (:filamentos/id f) (:id f))))

          resultados   (doall
                        (map (fn [t]
                               (try
                                 (if filamento-id
                                   (processar-impressao t filamento-id config)
                                   (throw (ex-info "Sem filamento." {})))
                                 (catch Exception e
                                   (log/error "Erro task:" (:bambu-task-id t) (.getMessage e))
                                   nil)))
                             tasks))]
      {:success true
       :total-tasks (count tasks)
       :processadas (count (filter some? resultados))})
    {:success false :message "Não autenticado."}))

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