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
    [ring.middleware.cors :refer [wrap-cors]]) ; <-- NOVO REQUIRE
  (:import (org.postgresql.util PGobject))
  (:gen-class))

;; ... (código existente para db-spec, ->jsonb, pgobject->map) ...

;; Funções de acesso ao banco de dados (sem alterações)
(defn count-psychologists []
  (try (-> (jdbc/query db-spec ["SELECT count(*) FROM horarios"]) first :count)
    (catch Exception e (println (str "ERRO GRAVE em count-psychologists: " (.getMessage e))) 999)))
(defn get-all-schedules []
  (try (let [results (jdbc/query db-spec ["SELECT psicologa_id, horarios_disponiveis FROM horarios"])] (mapv (fn [row] (update row :horarios_disponiveis pgobject->map)) results))
    (catch Exception e (println (str "ERRO GRAVE em get-all-schedules: " (.getMessage e))) [])))
(defn get-psychologist-by-id [id]
  (try (first (jdbc/query db-spec ["SELECT id, psicologa_id, senha_hash FROM horarios WHERE psicologa_id = ?" id]))
    (catch Exception e (println (str "ERRO GRAVE em get-psychologist-by-id: " (.getMessage e))) nil)))
(defn update-schedule! [id new-schedule]
  (try (let [json-schedule (->jsonb new-schedule)] (jdbc/update! db-spec :horarios {:horarios_disponiveis json-schedule :atualizado_em (java.sql.Timestamp. (System/currentTimeMillis))} ["psicologa_id = ?" id]))
    (catch Exception e (println (str "ERRO GRAVE em update-schedule!: " (.getMessage e))) nil)))


;; Handlers dos Endpoints (sem alterações)
(defn get-all-horarios-handler []
  (let [schedules (get-all-schedules)]
    (resp/response schedules)))

(defn update-horarios-handler [request]
  (let [{:keys [id senha horarios]} (:body request)]
    (if (or (nil? id) (nil? senha) (nil? horarios))
      (-> (resp/response {:message "Requisição inválida. Campos 'id', 'senha' e 'horarios' são obrigatórios."}) (resp/status 400))
      (if-let [psi (get-psychologist-by-id id)]
        (if (hashers/check senha (:senha_hash psi))
          (do (update-schedule! id horarios) (-> (resp/response {:message "Horários atualizados com sucesso!"}) (resp/status 200)))
          (-> (resp/response {:message "Não autorizado. Verifique o ID e a senha."}) (resp/status 401)))
        (-> (resp/response {:message "Não autorizado. Verifique o ID e a senha."}) (resp/status 401))))))

(defn create-psychologist-handler [request]
  (if (>= (count-psychologists) 5)
    (-> (resp/response {:message "Limite de 5 psicólogas atingido. Não é possível criar mais."}) (resp/status 403))
    (let [{:keys [id senha]} (:body request)]
      (if (or (nil? id) (nil? senha))
        (-> (resp/response {:message "Requisição inválida. Campos 'id' e 'senha' são obrigatórios."}) (resp/status 400))
        (try (let [hashed-password (hashers/derive senha)] (jdbc/insert! db-spec :horarios {:psicologa_id id :senha_hash hashed-password :horarios_disponiveis (->jsonb {})}) (-> (resp/response {:message "Psicólogo criado com sucesso!"}) (resp/status 201)))
          (catch Exception e (-> (resp/response {:message (str "Erro ao criar psicólogo: " (.getMessage e))}) (resp/status 500)))))))

;;; ----------------------------------------------------------------
;;; Definição de Rotas e Middlewares
;;; ----------------------------------------------------------------

(defroutes app-routes
  (context "/api" []
    (GET "/horarios" [] (get-all-horarios-handler))
    (POST "/horarios/editar" request (update-horarios-handler request))
    (POST "/horarios/criar" request (create-psychologist-handler request)))
  (route/not-found "Recurso não encontrado"))

;; !! APLICAÇÃO ATUALIZADA COM O WRAP-CORS !!
(def app
  (-> app-routes
      (wrap-cors :access-control-allow-origin [#".*"] ; Permite qualquer origem
                 :access-control-allow-methods [:get :post]) ; Permite métodos GET e POST
      (wrap-json-body {:keywords? true})
      (wrap-json-response)))

;; ... (o -main e o resto do arquivo continuam iguais) ...

(defn -main [& args]
  (let [port (Integer/parseInt (or (env :port) "8080"))]
    (jetty/run-jetty app {:port port :join? false})
    (println (str "Servidor rodando na porta " port))))

(defn init [] (println "Iniciando servidor..."))
(defn destroy [] (println "Parando servidor..."))
