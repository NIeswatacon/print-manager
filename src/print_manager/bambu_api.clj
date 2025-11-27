(ns print-manager.bambu-api
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; ---------------------------------------------------------
;; CONFIG
;; ---------------------------------------------------------

(def ^:private base-url "https://api.bambulab.com")

(defn- make-request
  [{:keys [method endpoint token body headers]}]
  (try
    (let [resp (http/request
                 {:method method
                  :url    (str base-url endpoint)
                  :headers (merge
                             {"Content-Type" "application/json"}
                             (when token {"Authorization" (str "Bearer " token)})
                             headers)
                  :body (when body (json/generate-string body))
                  :as   :json
                  :throw-exceptions false})]

      ;; DEBUG opcional — deixe por enquanto
      (println "================ RAW RESPONSE ================")
      (println (:status resp))
      (println (:headers resp))
      (println (:body resp))
      (println "==============================================")

      (if (< (:status resp) 400)
        {:success true :data (:body resp)}
        {:success false
         :status (:status resp)
         :error  (:body resp)}))
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

;; ---------------------------------------------------------
;; LOGIN COM SENHA (1ª etapa — envia código)
;; ---------------------------------------------------------

(defn login-password
  "Login usando email + senha. Para contas Google, a resposta será loginType=verifyCode."
  [email password]
  (let [resp (make-request
               {:method :post
                :endpoint "/v1/user-service/user/login"
                :body {:account email
                       :password password}})]
    (:data resp)))

;; ---------------------------------------------------------
;; LOGIN COM CÓDIGO (2ª etapa — tokens reais)
;; ---------------------------------------------------------

(defn login-code
  "Valida o código enviado por email, obtendo accessToken e refreshToken reais."
  [email code]
  (let [resp (make-request
               {:method :post
                :endpoint "/v1/user-service/user/login"
                :body {:account email
                       :code (str code)}})]
    (:data resp)))

;; ---------------------------------------------------------
;; REFRESH TOKEN
;; ---------------------------------------------------------

(defn refresh-token
  "Renova token usando o refreshToken já salvo."
  [refresh-token]
  (let [resp (make-request
               {:method :post
                :endpoint "/v1/user-service/user/refreshtoken"
                :body {:refreshToken refresh-token}})]
    (get-in resp [:data :data :accessToken])))

;; ---------------------------------------------------------
;; DEVICES (várias versões de resposta suportadas)
;; ---------------------------------------------------------

(defn get-device-list
  "Retorna a lista de impressoras da conta, padronizando :dev_id."
  [token]
  (let [resp (make-request
               {:method :get
                :endpoint "/v1/iot-service/api/user/bind"
                :token token})
        body (:data resp)]

    (println "========================================")
    (println "DEBUG /user/bind RESPONSE:")
    (println (pr-str body))
    (println "========================================")

    (let [devices (or
                    ;; formatos conhecidos
                    (:devices body)
                    (get-in body [:data :devices])
                    (:items body)
                    (get-in body [:data :items])
                    ;; fallback
                    body)]
      (when devices
        (map (fn [dev]
               (assoc dev
                 :dev_id (or (:dev_id dev)
                             (:devId dev)
                             (:id dev))))
             devices)))))

;; ---------------------------------------------------------
;; HISTÓRICO DE IMPRESSÕES
;; ---------------------------------------------------------

(defn get-task-history
  [token device-id & {:keys [limit offset] :or {limit 100 offset 0}}]
  (let [resp (make-request
               {:method :get
                :endpoint (str "/v1/user-service/my/tasks"
                               "?deviceId=" device-id
                               "&limit=" limit
                               "&offset=" offset)
                :token token})]
    (when (:success resp)
      (get-in resp [:data :hits]))))

(defn parse-task [task]
  {:bambu-task-id (:id task)
   :nome          (:title task)
   :data-inicio   (java.time.Instant/parse (:startTime task))
   :data-fim      (when (:endTime task)
                    (java.time.Instant/parse (:endTime task)))
   :tempo-minutos (when (:costTime task)
                    (quot (:costTime task) 60))
   :peso-usado-g  (:weight task)
   :status        (case (:status task)
                    "finish"  "success"
                    "failed"  "failed"
                    "running" "running"
                    "unknown")})

(defn sync-tasks
  [token device-id]
  (when-let [tasks (get-task-history token device-id :limit 500)]
    (map parse-task tasks)))