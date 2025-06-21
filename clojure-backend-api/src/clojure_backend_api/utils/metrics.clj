(ns clojure-backend-api.utils.metrics
  (:require [metrics.core :as metrics]
            [cheshire.core :as json]))

(defn convert-to-prometheus-format []
  (let [registry (metrics/default-registry)
        metrics-data (metrics/serialize registry)]
    (str 
     "# HELP http_requests_total Total HTTP requests by method\n"
     "# TYPE http_requests_total counter\n"
     (when-let [get-rate (get-in metrics-data ["ring.requests.rate.GET" "rates" "total"])]
       (str "http_requests_total{method=\"GET\"} " (int get-rate) "\n"))
     (when-let [post-rate (get-in metrics-data ["ring.requests.rate.POST" "rates" "total"])]
       (str "http_requests_total{method=\"POST\"} " (int post-rate) "\n"))
     
     "# HELP http_responses_total Total HTTP responses by status\n"
     "# TYPE http_responses_total counter\n"
     (when-let [rate-2xx (get-in metrics-data ["ring.responses.rate.2xx" "rates" "total"])]
       (str "http_responses_total{status=\"2xx\"} " (int rate-2xx) "\n"))
     (when-let [rate-4xx (get-in metrics-data ["ring.responses.rate.4xx" "rates" "total"])]
       (str "http_responses_total{status=\"4xx\"} " (int rate-4xx) "\n"))
     
     "# HELP app_info Application information\n"
     "# TYPE app_info gauge\n"
     "app_info{version=\"1.0\",service=\"clojure-psis-api\"} 1\n")))

(defn prometheus-metrics-handler []
  {:status 200
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body (convert-to-prometheus-format)})

(defn metrics-json-handler []
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string (metrics/serialize (metrics/default-registry)))})
