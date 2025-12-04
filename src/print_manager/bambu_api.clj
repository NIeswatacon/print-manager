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
      (if (< (:status resp) 400)
        {:success true :data (:body resp)}
        {:success false :status (:status resp) :error (:body resp)}))
    (catch Exception e
      {:success false :error (.getMessage e)})))

;; LOGIN & TOKENS (Mantenha igual)
(defn login-password [email password]
  (let [resp (make-request {:method :post :endpoint "/v1/user-service/user/login" :body {:account email :password password}})] (:data resp)))

(defn login-code [email code]
  (let [resp (make-request {:method :post :endpoint "/v1/user-service/user/login" :body {:account email :code (str code)}})] (:data resp)))

(defn refresh-token [refresh-token]
  (let [resp (make-request {:method :post :endpoint "/v1/user-service/user/refreshtoken" :body {:refreshToken refresh-token}})] (get-in resp [:data :data :accessToken])))

(defn get-device-list [token]
  (let [resp (make-request {:method :get :endpoint "/v1/iot-service/api/user/bind" :token token})
        body (:data resp)
        devices (or (:devices body) (get-in body [:data :devices]) (:items body) (get-in body [:data :items]) body)]
    (when devices (map (fn [dev] (assoc dev :dev_id (or (:dev_id dev) (:devId dev) (:id dev)))) devices))))

;; HISTÓRICO E PARSER
(defn get-task-history [token device-id & {:keys [limit offset] :or {limit 100 offset 0}}]
  (let [resp (make-request {:method :get :endpoint (str "/v1/user-service/my/tasks?deviceId=" device-id "&limit=" limit "&offset=" offset) :token token})]
    (when (:success resp) (get-in resp [:data :hits]))))

(defn parse-task [task]
  (let [;; Data de fim segura (trata nils e strings vazias)
         end-time-str (:endTime task)
         end-time     (when (and end-time-str (not (str/blank? end-time-str)))
                        (try (java.time.Instant/parse end-time-str) (catch Exception _ nil)))

         status-code (:status task)]
    {:bambu-task-id (:id task)
     :nome          (:title task)
     :data-inicio   (java.time.Instant/parse (:startTime task))
     :data-fim      end-time
     :tempo-minutos (when (:costTime task) (quot (:costTime task) 60))
     :peso-usado-g  (:weight task)
     :cover         (:cover task)

     ;; LÓGICA ROBUSTA DE STATUS
     :status        (cond
                      ;; Se não tem data de fim válida, está a imprimir
                      (nil? end-time) "running"

                      ;; Se status for 0 ou 1 e não temos certeza, assumimos running (melhor que falha)
                      (or (= status-code 0)) "running"

                      ;; Códigos de Sucesso
                      (or (= status-code 2) (= status-code "finish") (= status-code "success")) "success"

                      ;; Falhas e Cancelamentos
                      (or (= status-code 3) (= status-code "failed")) "failed"
                      (or (= status-code 4) (= status-code "stopped") (= status-code "cancelled")) "cancelled"

                      :else "unknown")}))

(defn sync-tasks [token device-id]
  (when-let [tasks (get-task-history token device-id :limit 200)]
    (map parse-task tasks)))