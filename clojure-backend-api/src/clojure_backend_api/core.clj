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
    ;; --- REQUIRES DA NOVA BIBLIOTECA DE MÉTRICAS ---
    [metrics.ring.instrument :refer [instrument]]
    [metrics.reporters.prometheus :as prometheus-reporter])
  (:import (org.postgresql.util PGobject))
  (:gen-class))


;;; ----------------------------------------------------------------
;;; Configuração do Reporter do Prometheus
;;; ----------------------------------------------------------------
;; Cria e inicia um reporter que irá coletar as métricas
(defonce prom-reporter (prometheus-reporter/reporter))
(prometheus-reporter/start prom-reporter)


;;; ----------------------------------------------------------------
;;; Configuração do Banco de Dados (sem alteração)
;;; ----------------------------------------------------------------
(def db-spec
  (let [db-url (env :database-url)]
    (if db-url
      (str db-url "?ssl=true&sslmode=require")
      (do
        (println "AVISO: DATABASE_URL não definida. O banco de dados não funcionará.")
        nil))))

;;; ... (o restante das suas funções de BD e auxiliares permanecem iguais) ...
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
;;; Definição de Rotas e Middlewares
;;; ----------------------------------------------------------------

(defroutes app-routes
  (GET "/health" []
    {:status 200
     :headers {"Content-Type" "text/plain"}
     :body "OK"})
     
  ;; Rota para o Prometheus fazer o 'scrape' das métricas
  (GET "/metrics" [] {:status 200
                      :headers {"Content-Type" "text/plain"}
                      :body (prometheus-reporter/report-str prom-reporter)})

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
      (wrap-json-response)
      ;; Adiciona o middleware de instrumentação da nova biblioteca
      (instrument)))


;;; ----------------------------------------------------------------
;;; Função Principal (sem alteração)
;;; ----------------------------------------------------------------

(defn -main [& args]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8080"))]
    (println (str "Servidor iniciando na porta " port))
    (jetty/run-jetty app {:port port :join? false})))

(defn init [] (println "Iniciando servidor..."))
(defn destroy [] (println "Parando servidor..."))
