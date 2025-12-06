(ns print-manager.sync-service
  (:require [print-manager.bambu-api :as bambu]
            [print-manager.database :as db]
            [print-manager.cost-calculator :as calc]
            [clojure.tools.logging :as log]))

;; --- TOKEN & AUTH ---

(defn obter-ou-renovar-token []
  (when-let [creds (db/buscar-bambu-credentials)]
    (:bambu_credentials/access_token creds)))

(defn autenticar-bambu-com-senha! [email password]
  (let [resp (bambu/login-password email password)]
    (cond
      (nil? resp) {:success false :message "Erro de comunicação"}
      (= "verifyCode" (:loginType resp)) {:success false :requires-code true :message "Código enviado"}
      (:accessToken resp) (do (db/salvar-bambu-credentials! {:email email :access-token (:accessToken resp) :refresh-token (:refreshToken resp) :token-expiry (.plusSeconds (java.time.Instant/now) (long (or (:expiresIn resp) 0))) :device-id (:dev_id (first (bambu/get-device-list (:accessToken resp))))}) {:success true :message "Autenticado"})
      :else {:success false :message (str "Erro: " (pr-str resp))})))

(defn autenticar-bambu-com-codigo! [email code]
  (let [resp (bambu/login-code email code)]
    (if (:accessToken resp)
      (do (db/salvar-bambu-credentials! {:email email :access-token (:accessToken resp) :refresh-token (:refreshToken resp) :token-expiry (.plusSeconds (java.time.Instant/now) (long (or (:expiresIn resp) 0))) :device-id (:dev_id (first (bambu/get-device-list (:accessToken resp))))}) {:success true :message "Autenticado"})
      {:success false :message "Código inválido"})))

;; --- PROCESSAMENTO ---

(defn recalcular-custos-impressao! [id]
  (when-let [impressao (db/buscar-impressao id)]
    (let [config (db/get-all-configs)
          custos (calc/calcular-impressao-completa
                  {:tempo-minutos    (:impressoes/tempo_minutos impressao)
                   :peso-usado-g     (:impressoes/peso_usado_g impressao)
                   :preco-venda      (:impressoes/preco_venda impressao)}
                  config)]
      (db/atualizar-impressao! id
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

(defn processar-impressao [task-data filamento-id config]
  (let [existente (db/buscar-impressao-por-bambu-id (:bambu-task-id task-data))]
    (if (and existente (= (:impressoes/status existente) (:status task-data)))
      nil
      (let [fil (db/buscar-filamento filamento-id)
            custo-kg (or (:filamentos/custo_por_kg fil) 0)
            t (or (:tempo-minutos task-data) 0)
            p (or (:peso-usado-g task-data) 0)
            custos (if (and (pos? t) (pos? p))
                     (calc/calcular-impressao-completa {:tempo-minutos t :peso-usado-g p} (assoc config :custo-por-kg custo-kg))
                     {:custo-total 0M :lucro-liquido 0M :preco-venda-real 0M :custo-filamento 0M})
            base (merge {:bambu_task_id (:bambu-task-id task-data) :nome (:nome task-data) :filamento_id filamento-id
                         :data_inicio (:data-inicio task-data) :data_fim (:data-fim task-data) :tempo_minutos t :peso_usado_g p
                         :status (:status task-data) :sincronizado true}
                        {:custo_filamento (:custo-filamento custos) :custo_energia (:custo-energia custos) :custo_fixo (:custo-fixo custos)
                         :custo_amortizacao (:custo-amortizacao custos) :custo_total (:custo-total custos)
                         :preco_consumidor_sugerido (:preco-consumidor-sugerido custos) :preco_lojista_sugerido (:preco-lojista-sugerido custos)
                         :preco_venda (:preco-venda-real custos) :preco_venda_real (:preco-venda-real custos) :margem_lucro (:lucro-liquido custos)})]
        (if existente
          (do (log/info "Update status:" (:status task-data)) (db/atualizar-impressao! (:impressoes/id existente) base) base)
          (do (db/criar-impressao! base)
            (when (and (= (:status task-data) "success") (pos? p)) (db/atualizar-estoque-filamento! filamento-id p))
            base))))))

(defn sincronizar-impressoes! []
  (if-let [token (obter-ou-renovar-token)]
    (let [creds (db/buscar-bambu-credentials)
          tasks (bambu/sync-tasks token (:bambu_credentials/device_id creds))
          config (db/get-all-configs)
          fid (or (:filamentos/id (first (db/listar-filamentos))) (:id (first (db/listar-filamentos))))
          res (doall (map #(try (if fid (processar-impressao % fid config) (throw (Exception. "Sem filamento"))) (catch Exception e (log/error e) nil)) tasks))]
      {:success true :total (count tasks) :processadas (count (filter some? res))})
    {:success false :message "Não autenticado"}))

(defn recalcular-todas-impressoes! []
  (let [todas (db/listar-impressoes :limit 10000)
        total (count todas)]
    (log/info "♻️ Iniciando recálculo seguro de" total "impressões...")
    (let [resultados (doall
                      (map (fn [imp]
                             (try
                               (let [id (or (:impressoes/id imp) (:id imp))]
                                 (if id
                                   (do (recalcular-custos-impressao! id) :sucesso)
                                   :ignorado))
                               (catch Exception e
                                 (log/error "Erro ID:" (:id imp) (.getMessage e))
                                 :erro)))
                           todas))]
      {:success true :total total
       :recalculadas (count (filter #(= % :sucesso) resultados))
       :falhas (count (filter #(= % :erro) resultados))})))

(defn estatisticas-gerais []
  (let [fil (db/listar-filamentos)]
    {:filamentos {:total (count fil) :estoque-total-g (reduce + (map :filamentos/peso_atual_g fil))}}))