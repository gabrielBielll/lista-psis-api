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
    [ring.middleware.cors :refer [wrap-cors]]
    [metrics.ring.instrument :refer [instrument]]
    [metrics.core :as metrics])
  (:import (org.postgresql.util PGobject))
  (:gen-class))

;;; ----------------------------------------------------------------
;;; Configuração do Banco de Dados
;;; ----------------------------------------------------------------
(def db-spec
  (let [db-url (env :database-url)]
    (if db-url
      (str db-url "?ssl=true&sslmode=require")
      (do
        (println "AVISO: DATABASE_URL não definida. O banco de dados não funcionará.")
        nil))))

;;; ----------------------------------------------------------------
;;; Funções Auxiliares
;;; ----------------------------------------------------------------
(defn ->jsonb [data]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/generate-string data))))

(defn- pgobject->map [pg-obj]
  (when pg-obj
    (-> pg-obj
        .getValue
        (json/parse-string true))))

;;; ----------------------------------------------------------------
;;; Funções de Banco de Dados
;;; ----------------------------------------------------------------
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
;;; Handlers
;;; ----------------------------------------------------------------
(defn get-all-horarios-handler []
  (let [schedules (get-all-schedules)]
    (resp/response schedules)))

(defn update-horarios-handler [request]
  (let [{:keys [id senha horarios]} (:body request)]
    (if (or (nil? id) (nil? senha) (nil? horarios))
      (-> (resp/response {:message "Requisição inválida. Campos 'id', 'senha' e 'horarios' são obrigatórios."})
          (resp/status 400))
      (if-let [psi (get-psychologist-by-id id)]
        (if (hashers/check senha (:senha_hash psi))
          (do
            (update-schedule! id horarios)
            (-> (resp/response {:message "Horários atualizados com sucesso!"})
                (resp/status 200)))
          (-> (resp/response {:message "Não autorizado. Verifique o ID e a senha."})
              (resp/status 401)))
        (-> (resp/response {:message "Não autorizado. Verifique o ID e a senha."})
            (resp/status 401))))))

(defn create-psychologist-handler [request]
  (if (>= (count-psychologists) 5)
    (-> (resp/response {:message "Limite de 5 psicólogas atingido. Não é possível criar mais."})
        (resp/status 403))
    (let [{:keys [id senha]} (:body request)]
      (if (or (nil? id) (nil? senha))
        (-> (resp/response {:message "Requisição inválida. Campos 'id' e 'senha' são obrigatórios."})
            (resp/status 400))
        (try
          (let [hashed-password (hashers/derive senha)]
            (jdbc/insert! db-spec :horarios
                          {:psicologa_id id
                           :senha_hash hashed-password
                           :horarios_disponiveis (->jsonb {})})
            (-> (resp/response {:message "Psicólogo criado com sucesso!"})
                (resp/status 201)))
          (catch Exception e
            (-> (resp/response {:message (str "Erro ao criar psicólogo: " (.getMessage e))})
                (resp/status 500))))))))

;;; ----------------------------------------------------------------
;;; Métricas para Prometheus
;;; ----------------------------------------------------------------
(defn convert-to-prometheus-format []
  (let [registry (metrics/default-registry)
        metrics-data (metrics/serialize registry)]
    (str 
     ;; Requests por método
     "# HELP http_requests_total Total HTTP requests by method\n"
     "# TYPE http_requests_total counter\n"
     (when-let [get-rate (get-in metrics-data ["ring.requests.rate.GET" "rates" "total"])]
       (str "http_requests_total{method=\"GET\"} " (int get-rate) "\n"))
     (when-let [post-rate (get-in metrics-data ["ring.requests.rate.POST" "rates" "total"])]
       (str "http_requests_total{method=\"POST\"} " (int post-rate) "\n"))
     
     ;; Response status
     "# HELP http_responses_total Total HTTP responses by status\n"
     "# TYPE http_responses_total counter\n"
     (when-let [rate-2xx (get-in metrics-data ["ring.responses.rate.2xx" "rates" "total"])]
       (str "http_responses_total{status=\"2xx\"} " (int rate-2xx) "\n"))
     (when-let [rate-4xx (get-in metrics-data ["ring.responses.rate.4xx" "rates" "total"])]
       (str "http_responses_total{status=\"4xx\"} " (int rate-4xx) "\n"))
     
     ;; Tempo de resposta
     "# HELP http_request_duration_seconds Request duration in seconds\n"
     "# TYPE http_request_duration_seconds summary\n"
     (when-let [duration (get-in metrics-data ["ring.handling-time.GET" "mean"])]
       (str "http_request_duration_seconds{method=\"GET\",quantile=\"mean\"} " (format "%.6f" (/ duration 1000000)) "\n"))
     
     ;; Informações da aplicação
     "# HELP app_info Application information\n"
     "# TYPE app_info gauge\n"
     "app_info{version=\"1.0\",service=\"clojure-psis-api\"} 1\n"
     
     ;; Contador de psicólogos
     "# HELP psychologists_total Number of psychologists in database\n"
     "# TYPE psychologists_total gauge\n"
     "psychologists_total " (count-psychologists) "\n")))

(defn prometheus-metrics-handler []
  {:status 200
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body (convert-to-prometheus-format)})

;;; ----------------------------------------------------------------
;;; Rotas
;;; ----------------------------------------------------------------
(defroutes app-routes
  (GET "/health" []
    {:status 200
     :headers {"Content-Type" "text/plain"}
     :body "OK"})
     
  ;; Endpoint de métricas em formato Prometheus
  (GET "/metrics" [] (prometheus-metrics-handler))
  
  ;; Endpoint de métricas em JSON para debug
  (GET "/metrics-json" [] 
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string (metrics/serialize (metrics/default-registry)))})

  (context "/api" []
    (GET "/horarios" [] (get-all-horarios-handler))
    (POST "/horarios/editar" request (update-horarios-handler request))
    (POST "/horarios/criar" request (create-psychologist-handler request)))
  (route/not-found "Recurso não encontrado"))

;;; ----------------------------------------------------------------
;;; App com middlewares
;;; ----------------------------------------------------------------
(def app
  (-> app-routes
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :post])
      (wrap-json-body {:keywords? true})
      (wrap-json-response)
      (instrument)))

;;; ----------------------------------------------------------------
;;; Função Principal
;;; ----------------------------------------------------------------
(defn -main [& args]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8080"))]
    (println (str "Servidor iniciando na porta " port))
    (jetty/run-jetty app {:port port :join? false})))

(defn init [] (println "Iniciando servidor..."))
(defn destroy [] (println "Parando servidor..."))
