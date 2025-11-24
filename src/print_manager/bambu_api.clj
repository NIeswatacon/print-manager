(ns print-manager.bambu-api
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private base-url "https://api.bambulab.com")

(defn- make-request
  "Faz requisição HTTP para a API Bambu com tratamento de erros"
  [{:keys [method endpoint token body headers]}]
  (try
    (let [response (http/request
                     {:method method
                      :url (str base-url endpoint)
                      :headers (merge {"Content-Type" "application/json"
                                       "Authorization" (str "Bearer " token)}
                                      headers)
                      :body (when body (json/generate-string body))
                      :as :json
                      :throw-exceptions false})]
      (if (< (:status response) 400)
        {:success true :data (:body response)}
        {:success false
         :error (:body response)
         :status (:status response)}))
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(defn login
  "Autentica usuário na Bambu Cloud e retorna token de acesso"
  [email password]
  (let [response (make-request
                   {:method :post
                    :endpoint "/v1/user-service/user/login"
                    :body {:account email
                           :password password}})]
    (when (:success response)
      (let [data (get-in response [:data :data])]
        {:access-token (:accessToken data)
         :refresh-token (:refreshToken data)
         :expires-in (:expiresIn data)}))))

(defn refresh-token
  "Renova o token de acesso usando refresh token"
  [refresh-token]
  (let [response (make-request
                   {:method :post
                    :endpoint "/v1/user-service/user/refreshtoken"
                    :body {:refreshToken refresh-token}})]
    (when (:success response)
      (get-in response [:data :data :accessToken]))))

(defn get-device-list
  "Lista impressoras associadas à conta"
  [token]
  (let [response (make-request
                   {:method :get
                    :endpoint "/v1/iot-service/api/user/bind"
                    :token token})]
    (when (:success response)
      (get-in response [:data :devices]))))

(defn get-task-history
  "Busca histórico de impressões (tasks) de um dispositivo"
  [token device-id & {:keys [limit offset]
                      :or {limit 100 offset 0}}]
  (let [response (make-request
                   {:method :get
                    :endpoint (str "/v1/user-service/my/tasks"
                                   "?deviceId=" device-id
                                   "&limit=" limit
                                   "&offset=" offset)
                    :token token})]
    (when (:success response)
      (get-in response [:data :hits]))))

(defn parse-task
  "Extrai informações relevantes de uma task do Bambu"
  [task]
  {:bambu-task-id (:id task)
   :nome (:title task)
   :data-inicio (java.time.Instant/parse (:startTime task))
   :data-fim (when (:endTime task)
               (java.time.Instant/parse (:endTime task)))
   :tempo-minutos (when (:costTime task)
                    (quot (:costTime task) 60)) ; converte segundos para minutos
   :peso-usado-g (:weight task) ; já vem em gramas
   :status (condp = (:status task)
             "finish" "success"
             "failed" "failed"
             "running" "running"
             "unknown")})

(defn sync-tasks
  "Sincroniza todas as tasks de um dispositivo"
  [token device-id]
  (when-let [tasks (get-task-history token device-id :limit 500)]
    (map parse-task tasks)))

(comment
  ;; Exemplo de uso:

  ;; 1. Login
  (def auth (login "seu-email@example.com" "sua-senha"))

  ;; 2. Listar impressoras
  (def devices (get-device-list (:access-token auth)))

  ;; 3. Pegar histórico de impressões
  (def device-id (-> devices first :dev_id))
  (def tasks (get-task-history (:access-token auth) device-id))

  ;; 4. Sincronizar todas as tasks
  (def parsed-tasks (sync-tasks (:access-token auth) device-id))
  )