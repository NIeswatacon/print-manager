(ns print-manager.database
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [honey.sql :as hsql]
            [cheshire.core :as json]
            [honey.sql.helpers :as h])
  (:import (java.time Instant)
           (java.sql Timestamp)))

;; Configuração do banco
(def db-spec
  {:dbtype   "postgresql"
   :dbname   "print_manager"
   :host     "localhost"
   :port     5432
   :user     "postgres"
   :password "0508"})

(def datasource (jdbc/get-datasource db-spec))

;; Helpers genéricos

(defn query
  "Executa query HoneySQL e retorna resultados"
  [sql-map]
  (jdbc/execute! datasource (hsql/format sql-map)))

(defn query-one
  "Executa query e retorna primeiro resultado"
  [sql-map]
  (jdbc/execute-one! datasource (hsql/format sql-map)))

(defn insert!
  "Insere registro e retorna o registro inserido"
  [table data]
  (sql/insert! datasource table data {:return-keys true}))

(defn update!
  "Atualiza registro(s)"
  [table data where-clause]
  (sql/update! datasource table data where-clause))

(defn delete!
  "Deleta registro(s)"
  [table where-clause]
  (sql/delete! datasource table where-clause))

;; Helper para datas

(defn to-sql-timestamp
  "Converte java.time.Instant (ou nil) para java.sql.Timestamp."
  [inst]
  (when inst
    (cond
      (instance? Timestamp inst) inst
      (instance? Instant inst)   (Timestamp/from inst)
      :else                      inst)))

;; Configurações

(defn get-config
  "Busca valor de uma configuração"
  [chave]
  (:configuracoes/valor
    (query-one
      {:select [:valor]
       :from   [:configuracoes]
       :where  [:= :chave chave]})))

(defn get-all-configs
  "Retorna todas as configurações como mapa"
  []
  (let [configs (query {:select [:chave :valor :tipo]
                        :from   [:configuracoes]})]
    (reduce (fn [acc {:configuracoes/keys [chave valor tipo]}]
              (assoc acc
                (keyword chave)
                (case tipo
                  "integer" (parse-long valor)
                  "decimal" (bigdec valor)
                  "json"    (json/parse-string valor true)
                  valor)))
            {}
            configs)))

(defn update-config!
  "Atualiza valor de uma configuração"
  [chave novo-valor]
  (update! :configuracoes
           {:valor (str novo-valor)}
           ["chave = ?" chave]))

;; Filamentos

(defn criar-filamento!
  "Cria novo filamento no estoque"
  [{:keys [nome marca tipo cor peso-inicial-g preco-compra data-compra]}]
  (insert! :filamentos
           {:nome           nome
            :marca          marca
            :tipo           tipo
            :cor            cor
            :peso_inicial_g peso-inicial-g
            :peso_atual_g   peso-inicial-g
            :preco_compra   preco-compra
            ;; Sempre salvar como TIMESTAMP no banco
            :data_compra    (to-sql-timestamp (or data-compra (Instant/now)))}))

(defn listar-filamentos
  "Lista todos os filamentos ativos"
  []
  (query {:select   [:*]
          :from     [:filamentos]
          :where    [:= :ativo true]
          :order-by [[:created_at :desc]]}))

(defn buscar-filamento
  "Busca filamento por ID"
  [id]
  (query-one {:select [:*]
              :from   [:filamentos]
              :where  [:= :id id]}))

(defn atualizar-estoque-filamento!
  "Atualiza quantidade de filamento após impressão"
  [filamento-id peso-usado-g]
  (jdbc/execute-one!
    datasource
    ["UPDATE filamentos
      SET peso_atual_g = peso_atual_g - ?
      WHERE id = ?
      RETURNING *"
     peso-usado-g
     filamento-id]))

(defn desativar-filamento!
  "Marca filamento como inativo (acabou)"
  [id]
  (update! :filamentos
           {:ativo false}
           ["id = ?" id]))

;; Impressões

(defn criar-impressao!
  "Cria registro de impressão com todos os custos calculados"
  [impressao-data]
  (let [data (-> impressao-data
                 (update :data_inicio to-sql-timestamp)
                 (update :data_fim    to-sql-timestamp))]
    (insert! :impressoes data)))

(defn listar-impressoes
  "Lista impressões com filtros opcionais"
  [& {:keys [limit offset filamento-id data-inicio data-fim]
      :or   {limit 100 offset 0}}]
  (let [base-query {:select   [:i.*
                               [:f.nome :filamento_nome]
                               [:f.marca :filamento_marca]
                               [:f.cor :filamento_cor]]
                    :from     [[:impressoes :i]]
                    :left-join [[:filamentos :f] [:= :i.filamento_id :f.id]]
                    :order-by [[:i.data_inicio :desc]]
                    :limit    limit
                    :offset   offset}
        conditions (cond-> []
                           filamento-id (conj [:= :i.filamento_id filamento-id])
                           data-inicio  (conj [:>= :i.data_inicio data-inicio])
                           data-fim     (conj [:<= :i.data_inicio data-fim]))
        final-query (if (seq conditions)
                      (assoc base-query :where (into [:and] conditions))
                      base-query)]
    (query final-query)))

(defn buscar-impressao
  "Busca impressão por ID"
  [id]
  (query-one {:select [:*]
              :from   [:impressoes]
              :where  [:= :id id]}))

(defn buscar-impressao-por-bambu-id
  "Busca impressão pelo ID da task do Bambu"
  [bambu-task-id]
  (query-one {:select [:*]
              :from   [:impressoes]
              :where  [:= :bambu_task_id (str bambu-task-id)]}))

(defn atualizar-impressao!
  "Atualiza dados da impressão"
  [id updates]
  (update! :impressoes updates ["id = ?" id]))

;; Relatórios

(defn relatorio-mensal
  "Gera relatório de custos e receitas do mês"
  [ano mes]
  (let [inicio (str ano "-" (format "%02d" mes) "-01")
        fim    (str ano "-" (format "%02d" (inc mes)) "-01")]
    (query-one
      {:select [[:%count.* :total_impressoes]
                [[:%sum.peso_usado_g] :total_filamento_g]
                [[:%sum.custo_total] :total_custos]
                [[:%sum.preco_venda] :total_receitas]
                [[:%sum.margem_lucro] :total_lucro]
                [[:%avg.tempo_minutos] :tempo_medio_min]]
       :from   [:impressoes]
       :where  [:and
                [:>= :data_inicio inicio]
                [:<  :data_inicio fim]
                [:=  :status "success"]]})))

(defn relatorio-por-filamento
  "Estatísticas de uso por filamento"
  [filamento-id]
  (query-one
    {:select [[:%count.* :total_impressoes]
              [[:%sum.peso_usado_g] :total_usado_g]
              [[:%sum.custo_filamento] :total_gasto]
              [[:%avg.custo_filamento] :custo_medio]]
     :from   [:impressoes]
     :where  [:and
              [:= :filamento_id filamento-id]
              [:= :status "success"]]}))

(defn top-impressoes-lucrativas
  "Top N impressões mais lucrativas"
  [n]
  (query {:select   [:nome :preco_venda :margem_lucro :data_inicio]
          :from     [:impressoes]
          :where    [:= :status "success"]
          :order-by [[:margem_lucro :desc]]
          :limit    n}))

;; Credenciais Bambu

(defn salvar-bambu-credentials!
  "Salva credenciais da Bambu Cloud"
  [{:keys [email access-token refresh-token token-expiry device-id]}]
  (let [token-expiry-ts (to-sql-timestamp token-expiry)]
    (jdbc/execute-one!
      datasource
      ["INSERT INTO bambu_credentials
        (email, access_token, refresh_token, token_expiry, device_id, updated_at)
        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        RETURNING *"
       email access-token refresh-token token-expiry-ts device-id])))

(defn buscar-bambu-credentials
  "Busca credenciais salvas da Bambu"
  []
  (query-one {:select   [:*]
              :from     [:bambu_credentials]
              :order-by [[:updated_at :desc]]
              :limit    1}))

(comment
  ;; Exemplos de uso:

  ;; Criar filamento
  (criar-filamento!
    {:nome           "PLA Preto"
     :marca          "Bambu Lab"
     :tipo           "PLA"
     :cor            "Preto"
     :peso-inicial-g 1000
     :preco-compra   89.90
     :data-compra    (Instant/now)})

  ;; Listar filamentos
  (listar-filamentos)

  ;; Criar impressão
  (criar-impressao!
    {:bambu_task_id  "task-123"
     :nome           "Peça X"
     :filamento_id   #uuid "00000000-0000-0000-0000-000000000000"
     :data_inicio    (Instant/now)
     :tempo_minutos  480
     :peso_usado_g   100
     :custo_total    33.06M
     :status         "success"})

  ;; Relatório mensal
  (relatorio-mensal 2025 11)
  )
