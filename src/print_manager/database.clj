(ns print-manager.database
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [honey.sql :as hsql]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.time Instant)
           (java.sql Timestamp)))

;; Configuração do banco
(def db-spec
  {:dbtype   "postgresql"
   :dbname   "print_manager"
   :host     "localhost"
   :port     5432
   :user     "postgres"
   :password "0774"})

(def datasource (jdbc/get-datasource db-spec))

;; Helpers genéricos
(defn query [sql-map]
  (jdbc/execute! datasource (hsql/format sql-map)))

(defn query-one [sql-map]
  (jdbc/execute-one! datasource (hsql/format sql-map)))

(defn insert! [table data]
  (sql/insert! datasource table data {:return-keys true}))

(defn update! [table data where-clause]
  (sql/update! datasource table data where-clause))

(defn delete! [table where-clause]
  (sql/delete! datasource table where-clause))

(defn to-sql-timestamp [inst]
  (when inst
    (cond
      (instance? Timestamp inst) inst
      (instance? Instant inst)   (Timestamp/from inst)
      :else                      inst)))

;; --- CONFIGURAÇÕES ---

(defn get-all-configs []
  (let [configs (query {:select [:chave :valor :tipo] :from [:configuracoes]})]
    (reduce (fn [acc {:configuracoes/keys [chave valor tipo]}]
              (let [kw (keyword (str/replace chave "_" "-"))]
                (assoc acc kw
                       (case tipo
                         "integer" (parse-long valor)
                         "decimal" (bigdec valor)
                         "json"    (json/parse-string valor true)
                         valor))))
            {}
            configs)))

(defn update-config! [chave novo-valor]
  (let [chave-db (str/replace (name chave) "-" "_")]
    (update! :configuracoes
             {:valor (str novo-valor)}
             ["chave = ?" chave-db])))

;; --- FILAMENTOS ---

(defn criar-filamento!
  [{:keys [nome marca tipo cor peso-inicial-g preco-compra data-compra]}]
  (insert! :filamentos
           {:nome           nome
            :marca          marca
            :tipo           tipo
            :cor            cor
            :peso_inicial_g peso-inicial-g
            :peso_atual_g   peso-inicial-g
            :preco_compra   preco-compra
            :data_compra    (to-sql-timestamp (or data-compra (Instant/now)))}))

(defn listar-filamentos []
  (query {:select [:*] :from [:filamentos] :where [:= :ativo true] :order-by [[:created_at :desc]]}))

(defn buscar-filamento [id]
  (query-one {:select [:*] :from [:filamentos] :where [:= :id id]}))

(defn atualizar-estoque-filamento! [id peso-usado]
  (jdbc/execute-one! datasource
                     ["UPDATE filamentos SET peso_atual_g = peso_atual_g - ? WHERE id = ? RETURNING *"
                      peso-usado id]))

(defn desativar-filamento! [id]
  (update! :filamentos {:ativo false} ["id = ?" id]))

;; --- IMPRESSÕES ---

(defn criar-impressao! [dados]
  (insert! :impressoes
           (-> dados
               (update :data_inicio to-sql-timestamp)
               (update :data_fim to-sql-timestamp))))

(defn listar-impressoes [& {:keys [limit offset filamento-id] :or {limit 100 offset 0}}]
  (let [q {:select [:i.* [:f.nome :filamento_nome] [:f.marca :filamento_marca] [:f.cor :filamento_cor]]
           :from [[:impressoes :i]]
           :left-join [[:filamentos :f] [:= :i.filamento_id :f.id]]
           :order-by [[:i.data_inicio :desc]]
           :limit limit :offset offset}]
    (query (if filamento-id (assoc q :where [:= :i.filamento_id filamento-id]) q))))

(defn buscar-impressao [id]
  (query-one {:select [:*] :from [:impressoes] :where [:= :id id]}))

(defn buscar-impressao-por-bambu-id [bid]
  (query-one {:select [:*] :from [:impressoes] :where [:= :bambu_task_id (str bid)]}))

(defn atualizar-impressao! [id updates]
  (update! :impressoes updates ["id = ?" id]))

;; --- RELATÓRIOS (HoneySQL v2 Sintaxe) ---

(defn relatorio-periodo [inicio fim]
  (query-one
   {:select [[[:count :*] :total_impressoes]
             [[:sum :peso_usado_g] :total_filamento_g]
             [[:sum :custo_total] :total_custos]
             [[:sum :preco_venda_real] :total_receitas]
             [[:sum :margem_lucro] :total_lucro]
             [[:avg :tempo_minutos] :tempo_medio_min]]
    :from   [:impressoes]
    :where  [:and
             [:>= :data_inicio (to-sql-timestamp inicio)]
             [:<= :data_inicio (to-sql-timestamp fim)]
             [:in :status ["success" "Success" "finish" "2"]]]}))

(defn top-lucrativas-periodo [n inicio fim]
  (query {:select   [:nome :preco_venda_real :margem_lucro :data_inicio :status]
          :from     [:impressoes]
          :where    [:and
                     [:>= :data_inicio (to-sql-timestamp inicio)]
                     [:<= :data_inicio (to-sql-timestamp fim)]
                     [:in :status ["success" "Success" "finish" "2"]]]
          :order-by [[:margem_lucro :desc]]
          :limit    n}))

(defn relatorio-mensal [ano mes]
  (let [inicio (str ano "-" (format "%02d" mes) "-01")
        fim    (str ano "-" (format "%02d" (inc mes)) "-01")]
    (relatorio-periodo (Timestamp/valueOf (str inicio " 00:00:00"))
                       (Timestamp/valueOf (str fim " 00:00:00")))))

(defn relatorio-por-filamento [id]
  (query-one {:select [[[:count :*] :total_impressoes] [[:sum :peso_usado_g] :total_usado_g] [[:sum :custo_filamento] :total_gasto]]
              :from [:impressoes] :where [:and [:= :filamento_id id] [:in :status ["success" "2"]]]}))

(defn top-impressoes-lucrativas [n]
  (query {:select [:nome :preco_venda :margem_lucro :data_inicio] :from [:impressoes]
          :where [:in :status ["success" "2"]] :order-by [[:margem_lucro :desc]] :limit n}))

;; --- CREDENCIAIS ---

(defn salvar-bambu-credentials! [dados]
  (let [dados (update dados :token-expiry to-sql-timestamp)]
    (jdbc/execute-one! datasource
                       ["INSERT INTO bambu_credentials (email, access_token, refresh_token, token_expiry, device_id, updated_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP) RETURNING *"
                        (:email dados) (:access-token dados) (:refresh-token dados) (:token-expiry dados) (:device-id dados)])))

(defn buscar-bambu-credentials []
  (query-one {:select [:*] :from [:bambu_credentials] :order-by [[:updated_at :desc]] :limit 1}))