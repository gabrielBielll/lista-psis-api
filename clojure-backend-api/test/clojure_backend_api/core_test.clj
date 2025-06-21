(ns clojure-backend-api.core-test
  (:require [clojure.test :refer :all]
            [clojure-backend-api.core :refer :all]
            [cheshire.core :as json])
  (:import (org.postgresql.util PGobject)))

(deftest test-jsonb-conversion
  (testing "->jsonb function"
    (let [data {:key "value" :number 123}
          pg-obj (->jsonb data)]
      (is (instance? PGobject pg-obj))
      (is (= "jsonb" (.getType pg-obj)))
      (is (= (json/generate-string data) (.getValue pg-obj)))))

  (testing "pgobject->map function"
    (let [data {:key "value" :another-key {:nested-key "nested-value"}}
          pg-obj (PGobject.)]
      (.setType pg-obj "jsonb")
      (.setValue pg-obj (json/generate-string data))
      (is (= data (@#'clojure-backend-api.core/pgobject->map pg-obj))))

    (testing "pgobject->map with nil input"
      (is (nil? (@#'clojure-backend-api.core/pgobject->map nil))))))

;; Mocking clojure.java.jdbc/* e outras dependências para não depender de um banco real nos testes.
(deftest test-database-functions
  (with-redefs [clojure-backend-api.core/db-spec "mock-db-for-all-db-tests"]
    (testing "count-psychologists"
      (with-redefs [clojure.java.jdbc/query (fn [db-spec sql-params]
                                 (is (= db-spec "mock-db-for-all-db-tests"))
                               (is (= ["SELECT count(*) FROM horarios"] sql-params))
                               [{:count 5}])]
      (is (= 5 (count-psychologists)))))

  (testing "count-psychologists with exception"
    (with-redefs [clojure.java.jdbc/query (fn [_ _] (throw (Exception. "DB error")))]
      (is (= 999 (count-psychologists)))))

  (testing "get-all-schedules"
    (let [horarios-json-str "{\"segunda\": [\"09:00\", \"10:00\"]}"
          pg-obj (doto (PGobject.) (.setType "jsonb") (.setValue horarios-json-str))
          mock-result [{:psicologa_id "psi1" :horarios_disponiveis pg-obj}]]
      (with-redefs [clojure.java.jdbc/query (fn [db-spec sql-params]
                                 (is (= db-spec @#'clojure-backend-api.core/db-spec))
                                 (is (= ["SELECT psicologa_id, horarios_disponiveis FROM horarios"] sql-params))
                                 mock-result)
                    clojure-backend-api.core/db-spec "mock-db-spec"] ; Ensure db-spec is not nil
        (let [result (get-all-schedules)]
          (is (= 1 (count result)))
          (is (= {:psicologa_id "psi1" :horarios_disponiveis {:segunda ["09:00" "10:00"]}}
                 (first result)))))))

  (testing "get-all-schedules with nil db-spec"
    (with-redefs [clojure-backend-api.core/db-spec nil]
      (is (= [] (get-all-schedules)))))

  (testing "get-all-schedules with exception"
    (with-redefs [clojure.java.jdbc/query (fn [_ _] (throw (Exception. "DB error")))
                  clojure-backend-api.core/db-spec "mock-db-spec"]
      (is (= [] (get-all-schedules)))))

  (testing "get-psychologist-by-id"
    (let [mock-psi {:id 1 :psicologa_id "psi1" :senha_hash "hashed_password"}]
      (with-redefs [clojure.java.jdbc/query (fn [db-spec sql-params]
                                 (is (= db-spec @#'clojure-backend-api.core/db-spec))
                                 (is (= ["SELECT id, psicologa_id, senha_hash FROM horarios WHERE psicologa_id = ?" "psi1"] sql-params))
                                 [mock-psi])]
        (is (= mock-psi (get-psychologist-by-id "psi1"))))))

  (testing "get-psychologist-by-id not found"
    (with-redefs [clojure.java.jdbc/query (fn [_ _] [])]
      (is (nil? (get-psychologist-by-id "nonexistent")))))

  (testing "get-psychologist-by-id with exception"
    (with-redefs [clojure.java.jdbc/query (fn [_ _] (throw (Exception. "DB error")))]
      (is (nil? (get-psychologist-by-id "psi1")))))

  (testing "update-schedule!"
    (let [new-schedule {:terca ["14:00"]}]
      (with-redefs [clojure.java.jdbc/update! (fn [db-spec table-name values where-params]
                                   (is (= db-spec @#'clojure-backend-api.core/db-spec))
                                   (is (= :horarios table-name))
                                   (is (= "jsonb" (-> values :horarios_disponiveis .getType)))
                                   (is (= (json/generate-string new-schedule) (-> values :horarios_disponiveis .getValue)))
                                   (is (contains? values :atualizado_em))
                                   (is (= ["psicologa_id = ?" "psi1"] where-params))
                                   [1])] ; Simulate one row updated
        (is (= [1] (update-schedule! "psi1" new-schedule))))))

  (testing "update-schedule! with exception"
    (with-redefs [clojure.java.jdbc/update! (fn [_ _ _ _] (throw (Exception. "DB error")))]
      (is (nil? (update-schedule! "psi1" {}))))))

(deftest test-route-handlers
  (testing "get-all-horarios-handler"
    (with-redefs [clojure-backend-api.core/get-all-schedules (fn [] [{:id "psi1" :horarios {}}])]
      (let [response (get-all-horarios-handler)]
        (is (= 200 (:status response)))
        (is (= [{:id "psi1" :horarios {}}] (:body response))))))

  (testing "update-horarios-handler"
    (testing "valid request and correct password"
      (let [mock-psi {:id 1 :psicologa_id "psi1" :senha_hash (buddy.hashers/derive "password123")}
            request {:body {:id "psi1" :senha "password123" :horarios {:segunda ["10:00"]}}}]
        (with-redefs [clojure-backend-api.core/get-psychologist-by-id (fn [id] (is (= "psi1" id)) mock-psi)
                      clojure-backend-api.core/update-schedule! (fn [id horarios]
                                         (is (= "psi1" id))
                                         (is (= {:segunda ["10:00"]} horarios))
                                         [1])]
          (let [response (update-horarios-handler request)]
            (is (= 200 (:status response)))
            (is (= {:message "Horários atualizados com sucesso!"} (:body response)))))))

    (testing "invalid request - missing fields"
      (let [request {:body {:id "psi1"}}] ; Missing senha and horarios
        (let [response (update-horarios-handler request)]
          (is (= 400 (:status response)))
          (is (= {:message "Requisição inválida. Campos 'id', 'senha' e 'horarios' são obrigatórios."} (:body response))))))

    (testing "psychologist not found"
      (let [request {:body {:id "nonexistent" :senha "password" :horarios {}}}]
        (with-redefs [clojure-backend-api.core/get-psychologist-by-id (fn [_] nil)]
          (let [response (update-horarios-handler request)]
            (is (= 401 (:status response)))
            (is (= {:message "Não autorizado. Verifique o ID e a senha."} (:body response)))))))

    (testing "incorrect password"
      (let [mock-psi {:id 1 :psicologa_id "psi1" :senha_hash (buddy.hashers/derive "correcthorse")}
            request {:body {:id "psi1" :senha "wrongpassword" :horarios {}}}]
        (with-redefs [clojure-backend-api.core/get-psychologist-by-id (fn [_] mock-psi)]
          (let [response (update-horarios-handler request)]
            (is (= 401 (:status response)))
            (is (= {:message "Não autorizado. Verifique o ID e a senha."} (:body response))))))))

  (testing "create-psychologist-handler"
    (testing "successful creation"
      (let [request {:body {:id "newpsi" :senha "newpass"}}]
        (with-redefs [clojure-backend-api.core/count-psychologists (fn [] 0) ; Less than 5
                      clojure.java.jdbc/insert! (fn [db-spec table-name values]
                                     (is (= @#'clojure-backend-api.core/db-spec db-spec))
                                     (is (= :horarios table-name))
                                     (is (= "newpsi" (:psicologa_id values)))
                                     (is (buddy.hashers/check "newpass" (:senha_hash values)))
                                      (is (= {} (@#'clojure-backend-api.core/pgobject->map (:horarios_disponiveis values))))
                                     [1])]
          (let [response (create-psychologist-handler request)]
            (is (= 201 (:status response)))
            (is (= {:message "Psicólogo criado com sucesso!"} (:body response)))))))

    (testing "limit of psychologists reached"
      (let [request {:body {:id "anotherpsi" :senha "pass"}}]
        (with-redefs [clojure-backend-api.core/count-psychologists (fn [] 5)] ; Limit is 5
          (let [response (create-psychologist-handler request)]
            (is (= 403 (:status response)))
            (is (= {:message "Limite de 5 psicólogas atingido. Não é possível criar mais."} (:body response)))))))

    (testing "invalid request - missing fields"
      (let [request {:body {:id "shortrequest"}}] ; Missing senha
        ;; Ensure count-psychologists returns < 5 for this specific test case
        (with-redefs [clojure-backend-api.core/count-psychologists (fn [] 0)]
          (let [response (create-psychologist-handler request)]
            (is (= 400 (:status response)))
            (is (= {:message "Requisição inválida. Campos 'id' e 'senha' são obrigatórios."} (:body response)))))))

    (testing "database error on creation"
      (let [request {:body {:id "psiError" :senha "passError"}}]
        (with-redefs [clojure-backend-api.core/count-psychologists (fn [] 0)
                      clojure.java.jdbc/insert! (fn [_ _ _] (throw (Exception. "DB insert error")))]
          (let [response (create-psychologist-handler request)]
            (is (= 500 (:status response)))
            (is (= {:message "Erro ao criar psicólogo: DB insert error"} (:body response))))))))
    ;; Closing the with-redefs for db-spec
    ))

(deftest test-prometheus-metrics
  (testing "simple-prometheus-metrics output"
    (with-redefs [clojure-backend-api.core/request-counter (atom 10)
                  clojure-backend-api.core/start-time (- (System/currentTimeMillis) (* 60 1000)) ; 60 seconds ago
                  clojure-backend-api.core/count-psychologists (fn [] 3)]
      (let [metrics-string (simple-prometheus-metrics)]
        (is (clojure.string/includes? metrics-string "http_requests_total 10"))
        (is (clojure.string/includes? metrics-string "psychologists_total 3"))
        (is (clojure.string/includes? metrics-string "app_uptime_seconds 60")) ; Approximately
        (is (clojure.string/includes? metrics-string "app_info{version=\"1.0\",service=\"clojure-psis-api\",environment=\"production\"} 1")))))

  (testing "prometheus-metrics-handler response"
    (with-redefs [clojure-backend-api.core/simple-prometheus-metrics (fn [] "fake_metrics")]
      (let [response (prometheus-metrics-handler)]
        (is (= 200 (:status response)))
        (is (= "text/plain; charset=utf-8" (get-in response [:headers "Content-Type"])))
        (is (= "fake_metrics" (:body response)))))))

(deftest test-health-check
  (testing "Health check route"
    (let [app @#'clojure-backend-api.core/app
          request {:uri "/health" :request-method :get}
          response (app request)]
      (is (= 200 (:status response)))
      (is (= "OK" (:body response)))
      (is (= "text/plain" (get-in response [:headers "Content-Type"]))))))

;; Adicionar um teste final para verificar a rota not-found
(deftest test-not-found-route
  (testing "Not found route"
    (let [app @#'clojure-backend-api.core/app
          request {:uri "/nonexistent-route" :request-method :get}
          response (app request)]
      (is (= 404 (:status response)))
      (is (= "Recurso não encontrado" (:body response))))))
