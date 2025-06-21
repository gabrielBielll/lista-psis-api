(ns clojure-backend-api.core
  (:require
    [compojure.core :refer :all]
    [compojure.route :as route]
    [ring.adapter.jetty :as jetty]
    [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
    [ring.util.response :as resp]
    [clojure.java.jdbc :as jdbc]
    [environ.core :refer [env]]
    [buddy.hashers :as hashers]
    [cheshire.core :as json]
    [ring.middleware.cors :refer [wrap-cors]])
  (:import 
    (org.postgresql.util PGobject)
    (io.prometheus.client CollectorRegistry Counter Gauge)
    (io.prometheus.client.exporter.common TextFormat)
    (java.io StringWriter))
  (:gen-class))

;;; ----------------------------------------------------------------
;;; Configuração do Prometheus (apenas Java)
;;; ----------------------------------------------------------------
(defonce ^CollectorRegistry registry (CollectorRegistry.))

;; Métricas simples
(defonce http-requests-total
  (-> (Counter/build)
      (.name "http_requests_total")
      (.help "Total number of HTTP requests")
      (.labelNames (into-array String ["method" "endpoint" "status"]))
      (.register registry)))

(defonce db-connections
  (-> (Gauge/build)
      (.name "database_connections")
      (.help "Number of database connections")
      (.register registry)))

;;; ----------------------------------------------------------------
;;; Suas funções existentes (sem alteração)
;;; ----------------------------------------------------------------
(def db-spec
  (let [db-url (env :database-url)]
    (if db-url
      (str db-url "?ssl=true&sslmode=require")
      (do
        (println "AVISO: DATABASE_URL não definida. O banco de dados não funcionará.")
        nil))))

(defn ->jsonb [data]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/generate-string data))))

(defn- pgobject->map [pg-obj]
  (when pg-obj
    (-> pg-obj
        .getValue
        (json/parse-string true))))

(defn count-psychologists []
  (try
    (-> (jdbc/query db-spec ["SELECT count(*) FROM horarios"])
        first
        :count)
    (catch Exception e
      (println (str "ERRO GRAVE em count-psychologists: " (.getMessage e)))
      999)))

(defn get-all-schedules []
  (try
    (if db-spec
      (let [results (jdbc/query db-spec ["SELECT psicologa_id, horarios_disponiveis FROM horarios"])]
        (mapv (fn [row] (update row :horarios_disponiveis pgobject->map))
              results))
      (do
        (println "ERRO FATAL: A variável de ambiente DATABASE_URL não está definida.")
        []))
    (catch Exception e
      (println (str "ERRO GRAVE em get-all-schedules: " (.getMessage e)))
      [])))

(defn get-psychologist-by-id [id]
  (try
    (first (jdbc/query db-spec ["SELECT id, psicologa_id, senha_hash FROM horarios WHERE psicologa_id = ?" id]))
    (catch Exception e
      (println (str "ERRO GRAVE em get-psychologist-by-id: " (.getMessage e)))
      nil)))

(defn update-schedule! [id new-schedule]
  (try
    (let [json-schedule (->jsonb new-schedule)]
      (jdbc/update! db-spec :horarios
                    {:horarios_disponiveis json-schedule
                     :atualizado_em (java.sql.Timestamp. (System/currentTimeMillis))}
                    ["psicologa_id = ?" id]))
    (catch Exception e
      (println (str "ERRO GRAVE em update-schedule!: " (.getMessage e)))
      nil)))

;;; ----------------------------------------------------------------
;;; Handlers com métricas básicas
;;; ----------------------------------------------------------------
(defn get-all-horarios-handler []
  (.inc (.labels http-requests-total (into-array String ["GET" "/api/horarios" "200"])))
  (let [schedules (get-all-schedules)]
    (resp/response schedules)))

(defn update-horarios-handler [request]
  (let [{:keys [id senha horarios]} (:body request)]
    (if (or (nil? id) (nil? senha) (nil? horarios))
      (do
        (.inc (.labels http-requests-total (into-array String ["POST" "/api/horarios/editar" "400"])))
        (-> (resp/response {:message "Requisição inválida. Campos 'id', 'senha' e 'horarios' são obrigatórios."})
            (resp/status 400)))
      (if-let [psi (get-psychologist-by-id id)]
        (if (hashers/check senha (:senha_hash psi))
          (do
            (.inc (.labels http-requests-total (into-array String ["POST" "/api/horarios/editar" "200"])))
            (update-schedule! id horarios)
            (-> (resp/response {:message "Horários atualizados com sucesso!"})
                (resp/status 200)))
          (do
            (.inc (.labels http-requests-total (into-array String ["POST" "/api/horarios/editar" "401"])))
            (-> (resp/response {:message "Não autorizado. Verifique o ID e a senha."})
                (resp/status 401))))
        (do
          (.inc (.labels http-requests-total (into-array String ["POST" "/api/horarios/editar" "401"])))
          (-> (resp/response {:message "Não autorizado. Verifique o ID e a senha."})
              (resp/status 401)))))))

(defn create-psychologist-handler [request]
  (if (>= (count-psychologists) 5)
    (do
      (.inc (.labels http-requests-total (into-array String ["POST" "/api/horarios/criar" "403"])))
      (-> (resp/response {:message "Limite de 5 psicólogas atingido. Não é possível criar mais."})
          (resp/status 403)))
    (let [{:keys [id senha]} (:body request)]
      (if (or (nil? id) (nil? senha))
        (do
          (.inc (.labels http-requests-total (into-array String ["POST" "/api/horarios/criar" "400"])))
          (-> (resp/response {:message "Requisição inválida. Campos 'id' e 'senha' são obrigatórios."})
              (resp/status 400)))
        (try
          (.inc (.labels http-requests-total (into-array String ["POST" "/api/horarios/criar" "201"])))
          (let [hashed-password (hashers/derive senha)]
            (jdbc/insert! db-spec :horarios
                          {:psicologa_id id
                           :senha_hash hashed-password
                           :horarios_disponiveis (->jsonb {})})
            (-> (resp/response {:message "Psicólogo criado com sucesso!"})
                (resp/status 201)))
          (catch Exception e
            (.inc (.labels http-requests-total (into-array String ["POST" "/api/horarios/criar" "500"])))
            (-> (resp/response {:message (str "Erro ao criar psicólogo: " (.getMessage e))})
                (resp/status 500))))))))

;;; ----------------------------------------------------------------
;;; Endpoint para métricas do Prometheus
;;; ----------------------------------------------------------------
(defn metrics-handler []
  (let [writer (StringWriter.)]
    (TextFormat/write004 writer (.metricFamilySamples registry))
    {:status 200
     :headers {"Content-Type" TextFormat/CONTENT_TYPE_004}
     :body (.toString writer)}))

;;; ----------------------------------------------------------------
;;; Rotas
;;; ----------------------------------------------------------------
(defroutes app-routes
  (GET "/health" []
    {:status 200
     :headers {"Content-Type" "text/plain"}
     :body "OK"})
     
  (GET "/metrics" [] (metrics-handler))

  (context "/api" []
    (GET "/horarios" [] (get-all-horarios-handler))
    (POST "/horarios/editar" request (update-horarios-handler request))
    (POST "/horarios/criar" request (create-psychologist-handler request)))
  (route/not-found "Recurso não encontrado"))

(def app
  (-> app-routes
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :post])
      (wrap-json-body {:keywords? true})
      (wrap-json-response)))

;;; ----------------------------------------------------------------
;;; Main
;;; ----------------------------------------------------------------
(defn -main [& args]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8080"))]
    (println (str "Servidor iniciando na porta " port))
    (jetty/run-jetty app {:port port :join? false})))

(defn init [] (println "Iniciando servidor..."))
(defn destroy [] (println "Parando servidor..."))
