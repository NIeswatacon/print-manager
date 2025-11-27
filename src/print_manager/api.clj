(ns print-manager.api
  (:require [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.coercion :as coercion]
            [reitit.coercion.spec :as spec-coercion]
            [muuntaja.core :as m]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.cors :refer [wrap-cors]]
            [print-manager.sync-service :as sync]
            [print-manager.database :as db]
            [print-manager.cost-calculator :as calc]
            [clojure.spec.alpha :as s]))

;; Specs para validação de requisições
(s/def ::nome string?)
(s/def ::marca string?)
(s/def ::tipo #{"PLA" "PLA_SILK" "PETG" "ABS" "TPU" "ASA"})
(s/def ::cor string?)
(s/def ::peso-inicial-g pos?)
(s/def ::preco-compra pos?)
(s/def ::email string?)
(s/def ::password string?)

;; Handlers
(defn health-check [_]
  {:status 200
   :body {:status "ok"
          :timestamp (str (java.time.Instant/now))}})

;; Auth endpoints
(defn post-auth-bambu [{{:keys [email password code]} :body-params}]
  (cond
    (and email password)
    (let [result (sync/autenticar-bambu-com-senha! email password)]
      {:status (if (:success result) 200 200) ;; 200 mesmo quando requires-code
       :body   result})

    (and email code)
    (let [result (sync/autenticar-bambu-com-codigo! email code)]
      {:status (if (:success result) 200 401)
       :body   result})

    :else
    {:status 400
     :body {:error "Envie {email, password} para solicitar código ou {email, code} para confirmar."}}))

(defn get-auth-status [_]
  (if-let [creds (db/buscar-bambu-credentials)]
    {:status 200
     :body {:authenticated true
            :email (:bambu_credentials/email creds)
            :device-id (:bambu_credentials/device_id creds)
            :last-updated (:bambu_credentials/updated_at creds)}}
    {:status 200
     :body {:authenticated false}}))

;; Filamentos endpoints
(defn get-filamentos [_]
  {:status 200
   :body (db/listar-filamentos)})

(defn post-filamento
  [{{:keys [nome marca tipo cor peso-inicial-g preco-compra]} :body-params}]
  (try
    (let [filamento (db/criar-filamento!
                      {:nome            nome
                       :marca           marca
                       :tipo            tipo
                       :cor             cor
                       :peso-inicial-g  peso-inicial-g
                       :preco-compra    preco-compra
                       ;; AQUI: Timestamp em vez de Instant
                       :data-compra (java.sql.Timestamp/from (java.time.Instant/now))})]
      {:status 201
       :body   filamento})
    (catch Exception e
      {:status 400
       :body {:erro (.getMessage e)}})))

(defn get-filamento [{{:keys [id]} :path-params}]
  (if-let [filamento (db/buscar-filamento (java.util.UUID/fromString id))]
    {:status 200
     :body filamento}
    {:status 404
     :body {:error "Filamento não encontrado"}}))

(defn delete-filamento [{{:keys [id]} :path-params}]
  (try
    (db/desativar-filamento! (java.util.UUID/fromString id))
    {:status 204
     :body nil}
    (catch Exception e
      {:status 404
       :body {:erro (.getMessage e)}})))

;; Impressões endpoints
(defn get-impressoes [{{:keys [limit offset filamento-id]} :query-params}]
  (let [impressoes (db/listar-impressoes
                     :limit (or limit 100)
                     :offset (or offset 0)
                     :filament-id (when filamento-id
                                    (java.util.UUID/fromString filamento-id)))]
    {:status 200
     :body impressoes}))

(defn get-impressao [{{:keys [id]} :path-params}]
  (if-let [impressao (db/buscar-impressao (java.util.UUID/fromString id))]
    {:status 200
     :body impressao}
    {:status 404
     :body {:erro "Impressão não encontrada"}}))

(defn post-sincronizar [_]
  (let [result (sync/sincronizar-impressoes!)]
    {:status 200
     :body result}))

(defn put-impressao-preco [request]
  (let [id          (get-in request [:path-params :id])
        preco-venda (get-in request [:body-params :preco-venda])]
    (try
      (db/atualizar-impressao! (java.util.UUID/fromString id)
                               {:preco_venda preco-venda})
      (let [custos (sync/recalcular-custos-impressao!
                     (java.util.UUID/fromString id))]
        {:status 200
         :body   custos})
      (catch Exception e
        {:status 400
         :body {:error (.getMessage e)}}))))

;; Configuração de endpoints
(defn get-configuracoes [_]
  {:status 200
   :body (db/get-all-configs)})

(defn put-configuracao [{{:keys [chave]} :path-params
                         {:keys [valor]} :body-params}]
  (try
    (db/update-config! chave valor)
    {:status 200
     :body {:chave chave :valor valor}}
    (catch Exception e
      {:status 400
       :body {:error (.getMessage e)}})))

;; Relatórios endpoints
(defn get-relatorio-mensal [{{:keys [ano mes]} :query-params}]
  (let [ano (or ano (.getYear (java.time.LocalDate/now)))
        mes (or mes (.getMonthValue (java.time.LocalDate/now)))]
    {:status 200
     :body (db/relatorio-mensal ano mes)}))

(defn get-relatorio-filamento [{{:keys [id]} :path-params}]
  {:status 200
   :body (db/relatorio-por-filamento (java.util.UUID/fromString id))})

(defn get-top-lucrativos [{{:keys [n]} :query-params}]
  {:status 200
   :body (db/top-impressoes-lucrativas (or n 10))})

(defn get-estatisticas [_]
  {:status 200
   :body (sync/estatisticas-gerais)})

;; Calculadora endpoints
(defn post-simular-custo [{{:keys [tempo-minutos peso-usado-g preco-venda]} :body-params}]
  (let  [config (db/get-all-configs)
         custos (calc/calcular-impressao-completa
                  {:tempo-minutos tempo-minutos
                   :peso-usado-g peso-usado-g
                   :preco-venda preco-venda}
                  config)]
    {:status 200
     :body custos}))

;; Rotas
(defn routes []
  ["/api"
   ["/health"
    {:get {:handler health-check}}]

   ["/auth"
    ["/bambu"
     {:post {:parameters {:body (s/or :senha  (s/keys :req-un [::email ::password])
                                      :codigo (s/keys :req-un [::email]
                                                      :opt-un [::password]))}
             :handler post-auth-bambu}}]
    ["/status"
     {:get {:handler get-auth-status}}]]

   ["/filamentos"
    ["" {:get  {:handler get-filamentos}
         :post {:parameters {:body {:nome           string?
                                    :marca          string?
                                    :tipo           string?
                                    :cor            string?
                                    :peso-inicial-g number?
                                    :preco-compra   number?}}
                :handler    post-filamento}}]
    ["/:id"
     {:get    {:handler get-filamento}
      :delete {:handler delete-filamento}}]
    ["/:id/relatorio"
     {:get {:handler get-relatorio-filamento}}]]

   ["/impressoes"
    ["" {:get {:handler get-impressoes}}]
    ["/sincronizar"
     {:post {:handler post-sincronizar}}]
    ["/top-lucrativas"
     {:get {:handler get-top-lucrativos}}]
    ["/detalhe/:id"
     {:get {:handler get-impressao}}]
    ["/detalhe/:id/preco"
     {:put {:parameters {:body {:preco-venda number?}}
            :handler    put-impressao-preco}}]]

   ["/configuracoes"
    ["" {:get {:handler get-configuracoes}}]
    ["/:chave"
     {:put {:parameters {:body {:valor string?}}
            :handler    put-configuracao}}]]

   ["/relatorios"
    ["/mensal"
     {:get {:handler get-relatorio-mensal}}]
    ["/estatisticas"
     {:get {:handler get-estatisticas}}]]

   ["/calculadora"
    ["/simular"
     {:post {:parameters {:body {:tempo-minutos number?
                                 :peso-usado-g  number?
                                 :preco-venda   number?}}
             :handler    post-simular-custo}}]]])

;; Middleware
(defn wrap-exception [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        {:status 500
         :body {:erro    "Internal server error"
                :message (.getMessage e)}}))))

;; App
(def app
  (ring/ring-handler
    (ring/router
      (routes)
      {:data {:coercion  spec-coercion/coercion
              :muuntaja  m/instance
              :middleware [muuntaja/format-middleware
                           coercion/coerce-exceptions-middleware
                           coercion/coerce-request-middleware
                           coercion/coerce-response-middleware
                           wrap-exception]}})
    (ring/routes
      (ring/create-default-handler
        {:not-found (constantly {:status 404 :body {:error "Not found"}})}))))

;; CORS wrapper
(def app-with-cors
  (wrap-cors app
             :access-control-allow-origin [#".*"]
             :access-control-allow-methods [:get :post :put :delete :options]
             :access-control-allow-headers ["Content-Type" "Authorization"]))

;; Server
(defonce server (atom nil))

(defn start-server! [& {:keys [port] :or {port 3000}}]
  (when-let [s @server]
    (.stop s))
  (reset! server (jetty/run-jetty #'app-with-cors
                                  {:port port
                                   :join? false}))
  (println (str "Server running on http://Localhost:" port)))

(defn stop-server! []
  (when-let [s @server]
    (.stop s)
    (reset! server nil)
    (println "Server stopped")))

(comment
  ;; Iniciar servidor
  (start-server! :port 3000)

  ;; Parar servidor
  (stop-server!)

  ;; Testar endpoints:
  ;; curl http://localhost:3000/api/health
  ;; curl -X POST http://localhost:3000/api/auth/bambu -H "Content-Type: application/json" -d '{"email":"seu@email.com","password":"senha"}'
  ;; curl http://localhost:3000/api/filamentos
  ;; curl http://localhost:3000/api/estatisticas
  )
