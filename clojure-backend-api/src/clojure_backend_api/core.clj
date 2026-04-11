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
    [metrics.ring.instrument :refer [instrument]])
  (:import (org.postgresql.util PGobject))
  (:gen-class))

;; Contador simples para métricas
(defonce request-counter (atom 0))
(defonce start-time (System/currentTimeMillis))

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
      (let [results (jdbc/query db-spec ["SELECT psicologa_id, nome, horarios_disponiveis FROM horarios"])]
        ;; MODIFICAÇÃO CHAVE (FASE 1):
        ;; Garante que a API seja robusta e não retorne `nil` para o nome.
        (mapv (fn [row]
                (-> row
                    (update :nome #(or % "Nome não cadastrado")) ; Se :nome for nil, usa o texto padrão.
                    (update :horarios_disponiveis pgobject->map)))
              results))
      (do
        (println "ERRO FATAL: A variável de ambiente DATABASE_URL não está definida.")
        []))
    (catch Exception e
      (println (str "ERRO GRAVE em get-all-schedules: " (.getMessage e)))
      [])))

(defn get-psychologist-by-id [id]
  (try
    ;; MODIFICADO: Seleciona também o nome
    (first (jdbc/query db-spec ["SELECT id, psicologa_id, nome, senha_hash FROM horarios WHERE psicologa_id = ?" id]))
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
;;; Handlers com contadores simples
;;; ----------------------------------------------------------------
(defn get-all-horarios-handler []
  (swap! request-counter inc)
  (let [schedules (get-all-schedules)]
    (resp/response schedules)))

(defn update-horarios-handler [request]
  (swap! request-counter inc)
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
  (swap! request-counter inc)
  (if (>= (count-psychologists) 10)
    (-> (resp/response {:message "Limite de 10 psicólogas atingido. Não é possível criar mais."})
        (resp/status 403))
    ;; MODIFICADO: Extrai o 'nome' do corpo da requisição
    (let [{:keys [id nome senha]} (:body request)]
      ;; MODIFICADO: Valida a presença do campo 'nome'
      (if (or (nil? id) (nil? nome) (nil? senha))
        (-> (resp/response {:message "Requisição inválida. Campos 'id', 'nome' e 'senha' são obrigatórios."})
            (resp/status 400))
        (try
          (let [hashed-password (hashers/derive senha)]
            ;; MODIFICADO: Insere o 'nome' no banco de dados
            (jdbc/insert! db-spec :horarios
                          {:psicologa_id id
                           :nome nome
                           :senha_hash hashed-password
                           :horarios_disponiveis (->jsonb {})}))
          (-> (resp/response {:message "Psicólogo criado com sucesso!"})
              (resp/status 201))
          (catch Exception e
            (-> (resp/response {:message (str "Erro ao criar psicólogo: " (.getMessage e))})
                (resp/status 500))))))))

;;; ----------------------------------------------------------------
;;; Métricas Super Simples para Prometheus
;;; ----------------------------------------------------------------
(defn simple-prometheus-metrics []
  (let [current-time (System/currentTimeMillis)
        uptime-seconds (/ (- current-time start-time) 1000)
        request-count @request-counter
        psychologist-count (count-psychologists)]
    (str
      "# HELP http_requests_total Total number of HTTP requests\n"
      "# TYPE http_requests_total counter\n"
      "http_requests_total " request-count "\n\n"

      "# HELP psychologists_total Number of psychologists in database\n"
      "# TYPE psychologists_total gauge\n"
      "psychologists_total " psychologist-count "\n\n"

      "# HELP app_uptime_seconds Application uptime in seconds\n"
      "# TYPE app_uptime_seconds gauge\n"
      "app_uptime_seconds " (int uptime-seconds) "\n\n"

      "# HELP app_info Application information\n"
      "# TYPE app_info gauge\n"
      "app_info{version=\"1.0\",service=\"clojure-psis-api\",environment=\"production\"} 1\n")))

(defn prometheus-metrics-handler []
  {:status 200
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body (simple-prometheus-metrics)})

;;; ----------------------------------------------------------------
;;; Rotas
;;; ----------------------------------------------------------------
(defroutes app-routes
  (GET "/health" []
    {:status 200
     :headers {"Content-Type" "text/plain"}
     :body "OK"})

  (GET "/metrics" [] (prometheus-metrics-handler))

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
