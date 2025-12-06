(ns print-manager.api
  (:require [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.ring.coercion :as coercion]
            [reitit.coercion.spec :as spec-coercion]
            [muuntaja.core :as m]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.cors :refer [wrap-cors]]
            [print-manager.sync-service :as sync]
            [print-manager.database :as db]
            [print-manager.cost-calculator :as calc]
            [print-manager.finance :as fin]
            [clojure.spec.alpha :as s])
  (:import (java.math BigDecimal RoundingMode)
           (java.sql Timestamp)))

;; --- SPECS ---
(s/def ::nome string?)
(s/def ::marca string?)
(s/def ::tipo string?)
(s/def ::cor string?)
(s/def ::peso-inicial-g number?)
(s/def ::preco-compra number?)
(s/def ::email string?)
(s/def ::password string?)
(s/def ::inicio string?)
(s/def ::fim string?)
(s/def ::valor number?)
(s/def ::id string?)

;; --- HELPERS ---
(defn round-2 [v]
  (when v
    (cond
      (instance? BigDecimal v) (.setScale ^BigDecimal v 2 RoundingMode/HALF_UP)
      (number? v) (.setScale (BigDecimal/valueOf (double v)) 2 RoundingMode/HALF_UP)
      :else v)))

(defn format2 [m k] (update m k round-2))

(defn format-impressao [row]
  (-> row
      (format2 :impressoes/custo_filamento)
      (format2 :impressoes/custo_energia)
      (format2 :impressoes/custo_fixo)
      (format2 :impressoes/custo_amortizacao)
      (format2 :impressoes/custo_total)
      (format2 :impressoes/preco_consumidor_sugerido)
      (format2 :impressoes/preco_lojista_sugerido)
      (format2 :impressoes/preco_venda_real)
      (format2 :impressoes/margem_lucro)))

;; --- HANDLERS ---

(defn health-check [_] {:status 200 :body {:status "ok"}})

(defn post-auth-bambu [{{:keys [email password code]} :body-params}]
  (cond
    (and email password) {:status 200 :body (sync/autenticar-bambu-com-senha! email password)}
    (and email code) {:status 200 :body (sync/autenticar-bambu-com-codigo! email code)}
    :else {:status 400 :body {:error "Dados inválidos"}}))

(defn get-auth-status [_]
  (if-let [creds (db/buscar-bambu-credentials)]
    {:status 200 :body {:authenticated true :email (:bambu_credentials/email creds)}}
    {:status 200 :body {:authenticated false}}))

;; Financeiro
(defn get-finance-saldo [_] {:status 200 :body {:saldo (fin/get-saldo)}})
(defn post-finance-saldo-inicial [{{:keys [valor]} :body-params}] {:status 200 :body {:saldo (fin/definir-saldo-inicial! valor)}})
(defn post-venda-impressao [{{:keys [id valor nome]} :body-params}]
  (let [tx (fin/registrar-transacao! :receita valor (str "Venda: " nome))]
    {:status 200 :body tx}))
(defn get-finance-extrato [_] {:status 200 :body (fin/get-extrato)})

;; Filamentos
(defn get-filamentos [_] {:status 200 :body (db/listar-filamentos)})
(defn post-filamento [{{:keys [nome marca tipo cor peso-inicial-g preco-compra]} :body-params}]
  (try
    (let [filamento (db/criar-filamento! {:nome nome :marca marca :tipo tipo :cor cor :peso-inicial-g peso-inicial-g :preco-compra preco-compra})]
      (fin/registrar-transacao! :despesa preco-compra (str "Compra Filamento: " nome))
      {:status 201 :body filamento})
    (catch Exception e {:status 400 :body {:erro (.getMessage e)}})))

(defn get-filamento [{{:keys [id]} :path-params}]
  (if-let [f (db/buscar-filamento (java.util.UUID/fromString id))] {:status 200 :body f} {:status 404}))
(defn delete-filamento [{{:keys [id]} :path-params}]
  (db/desativar-filamento! (java.util.UUID/fromString id)) {:status 204 :body nil})
(defn get-relatorio-filamento [{{:keys [id]} :path-params}]
  {:status 200 :body (db/relatorio-por-filamento (java.util.UUID/fromString id))})

;; Impressoes
(defn get-impressoes [{{:keys [limit offset]} :query-params}]
  {:status 200 :body (map format-impressao (db/listar-impressoes :limit (or limit 100) :offset (or offset 0)))})
(defn get-impressao [{{:keys [id]} :path-params}]
  (if-let [imp (db/buscar-impressao (java.util.UUID/fromString id))] {:status 200 :body (format-impressao imp)} {:status 404}))
(defn post-sincronizar [_] {:status 200 :body (sync/sincronizar-impressoes!)})
(defn put-impressao-preco [request]
  (let [id (get-in request [:path-params :id]) preco (get-in request [:body-params :preco-venda])]
    (db/atualizar-impressao! (java.util.UUID/fromString id) {:preco_venda preco :preco_venda_real preco})
    {:status 200 :body (sync/recalcular-custos-impressao! (java.util.UUID/fromString id))}))

;; Configs
(defn get-configuracoes [_] {:status 200 :body (db/get-all-configs)})
(defn put-configuracao [{{:keys [chave]} :path-params {:keys [valor]} :body-params}]
  (db/update-config! chave valor) {:status 200 :body {:chave chave :valor valor}})

;; Relatórios
(defn get-relatorio-mensal [{{:keys [ano mes]} :query-params}]
  {:status 200 :body (db/relatorio-mensal (or ano 2025) (or mes 1))})

(defn get-relatorio-custom [request]
  (let [{:keys [inicio fim]} (get-in request [:parameters :query])]
    (println "📊 Relatório:" inicio "até" fim)
    (try
      (let [ts-inicio (Timestamp/valueOf (str inicio " 00:00:00"))
            ts-fim    (Timestamp/valueOf (str fim " 23:59:59"))
            stats     (db/relatorio-periodo ts-inicio ts-fim)
            top10     (db/top-lucrativas-periodo 10 ts-inicio ts-fim)]
        {:status 200 :body {:estatisticas stats :top_lucrativas (map format-impressao top10)}})
      (catch Exception e
        (println "❌ Erro:" (.getMessage e))
        {:status 400 :body {:erro "Datas inválidas" :detalhe (.getMessage e)}}))))

(defn get-estatisticas [_] {:status 200 :body (sync/estatisticas-gerais)})
(defn post-simular-custo [{{:keys [tempo-minutos peso-usado-g preco-venda]} :body-params}]
  {:status 200 :body (calc/calcular-impressao-completa {:tempo-minutos tempo-minutos :peso-usado-g peso-usado-g :preco-venda preco-venda} (db/get-all-configs))})

;; --- ROTAS ---

(defn routes []
  ["/api"
   ["/health" {:get {:handler health-check}}]
   ["/auth" ["/bambu" {:post {:handler post-auth-bambu}}] ["/status" {:get {:handler get-auth-status}}]]

   ["/financeiro"
    ["/saldo" {:get {:handler get-finance-saldo} :post {:parameters {:body {:valor number?}} :handler post-finance-saldo-inicial}}]
    ["/venda" {:post {:parameters {:body {:id string? :valor number? :nome string?}} :handler post-venda-impressao}}]
    ["/extrato" {:get {:handler get-finance-extrato}}]]

   ["/filamentos"
    ["" {:get {:handler get-filamentos} :post {:parameters {:body {:nome string? :marca string? :tipo string? :cor string? :peso-inicial-g number? :preco-compra number?}} :handler post-filamento}}]
    ["/:id" {:get {:handler get-filamento} :delete {:handler delete-filamento}}]
    ["/:id/relatorio" {:get {:handler get-relatorio-filamento}}]]

   ["/impressoes"
    ["" {:get {:handler get-impressoes}}]
    ["/sincronizar" {:post {:handler post-sincronizar}}]
    ["/recalcular-tudo" {:post {:handler (fn [_] {:status 200 :body (sync/recalcular-todas-impressoes!)})}}]
    ["/detalhe/:id" {:get {:handler get-impressao}}]
    ["/detalhe/:id/preco" {:put {:handler put-impressao-preco}}]]

   ["/configuracoes" ["" {:get {:handler get-configuracoes}}] ["/:chave" {:put {:handler put-configuracao}}]]

   ["/relatorios"
    ["/mensal" {:get {:handler get-relatorio-mensal}}]
    ["/custom" {:get {:parameters {:query {:inicio string? :fim string?}}
                      :handler get-relatorio-custom}}]
    ["/estatisticas" {:get {:handler get-estatisticas}}]]

   ["/calculadora/simular" {:post {:handler post-simular-custo}}]])

;; --- SERVER ---

(defn wrap-exception [handler]
  (fn [request] (try (handler request) (catch Exception e {:status 500 :body {:erro "Erro interno" :msg (.getMessage e)}}))))

(def app
  (ring/ring-handler
   (ring/router (routes)
                {:data {:coercion spec-coercion/coercion
                        :muuntaja m/instance
                        :middleware [parameters/parameters-middleware ;; Essencial!
                                     muuntaja/format-middleware
                                     coercion/coerce-exceptions-middleware
                                     coercion/coerce-request-middleware
                                     coercion/coerce-response-middleware
                                     wrap-exception]}})
   (ring/create-default-handler {:not-found (constantly {:status 404 :body "Not found"})})))

(def app-with-cors
  (wrap-cors app :access-control-allow-origin [#".*"] :access-control-allow-methods [:get :post :put :delete] :access-control-allow-headers ["Content-Type" "Authorization"]))

(defonce server (atom nil))
(defn start-server! [& {:keys [port] :or {port 3000}}]
  (when-let [s @server] (.stop s))
  (reset! server (jetty/run-jetty app-with-cors {:port port :join? false}))
  (println ">>> BACKEND ON http://localhost:" port))