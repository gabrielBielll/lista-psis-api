(defproject clojure-backend-api "0.1.0-SNAPSHOT"
  :description "API para gerenciar horários de psicólogas."
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}

  ;; ADICIONA O REPOSITÓRIO CLOJARS EXPLÍCITAMENTE
  :repositories [["clojars" "https://repo.clojars.org/"]]

  :dependencies [[org.clojure/clojure "1.11.1"]
                 [ring/ring-core "1.9.6"]
                 [ring/ring-jetty-adapter "1.9.6"]
                 [ring/ring-json "0.5.1"]
                 [compojure "1.7.0"]
                 [org.clojure/java.jdbc "0.7.12"]
                 [org.postgresql/postgresql "42.5.0"]
                 [environ "1.2.0"]
                 [buddy/buddy-hashers "1.8.1"]
                 [cheshire "5.11.0"]
                 [ring-cors "0.1.13"]
                 ;; Métricas e Prometheus
                 [metrics-clojure "2.10.0"]
                 [metrics-clojure/metrics-clojure-ring "2.10.0"]
                 [io.prometheus/simpleclient "0.16.0"]
                 [io.prometheus/simpleclient_hotspot "0.16.0"]
                 [io.prometheus/simpleclient_servlet "0.16.0"]
                 [io.prometheus/simpleclient_common "0.16.0"]]

  :main ^:skip-aot clojure-backend-api.core
  :target-path "target/%s"
 
  :plugins [[lein-ring "0.12.6"]]

  :ring {:handler clojure-backend-api.core/app
         :init clojure-backend-api.core/init
         :destroy clojure-backend-api.core/destroy}
 
  :profiles
  {:uberjar {:aot :all
             :uberjar-name "clojure-backend-api.jar"}})
