(ns clojure-backend-api.core
  (:require
    [compojure.core :refer :all]
    [compojure.route :as route]
    [ring.adapter.jetty :as jetty]
    [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
    [ring.middleware.cors :refer [wrap-cors]]
    [metrics.ring.instrument :refer [instrument]]
    ;; SEUS NAMESPACES ORGANIZADOS
    [clojure-backend-api.utils.metrics :as metrics]
    [clojure-backend-api.handlers.horarios :as horarios])
  (:gen-class))

(defroutes app-routes
  (GET "/health" []
    {:status 200
     :headers {"Content-Type" "text/plain"}
     :body "OK"})
     
  ;; USANDO OS HANDLERS DO NAMESPACE UTILS
  (GET "/metrics" [] (metrics/prometheus-metrics-handler))
  (GET "/metrics-json" [] (metrics/metrics-json-handler))

  (context "/api" []
    (GET "/horarios" [] (horarios/get-all))
    (POST "/horarios/editar" request (horarios/update request))
    (POST "/horarios/criar" request (horarios/create request)))
  (route/not-found "Recurso não encontrado"))

;; App definition permanece igual
(def app
  (-> app-routes
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :post])
      (wrap-json-body {:keywords? true})
      (wrap-json-response)
      (instrument)))

;; Main function permanece igual
(defn -main [& args]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8080"))]
    (println (str "Servidor iniciando na porta " port))
    (jetty/run-jetty app {:port port :join? false})))
