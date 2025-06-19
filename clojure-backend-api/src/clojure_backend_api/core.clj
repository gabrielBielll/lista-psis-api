(ns clojure-backend-api.core
  (:require [ring.adapter.jetty :as jetty]
            [compojure.core :refer :all]
            [compojure.route :as route]))

(defn hello-world-handler [request]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "Hello, World from Compojure!"})

(defroutes app-routes
  (GET "/" [] hello-world-handler)
  (route/not-found "Not Found"))

(defn -main [& args]
  (jetty/run-jetty app-routes {:port 3000 :join? false}))
