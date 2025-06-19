(ns clojure-backend-api.core
  (:require
    [compojure.core :refer :all]
    [compojure.route :as route]
    [ring.adapter.jetty :as jetty]
    [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
    [ring.util.response :as resp]
    [clojure.java.jdbc :as jdbc]
    [environ.core :refer [env]]
    [crypto.password.bcrypt :as password]
    [cheshire.core :as json])
  (:import (org.postgresql.util PGobject))
  (:gen-class))

;;; ----------------------------------------------------------------
;;; Configuração do Banco de Dados
;;; ----------------------------------------------------------------

(def db-spec
  (let [db-url (env :database-url)]
    (if db-url
      (str db-url "?ssl=true&sslmode=require")
      nil)))

;;; ----------------------------------------------------------------
;;; Funções Auxiliares
;;; ----------------------------------------------------------------

;; Converte um mapa Clojure em um objeto JSONB para o PostgreSQL
(defn ->jsonb [data]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/generate-string data))))

;; !! NOVA FUNÇÃO !!
;; Extrai e converte o valor de um objeto PGobject (JSONB) para um mapa Clojure
(defn- pgobject->map [pg-obj]
  (when pg-obj
    (-> pg-obj
        .getValue
        (json/parse-string true)))) ; O 'true' transforma as chaves em keywords

;;; ----------------------------------------------------------------
;;; Funções de Acesso ao Banco de Dados
;;; ----------------------------------------------------------------

(defn get-all-schedules []
  (try
    (if db-spec
      (let [results (jdbc/query db-spec ["SELECT psicologa_id, horarios_disponiveis FROM horarios"])]
        ;; !! LÓGICA ATUALIZADA !!
        ;; Mapeia os resultados, convertendo o campo :horarios_disponiveis
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
;;; Handlers e Rotas (sem alterações)
;;; ----------------------------------------------------------------

(defn get-all-horarios-handler []
  (let [schedules (get-all-schedules)]
    (resp/response schedules)))

(defroutes app-routes
  (context "/api" []
    (GET "/horarios" [] (get-all-horarios-handler))
    (POST "/horarios/editar" request (update-horarios-handler)))
  (route/not-found "Recurso não encontrado"))

(def app
  (-> app-routes
      (wrap-json-body {:keywords? true :bigdecimals? true})
      (wrap-json-response)))

;;; ----------------------------------------------------------------
;;; Função Principal (sem alterações)
;;; ----------------------------------------------------------------

(defn -main [& args]
  (let [port (Integer/parseInt (or (env :port) "8080"))]
    (jetty/run-jetty app {:port port :join? false})
    (println (str "Servidor rodando na porta " port))))

(defn init [] (println "Iniciando servidor..."))
(defn destroy [] (println "Parando servidor..."))
```

### Próximos Passos

1.  **Atualize o Código:** Substitua o conteúdo de `src/clojure_backend_api/core.clj` no seu projeto.
2.  **Commit & Push:** Salve e envie para o GitHub.
    ```bash
    git add .
    git commit -m "fix: Converte corretamente o JSONB do banco para a resposta da API"
    git push
    ```
3.  **Aguarde o Deploy:** Espere o Render terminar a atualização.
4.  **Teste com `curl`:**
    ```bash
    curl https://lista-psis-api.onrender.com/api/horarios
    ```

Estou muito confiante de que esta é a correção final. Depois de uma longa jornada de depuração, este último passo deve resolver tu
