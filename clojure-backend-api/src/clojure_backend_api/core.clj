(ns clojure-backend-api.core
  (:require
    [compojure.core :refer :all]
    [compojure.route :as route]
    [ring.adapter.jetty :as jetty]
    [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
    [ring.middleware.cookies :refer [wrap-cookies]]
    [ring.middleware.params :refer [wrap-params]]
    [ring.util.response :as resp]
    [clojure.java.jdbc :as jdbc]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [environ.core :refer [env]]
    [buddy.hashers :as hashers]
    [cheshire.core :as json]
    [ring.middleware.cors :refer [wrap-cors]]
    [metrics.ring.instrument :refer [instrument]])
  (:import (org.postgresql.util PGobject)
           (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers HttpRequest$BodyPublishers)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.text Normalizer Normalizer$Form)
           (java.time Instant OffsetDateTime ZonedDateTime Duration ZoneId DayOfWeek LocalDate LocalDateTime LocalTime)
           (java.sql Timestamp)
           (java.util Base64 UUID)
           (java.util.concurrent Executors TimeUnit ScheduledExecutorService)
           (javax.crypto Cipher)
           (javax.crypto.spec GCMParameterSpec SecretKeySpec))
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
    (first (jdbc/query db-spec ["SELECT id, psicologa_id, nome, senha_hash FROM horarios WHERE CAST(psicologa_id AS TEXT) = ?" (str id)]))
    (catch Exception e
      (println (str "ERRO GRAVE em get-psychologist-by-id: " (.getMessage e)))
      nil)))

(defn update-schedule! [id new-schedule]
  (try
    (let [json-schedule (->jsonb new-schedule)]
      (jdbc/update! db-spec :horarios
                    {:horarios_disponiveis json-schedule
                     :atualizado_em (java.sql.Timestamp. (System/currentTimeMillis))}
                    ["CAST(psicologa_id AS TEXT) = ?" (str id)]))
    (catch Exception e
      (println (str "ERRO GRAVE em update-schedule!: " (.getMessage e)))
      nil)))

;;; ----------------------------------------------------------------
;;; Sessões, papéis e migração de esquema
;;; ----------------------------------------------------------------
(def ^:private session-duration (Duration/ofDays 30))
(def ^:private deep-google-account-key "deep")

(defn- sha256-hex [value]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes (str value) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes))))

(defn- random-token []
  (let [bytes (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn- session-cookie-secure? []
  (not= "false" (some-> (env :cookie-secure) str/lower-case)))

(defn- session-cookie [token]
  {:value token
   :path "/"
   :http-only true
   :secure (session-cookie-secure?)
   :same-site :lax
   :max-age (.getSeconds session-duration)})

(defn- request-token [request]
  (or (get-in request [:cookies "deep_session" :value])
      (let [authorization (get-in request [:headers "authorization"])]
        (when (and authorization
                   (str/starts-with? (str/lower-case authorization) "bearer "))
          (subs authorization 7)))))

(defn- create-session! [account-id role]
  (let [token (random-token)
        token-hash (sha256-hex token)]
    (jdbc/insert! db-spec :app_sessions
                  {:token_hash token-hash
                   :conta_id (str account-id)
                   :papel (name role)
                   :expira_em (Timestamp/from (.plus (Instant/now) session-duration))})
    token))

(defn- session-account [request]
  (when-let [token (request-token request)]
    (try
      (let [session (first (jdbc/query db-spec
                                        ["SELECT conta_id, papel FROM app_sessions WHERE token_hash = ? AND expira_em > CURRENT_TIMESTAMP"
                                         (sha256-hex token)]))]
        (when session
          {:account-id (str (:conta_id session))
           :role (keyword (:papel session))
           :token-hash (sha256-hex token)}))
      (catch Exception e
        (println (str "ERRO ao validar sessão: " (.getMessage e)))
        nil))))

(defn- unauthorized-response []
  (-> (resp/response {:message "Autenticação necessária."})
      (resp/status 401)))

(defn- forbidden-response []
  (-> (resp/response {:message "Você não possui permissão para esta ação."})
      (resp/status 403)))

(defn- with-role [request accepted-roles handler]
  (if-let [account (session-account request)]
    (if (contains? accepted-roles (:role account))
      (handler account)
      (forbidden-response))
    (unauthorized-response)))

(defn- get-admin-by-login [login]
  (first (jdbc/query db-spec
                     ["SELECT login, nome, senha_hash FROM app_admins WHERE login = ?" (str login)])))

(defn- authenticate-account [id password]
  (when (and (some? id) (string? password) (not (str/blank? password)))
    (or
      (when-let [admin (get-admin-by-login id)]
        (when (hashers/check password (:senha_hash admin))
          {:account-id (:login admin) :role :admin :name (:nome admin)}))
      (when-let [psychologist (get-psychologist-by-id id)]
        (when (hashers/check password (:senha_hash psychologist))
          {:account-id (str (:psicologa_id psychologist))
           :role :psychologist
           :name (:nome psychologist)})))))

(defn login-handler [request]
  (let [{:keys [id senha]} (:body request)]
    (if-let [account (authenticate-account id senha)]
      (let [token (create-session! (:account-id account) (:role account))]
        (-> (resp/response {:message "Login efetuado com sucesso."
                            :account {:id (:account-id account)
                                      :name (:name account)
                                      :role (name (:role account))}
                            ;; O token facilita a integração entre domínios em staging.
                            ;; O gestor deve preferir o cookie HttpOnly em produção.
                            :token token})
            (assoc :cookies {"deep_session" (session-cookie token)})))
      (-> (resp/response {:message "Credenciais inválidas."})
          (resp/status 401)))))

(defn logout-handler [request]
  (when-let [token (request-token request)]
    (jdbc/delete! db-spec :app_sessions ["token_hash = ?" (sha256-hex token)]))
  (-> (resp/response {:message "Sessão encerrada."})
      (assoc :cookies {"deep_session" (assoc (session-cookie "") :max-age 0)})))

(defn current-account-handler [request]
  (if-let [account (session-account request)]
    (resp/response {:account {:id (:account-id account) :role (name (:role account))}})
    (unauthorized-response)))

(defn- read-migration-sql []
  (some-> "migrations/001_google_calendar_sync.sql" io/resource slurp))

(defn ensure-schema! []
  (when (and db-spec (read-migration-sql))
    (try
      ;; O arquivo contém somente instruções DDL independentes, separadas por ';'.
      (let [statements (->> (str/split (read-migration-sql) #";\s*(?:\r?\n|$)")
                            (map str/trim)
                            (remove str/blank?))]
        (doseq [statement statements]
          (jdbc/db-do-commands db-spec statement)))
      (catch Exception e
        (println (str "ERRO ao aplicar esquema de disponibilidade: " (.getMessage e)))))))

(defn ensure-admin-account! []
  (let [login (env :admin-user)
        password (env :admin-password)
        name (or (env :admin-name) "Administração Deep")]
    (when (and db-spec (not (str/blank? login)) (not (str/blank? password)))
      (try
        (jdbc/execute! db-spec
                       ["INSERT INTO app_admins (login, nome, senha_hash) VALUES (?, ?, ?)\n                         ON CONFLICT (login) DO UPDATE SET nome = EXCLUDED.nome, senha_hash = EXCLUDED.senha_hash"
                        login name (hashers/derive password)])
        (catch Exception e
          (println (str "ERRO ao criar administrador: " (.getMessage e))))))))

;;; ----------------------------------------------------------------
;;; Disponibilidade com exceções manuais
;;; ----------------------------------------------------------------
(defn- parse-instant [value]
  (cond
    (instance? Instant value) value
    (instance? Timestamp value) (.toInstant ^Timestamp value)
    (instance? OffsetDateTime value) (.toInstant ^OffsetDateTime value)
    (instance? ZonedDateTime value) (.toInstant ^ZonedDateTime value)
    (string? value) (try
                      (Instant/parse value)
                      (catch Exception _
                        (.toInstant (OffsetDateTime/parse value))))
    :else (throw (ex-info "Data/hora inválida" {:value value}))))

(defn- date-range-from-request [request]
  (let [params (:params request)
        now (Instant/now)
        start (if-let [value (get params "from")]
                (parse-instant value)
                now)
        end (if-let [value (get params "to")]
              (parse-instant value)
              (.plus start (Duration/ofDays 90)))]
    (when-not (.isBefore start end)
      (throw (ex-info "O início deve ser anterior ao fim." {})))
    {:start start :end end}))

(defn- slot-overlaps? [left right]
  (and (.isBefore (:start left) (:end right))
       (.isBefore (:start right) (:end left))))

(defn- db-slots [table psychologist-id start end]
  (jdbc/query db-spec
              [(str "SELECT * FROM " table
                    " WHERE psicologa_id = ? AND inicia_em < ? AND termina_em > ? ORDER BY inicia_em")
               (str psychologist-id)
               (Timestamp/from end)
               (Timestamp/from start)]))

(defn- row->slot [row source]
  {:start (parse-instant (:inicia_em row))
   :end (parse-instant (:termina_em row))
   :source source
   :id (:id row)
   :available (if (contains? row :disponivel) (:disponivel row) true)})

(def ^:private deep-time-zone (ZoneId/of "America/Sao_Paulo"))
(def ^:private weekday->schedule-key
  {DayOfWeek/MONDAY :seg
   DayOfWeek/TUESDAY :ter
   DayOfWeek/WEDNESDAY :qua
   DayOfWeek/THURSDAY :qui
   DayOfWeek/FRIDAY :sex
   DayOfWeek/SATURDAY :sab
   DayOfWeek/SUNDAY :dom})
(def ^:private legacy-slot-duration (Duration/ofMinutes 50))

(defn- legacy-schedule-for-psychologist [psychologist-id]
  (some-> (first (jdbc/query db-spec
                              ["SELECT horarios_disponiveis FROM horarios WHERE CAST(psicologa_id AS TEXT) = ?"
                               (str psychologist-id)]))
          :horarios_disponiveis
          pgobject->map))

(defn- google-calendar-connected? [psychologist-id]
  (boolean
    (first (jdbc/query db-spec
                       ["SELECT 1 FROM google_calendar_connections WHERE psicologa_id = ?"
                        (str psychologist-id)]))))

(defn- scheduled-times-for-day [schedule day-key]
  (or (get schedule day-key)
      (get schedule (name day-key))
      []))

(defn- legacy-schedule->slots [schedule start end]
  (let [first-day (.toLocalDate (.atZone start deep-time-zone))
        last-day (.toLocalDate (.atZone (.minusMillis end 1) deep-time-zone))]
    (loop [day first-day slots []]
      (if (.isAfter day last-day)
        slots
        (let [schedule-key (weekday->schedule-key (.getDayOfWeek day))
              day-slots (->> (scheduled-times-for-day schedule schedule-key)
                             (keep (fn [time]
                                     (try
                                       (let [slot-start (.toInstant (.atZone (LocalDateTime/of day (LocalTime/parse (str time)))
                                                                         deep-time-zone))
                                             slot-end (.plus slot-start legacy-slot-duration)
                                             slot {:start slot-start :end slot-end :source :manual-weekly}]
                                         (when (slot-overlaps? slot {:start start :end end})
                                           slot))
                                       (catch Exception _
                                         ;; Um valor inválido na grade manual não
                                         ;; derruba a disponibilidade pública.
                                         nil)))))
              ]
          (recur (.plusDays day 1) (into slots day-slots)))))))

(defn availability-for-psychologist [psychologist-id start end]
  (let [google-connected (google-calendar-connected? psychologist-id)
        automatic (map #(row->slot % :google)
                       (db-slots "availability_auto_slots" psychologist-id start end))
        ;; A grade semanal existente continua sendo o modo manual. Quando uma
        ;; agenda Google é vinculada, ela se torna a fonte principal para não
        ;; publicar uma disponibilidade manual desatualizada em paralelo.
        weekly-manual (if google-connected
                        []
                        (legacy-schedule->slots (or (legacy-schedule-for-psychologist psychologist-id) {})
                                                start end))
        overrides (map #(row->slot % :manual)
                       (db-slots "availability_manual_overrides" psychologist-id start end))
        blocks (filter #(false? (:available %)) overrides)
        visible-base-slots (remove #(some (partial slot-overlaps? %) blocks)
                                   (concat automatic weekly-manual))
        manual-openings (filter :available overrides)
        unique-slots (vals (reduce (fn [acc slot]
                                     (assoc acc [(:start slot) (:end slot)] slot))
                                   {}
                                   (concat visible-base-slots manual-openings)))]
    (sort-by :start unique-slots)))

(defn- public-slot [slot]
  {:start (str (:start slot))
   :end (str (:end slot))
   :available true})

(defn- availability-ids [start end]
  (let [weekly-ids (jdbc/query db-spec
                               ["SELECT psicologa_id FROM horarios ORDER BY psicologa_id"])
        auto-ids (jdbc/query db-spec
                             ["SELECT DISTINCT psicologa_id FROM availability_auto_slots WHERE inicia_em < ? AND termina_em > ?"
                              (Timestamp/from end) (Timestamp/from start)])
        manual-ids (jdbc/query db-spec
                               ["SELECT DISTINCT psicologa_id FROM availability_manual_overrides WHERE inicia_em < ? AND termina_em > ?"
                                (Timestamp/from end) (Timestamp/from start)])]
    (->> (concat weekly-ids auto-ids manual-ids)
         (map :psicologa_id)
         (map str)
         distinct
         sort)))

(defn public-availability-handler [request]
  (try
    (let [{:keys [start end]} (date-range-from-request request)
          requested-id (get-in request [:params "psychologistId"])
          ids (if (str/blank? requested-id) (availability-ids start end) [(str requested-id)])]
      (resp/response
        {:from (str start)
         :to (str end)
         :psychologists
         (mapv (fn [psychologist-id]
                 {:psychologistId psychologist-id
                  :slots (mapv public-slot
                               (availability-for-psychologist psychologist-id start end))})
               ids)}))
    (catch Exception e
      (-> (resp/response {:message (or (.getMessage e) "Intervalo de datas inválido.")})
          (resp/status 400)))))

(defn- create-manual-override! [psychologist-id account {:keys [start end available note]}]
  (let [slot-start (parse-instant start)
        slot-end (parse-instant end)]
    (when-not (.isBefore slot-start slot-end)
      (throw (ex-info "O término deve ser posterior ao início." {})))
    (jdbc/insert! db-spec :availability_manual_overrides
                  {:psicologa_id (str psychologist-id)
                   :inicia_em (Timestamp/from slot-start)
                   :termina_em (Timestamp/from slot-end)
                   :disponivel (boolean available)
                   :observacao (some-> note str str/trim)
                   :criado_por (:account-id account)
                   :criado_por_papel (name (:role account))})))

(defn create-my-override-handler [request]
  (with-role request #{:psychologist :admin}
    (fn [account]
      (try
        (let [psychologist-id (if (= :admin (:role account))
                                (or (get-in request [:body :psychologistId])
                                    (get-in request [:body :psicologaId]))
                                (:account-id account))]
          (if (str/blank? (some-> psychologist-id str))
            (-> (resp/response {:message "Informe a psicóloga."}) (resp/status 400))
            (do
              (create-manual-override! psychologist-id account (:body request))
              (-> (resp/response {:message "Exceção manual salva."}) (resp/status 201)))))
        (catch Exception e
          (-> (resp/response {:message (or (.getMessage e) "Não foi possível salvar a exceção.")})
              (resp/status 400)))))))

(defn delete-override-handler [request override-id]
  (with-role request #{:psychologist :admin}
    (fn [account]
      (let [where (if (= :admin (:role account))
                    ["id = ?" override-id]
                    ["id = ? AND psicologa_id = ?" override-id (:account-id account)])
            result (jdbc/delete! db-spec :availability_manual_overrides where)]
        (if (pos? (or (first result) 0))
          (resp/response {:message "Exceção manual removida."})
          (-> (resp/response {:message "Exceção não encontrada."}) (resp/status 404)))))))

;;; ----------------------------------------------------------------
;;; Google Agenda: OAuth central e sincronização automática
;;; ----------------------------------------------------------------
(defonce ^:private google-http-client (HttpClient/newHttpClient))
(defonce ^:private google-sync-executor (atom nil))

(defn- required-google-config []
  (let [config {:client-id (env :google-client-id)
                :client-secret (env :google-client-secret)
                :redirect-uri (env :google-redirect-uri)
                :token-key (env :google-token-encryption-key)}]
    (when (every? (comp not str/blank? val) config)
      config)))

(defn- form-encode [params]
  (->> params
       (remove (comp nil? val))
       (map (fn [[key value]]
              (str (URLEncoder/encode (name key) "UTF-8") "="
                   (URLEncoder/encode (str value) "UTF-8"))))
       (str/join "&")))

(defn- google-request! [request]
  (let [response (.send google-http-client request (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)
        body (.body response)
        parsed (when-not (str/blank? body) (json/parse-string body true))]
    (if (<= 200 status 299)
      parsed
      (throw (ex-info "A Google Agenda recusou a solicitação."
                      {:status status :google-error parsed})))))

(defn- google-post-form! [url params]
  (google-request!
    (-> (HttpRequest/newBuilder (URI/create url))
        (.header "Content-Type" "application/x-www-form-urlencoded")
        (.POST (HttpRequest$BodyPublishers/ofString (form-encode params)))
        (.build))))

(defn- google-get! [url access-token]
  (google-request!
    (-> (HttpRequest/newBuilder (URI/create url))
        (.header "Authorization" (str "Bearer " access-token))
        (.GET)
        (.build))))

(defn- google-token-key []
  (let [encoded (:token-key (required-google-config))
        key-bytes (try
                    (.decode (Base64/getDecoder) encoded)
                    (catch Exception _ nil))]
    (when-not (= 32 (count key-bytes))
      (throw (ex-info "GOOGLE_TOKEN_ENCRYPTION_KEY deve conter 32 bytes em Base64." {})))
    (SecretKeySpec. key-bytes "AES")))

(defn- encrypt-google-token [plaintext]
  (let [iv (byte-array 12)
        _ (.nextBytes (SecureRandom.) iv)
        cipher (Cipher/getInstance "AES/GCM/NoPadding")]
    (.init cipher Cipher/ENCRYPT_MODE (google-token-key) (GCMParameterSpec. 128 iv))
    (let [encrypted (.doFinal cipher (.getBytes plaintext StandardCharsets/UTF_8))
          encoder (.withoutPadding (Base64/getUrlEncoder))]
      (str (.encodeToString encoder iv) "." (.encodeToString encoder encrypted)))))

(defn- decrypt-google-token [payload]
  (let [[encoded-iv encoded-token] (str/split payload #"\." 2)
        decoder (Base64/getUrlDecoder)
        iv (.decode decoder encoded-iv)
        encrypted (.decode decoder encoded-token)
        cipher (Cipher/getInstance "AES/GCM/NoPadding")]
    (.init cipher Cipher/DECRYPT_MODE (google-token-key) (GCMParameterSpec. 128 iv))
    (String. (.doFinal cipher encrypted) StandardCharsets/UTF_8)))

(defn- oauth-state! [account]
  (let [state (str (UUID/randomUUID) "." (random-token))]
    (jdbc/insert! db-spec :google_oauth_states
                  {:state_hash (sha256-hex state)
                   :criado_por (:account-id account)
                   :expira_em (Timestamp/from (.plus (Instant/now) (Duration/ofMinutes 10)))})
    state))

(defn- consume-oauth-state! [state]
  (let [state-hash (sha256-hex state)
        stored (first (jdbc/query db-spec
                                  ["SELECT criado_por FROM google_oauth_states WHERE state_hash = ? AND expira_em > CURRENT_TIMESTAMP"
                                   state-hash]))]
    (when stored
      (jdbc/delete! db-spec :google_oauth_states ["state_hash = ?" state-hash])
      stored)))

(defn- google-authorization-url [state]
  (let [{:keys [client-id redirect-uri]} (required-google-config)]
    (str "https://accounts.google.com/o/oauth2/v2/auth?"
         (form-encode {:client_id client-id
                       :redirect_uri redirect-uri
                       :response_type "code"
                       :scope "https://www.googleapis.com/auth/calendar.readonly"
                       :access_type "offline"
                       :include_granted_scopes "true"
                       ;; Garante um refresh token na reconexão administrativa.
                       :prompt "consent"
                       :state state}))))

(defn- latest-google-account []
  (first (jdbc/query db-spec
                     ["SELECT refresh_token_criptografado FROM google_oauth_accounts WHERE chave = ?"
                      deep-google-account-key])))

(defn- save-google-refresh-token! [refresh-token scopes]
  (jdbc/execute! db-spec
                 ["INSERT INTO google_oauth_accounts (chave, refresh_token_criptografado, escopos, atualizado_em)
                   VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                   ON CONFLICT (chave) DO UPDATE SET
                     refresh_token_criptografado = EXCLUDED.refresh_token_criptografado,
                     escopos = EXCLUDED.escopos,
                     atualizado_em = CURRENT_TIMESTAMP"
                  deep-google-account-key (encrypt-google-token refresh-token) scopes]))

(defn- exchange-google-code! [code]
  (let [{:keys [client-id client-secret redirect-uri]} (required-google-config)]
    (google-post-form! "https://oauth2.googleapis.com/token"
                       {:code code
                        :client_id client-id
                        :client_secret client-secret
                        :redirect_uri redirect-uri
                        :grant_type "authorization_code"})))

(defn- google-access-token! []
  (let [{:keys [client-id client-secret]} (or (required-google-config)
                                              (throw (ex-info "A integração Google não foi configurada." {})))
        account (or (latest-google-account)
                    (throw (ex-info "A conta Google da Deep ainda não foi conectada." {})))
        refresh-token (decrypt-google-token (:refresh_token_criptografado account))
        response (google-post-form! "https://oauth2.googleapis.com/token"
                                    {:client_id client-id
                                     :client_secret client-secret
                                     :refresh_token refresh-token
                                     :grant_type "refresh_token"})]
    (:access_token response)))

(defn google-connect-handler [request]
  (with-role request #{:admin}
    (fn [account]
      (if (required-google-config)
        (resp/redirect (google-authorization-url (oauth-state! account)))
        (-> (resp/response {:message "Configure GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, GOOGLE_REDIRECT_URI e GOOGLE_TOKEN_ENCRYPTION_KEY."})
            (resp/status 503))))))

(defn google-connect-url-handler [request]
  (with-role request #{:admin}
    (fn [account]
      (if (required-google-config)
        (resp/response {:authorizationUrl (google-authorization-url (oauth-state! account))})
        (-> (resp/response {:message "Configure a integração Google no backend antes de conectar a conta."})
            (resp/status 503))))))

(defn google-oauth-callback-handler [request]
  (let [{:strs [code state error]} (:params request)
        admin-frontend-url (or (env :admin-frontend-url) "/")]
    (try
      (cond
        error (resp/redirect (str admin-frontend-url "?google=cancelled"))
        (or (str/blank? code) (str/blank? state))
        (-> (resp/response {:message "Resposta OAuth inválida."}) (resp/status 400))
        (nil? (consume-oauth-state! state))
        (-> (resp/response {:message "A autorização expirou ou já foi utilizada."}) (resp/status 400))
        :else
        (let [tokens (exchange-google-code! code)
              refresh-token (or (:refresh_token tokens)
                                (some-> (latest-google-account) :refresh_token_criptografado decrypt-google-token))]
          (if (str/blank? refresh-token)
            (-> (resp/response {:message "O Google não devolveu um refresh token. Reconecte a conta e aceite o acesso offline."})
                (resp/status 400))
            (do
              (save-google-refresh-token! refresh-token (:scope tokens))
              (resp/redirect (str admin-frontend-url "?google=connected"))))))
      (catch Exception e
        (println (str "ERRO OAuth Google: " (.getMessage e)))
        (-> (resp/response {:message "Não foi possível conectar a agenda Google."})
            (resp/status 502))))))

(defn- google-calendar-list! []
  (let [access-token (google-access-token!)]
    (loop [page-token nil calendars []]
      (let [url (str "https://www.googleapis.com/calendar/v3/users/me/calendarList?"
                     (form-encode (cond-> {:minAccessRole "reader" :maxResults 250}
                                    page-token (assoc :pageToken page-token))))
            response (google-get! url access-token)
            all-calendars (into calendars (:items response))]
        (if-let [next-page-token (:nextPageToken response)]
          (recur next-page-token all-calendars)
          all-calendars)))))

(defn google-calendars-handler [request]
  (with-role request #{:admin}
    (fn [_]
      (try
        (resp/response
          {:calendars (mapv (fn [calendar]
                              {:id (:id calendar)
                               :name (:summary calendar)
                               :accessRole (:accessRole calendar)})
                            (google-calendar-list!))})
        (catch Exception e
          (-> (resp/response {:message (or (.getMessage e) "Não foi possível listar as agendas Google.")})
              (resp/status 502)))))))

(defn- get-calendar-connection [psychologist-id]
  (first (jdbc/query db-spec
                     ["SELECT psicologa_id, calendar_id, calendar_nome, ultima_sincronizacao_em, ultimo_erro
                       FROM google_calendar_connections WHERE psicologa_id = ?"
                      (str psychologist-id)])))

(defn google-connections-handler [request]
  (with-role request #{:admin}
    (fn [_]
      (resp/response {:connected (boolean (latest-google-account))
                      :connections (jdbc/query db-spec
                                               ["SELECT psicologa_id, calendar_id, calendar_nome, ultima_sincronizacao_em, ultimo_erro
                                                 FROM google_calendar_connections ORDER BY psicologa_id"])}))))

(defn save-google-calendar-connection-handler [request]
  (with-role request #{:admin}
    (fn [_]
      (let [{:keys [psychologistId psicologaId calendarId calendarName]} (:body request)
            psychologist-id (or psychologistId psicologaId)]
        (try
          (if (or (str/blank? (some-> psychologist-id str)) (str/blank? calendarId))
            (-> (resp/response {:message "Informe psicóloga e agenda Google."}) (resp/status 400))
            (let [calendar (some #(when (= calendarId (:id %)) %) (google-calendar-list!))]
              (if-not calendar
                (-> (resp/response {:message "A agenda escolhida não pertence à conta Google conectada."}) (resp/status 400))
                (do
                  (jdbc/execute! db-spec
                                 ["INSERT INTO google_calendar_connections (psicologa_id, calendar_id, calendar_nome, atualizado_em)
                                   VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                                   ON CONFLICT (psicologa_id) DO UPDATE SET
                                     calendar_id = EXCLUDED.calendar_id,
                                     calendar_nome = EXCLUDED.calendar_nome,
                                     atualizado_em = CURRENT_TIMESTAMP"
                                  (str psychologist-id) calendarId (or calendarName (:summary calendar))])
                  (resp/response {:message "Agenda Google vinculada à psicóloga."})))))
          (catch Exception e
            (-> (resp/response {:message (or (.getMessage e) "Não foi possível salvar o vínculo.")})
                (resp/status 502))))))))

(defn- google-calendar-events! [calendar-id access-token start end]
  (loop [page-token nil events []]
    (let [query (cond-> {:timeMin (str start)
                         :timeMax (str end)
                         :singleEvents "true"
                         :orderBy "startTime"
                         :timeZone "America/Sao_Paulo"
                         :maxResults 2500}
                  page-token (assoc :pageToken page-token))
          url (str "https://www.googleapis.com/calendar/v3/calendars/"
                   (URLEncoder/encode calendar-id "UTF-8") "/events?" (form-encode query))
          response (google-get! url access-token)
          all-events (into events (:items response))]
      (if-let [next-page-token (:nextPageToken response)]
        (recur next-page-token all-events)
        all-events))))

(defn- google-event-period [event]
  (let [start (get-in event [:start :dateTime])
        end (get-in event [:end :dateTime])]
    (when (and start end)
      {:start (parse-instant start) :end (parse-instant end)})))

(def ^:private default-available-event-color-ids #{"7" "9"})

(defn- configured-available-event-color-ids []
  (let [configured (some-> (env :google-available-event-color-ids)
                           (str/split #","))
        color-ids (->> configured
                       (map str/trim)
                       (remove str/blank?)
                       set)]
    (if (seq color-ids)
      color-ids
      default-available-event-color-ids)))

(defn- normalize-calendar-text [value]
  (-> (Normalizer/normalize (str (or value "")) Normalizer$Form/NFD)
      (str/replace #"\p{M}" "")
      str/upper-case))

(defn- deep-available-event? [event]
  ;; Convenção operacional da Deep: o título identifica a intenção e a cor
  ;; confirma o status. IDs 7 (Pavão) e 9 (Azul/Blueberry) são aceitos por
  ;; padrão; a lista pode ser ajustada por ambiente sem alterar o código.
  (and (not= "cancelled" (:status event))
       (str/includes? (normalize-calendar-text (:summary event)) "[DISPONIVEL]")
       (contains? (configured-available-event-color-ids) (str (:colorId event)))
       (google-event-period event)))

(defn- google-events->available-slots [events]
  (let [regular-events (->> events
                            ;; Qualquer evento que não seja um bloco azul
                            ;; [DISPONÍVEL] bloqueia o intervalo: sessões,
                            ;; [INDISPONÍVEL], férias e compromissos pessoais.
                            (remove #(or (= "cancelled" (:status %)) (deep-available-event? %)))
                            (keep #(when-let [period (google-event-period %)] period)))]
    (->> events
         (filter deep-available-event?)
         (keep (fn [event]
                 (let [period (google-event-period event)]
                   (when-not (some #(slot-overlaps? period %) regular-events)
                     {:google-event-id (:id event) :start (:start period) :end (:end period)})))))))

(defn- sync-calendar-connection! [connection access-token start end]
  (let [calendar-id (:calendar_id connection)
        psychologist-id (str (:psicologa_id connection))]
    (try
      (let [events (google-calendar-events! calendar-id access-token start end)
            slots (google-events->available-slots events)]
        (jdbc/with-db-transaction [tx db-spec]
          ;; A consulta é completa para a janela futura. Portanto, remoções e
          ;; alterações na agenda também são refletidas no cache.
          (jdbc/delete! tx :availability_auto_slots ["calendar_id = ?" calendar-id])
          (doseq [slot slots]
            (jdbc/insert! tx :availability_auto_slots
                          {:calendar_id calendar-id
                           :google_event_id (:google-event-id slot)
                           :psicologa_id psychologist-id
                           :inicia_em (Timestamp/from (:start slot))
                           :termina_em (Timestamp/from (:end slot))}))
          (jdbc/update! tx :google_calendar_connections
                        {:ultima_sincronizacao_em (Timestamp/from (Instant/now))
                         :ultimo_erro nil
                         :atualizado_em (Timestamp/from (Instant/now))}
                        ["psicologa_id = ?" psychologist-id]))
        {:psychologistId psychologist-id :synced (count slots)})
      (catch Exception e
        (jdbc/update! db-spec :google_calendar_connections
                      {:ultimo_erro (or (.getMessage e) "Falha desconhecida")
                       :atualizado_em (Timestamp/from (Instant/now))}
                      ["psicologa_id = ?" psychologist-id])
        (throw e)))))

(defn sync-google-calendars! []
  (if-not (and db-spec (required-google-config) (latest-google-account))
    {:synced 0 :reason "Google não conectado ou não configurado."}
    (let [access-token (google-access-token!)
          start (Instant/now)
          end (.plus start (Duration/ofDays 90))
          connections (jdbc/query db-spec
                                  ["SELECT psicologa_id, calendar_id FROM google_calendar_connections ORDER BY psicologa_id"])]
      {:synced (mapv #(sync-calendar-connection! % access-token start end) connections)})))

(defn sync-google-calendars-handler [request]
  (with-role request #{:admin}
    (fn [_]
      (try
        (resp/response (sync-google-calendars!))
        (catch Exception e
          (println (str "ERRO na sincronização Google: " (.getMessage e)))
          (-> (resp/response {:message "Não foi possível sincronizar as agendas Google."})
              (resp/status 502)))))))

(defn- configured-sync-interval []
  (try
    (max 1 (Long/parseLong (or (env :google-sync-interval-minutes) "5")))
    (catch Exception _ 5)))

(defn start-google-sync! []
  (when (and (required-google-config) (nil? @google-sync-executor))
    (let [executor (Executors/newSingleThreadScheduledExecutor)
          interval (configured-sync-interval)
          task (reify Runnable
                 (run [_]
                   (try
                     (sync-google-calendars!)
                     (catch Exception e
                       (println (str "ERRO no sincronizador automático Google: " (.getMessage e)))))))]
      (when (compare-and-set! google-sync-executor nil executor)
        (.scheduleWithFixedDelay ^ScheduledExecutorService executor task 1 interval TimeUnit/MINUTES)))))

(defn stop-google-sync! []
  (when-let [executor @google-sync-executor]
    (.shutdownNow ^ScheduledExecutorService executor)
    (reset! google-sync-executor nil)))

;;; ----------------------------------------------------------------
;;; Handlers com contadores simples
;;; ----------------------------------------------------------------
(defn get-all-horarios-handler []
  (swap! request-counter inc)
  (let [schedules (get-all-schedules)]
    (resp/response schedules)))

(defn update-horarios-handler [request]
  (swap! request-counter inc)
  (with-role request #{:psychologist :admin}
    (fn [account]
      (let [{:keys [id horarios]} (:body request)
            psychologist-id (if (= :admin (:role account)) id (:account-id account))]
        (if (or (nil? psychologist-id) (nil? horarios))
          (-> (resp/response {:message "Informe a psicóloga e os horários."})
              (resp/status 400))
          (if (get-psychologist-by-id psychologist-id)
            (do
              (update-schedule! psychologist-id horarios)
              (resp/response {:message "Horários atualizados com sucesso!"}))
            (-> (resp/response {:message "Psicóloga não encontrada."})
                (resp/status 404))))))))

(defn create-psychologist-handler [request]
  (swap! request-counter inc)
  (with-role request #{:admin}
    (fn [_]
      (if (>= (count-psychologists) 10)
        (-> (resp/response {:message "Limite de 10 psicólogas atingido. Não é possível criar mais."})
            (resp/status 403))
        (let [{:keys [id nome senha]} (:body request)]
          (if (or (nil? id) (nil? nome) (nil? senha))
            (-> (resp/response {:message "Requisição inválida. Campos 'id', 'nome' e 'senha' são obrigatórios."})
                (resp/status 400))
            (try
              (jdbc/insert! db-spec :horarios
                            {:psicologa_id id
                             :nome nome
                             :senha_hash (hashers/derive senha)
                             :horarios_disponiveis (->jsonb {})})
              (-> (resp/response {:message "Psicóloga criada com sucesso!"})
                  (resp/status 201))
              (catch Exception e
                (-> (resp/response {:message (str "Erro ao criar psicóloga: " (.getMessage e))})
                    (resp/status 500))))))))))

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
    ;; Sessão da aplicação. A identidade autenticada é a fonte de verdade;
    ;; endpoints privados nunca confiam em um ID escolhido pelo navegador.
    (POST "/auth/login" request (login-handler request))
    (POST "/auth/logout" request (logout-handler request))
    (GET "/auth/me" request (current-account-handler request))

    ;; Contrato novo do site público: somente slots, sem dados de eventos.
    (GET "/availability" request (public-availability-handler request))

    ;; Exceções pontuais: a psicóloga só cria/remove as próprias; o admin pode
    ;; escolher a psicóloga no corpo da requisição.
    (POST "/me/availability-overrides" request (create-my-override-handler request))
    (DELETE "/me/availability-overrides/:override-id" [override-id :as request]
      (delete-override-handler request override-id))

    (context "/admin/google" []
      (GET "/connect" request (google-connect-handler request))
      (POST "/connect" request (google-connect-url-handler request))
      ;; Este callback é validado por state de uso único, emitido por um admin.
      (GET "/callback" request (google-oauth-callback-handler request))
      (GET "/calendars" request (google-calendars-handler request))
      (GET "/connections" request (google-connections-handler request))
      (POST "/connections" request (save-google-calendar-connection-handler request))
      (POST "/sync" request (sync-google-calendars-handler request)))

    ;; Rotas legadas: permanecem temporariamente para não interromper o site
    ;; atual. O gestor em staging será migrado para a sessão acima.
    (GET "/horarios" [] (get-all-horarios-handler))
    (POST "/horarios/editar" request (update-horarios-handler request))
    (POST "/horarios/criar" request (create-psychologist-handler request)))
  (route/not-found "Recurso não encontrado"))

;;; ----------------------------------------------------------------
;;; App com middlewares
;;; ----------------------------------------------------------------
(defn- configured-cors-origins []
  (let [raw (some-> (env :allowed-origins) (str/split #","))
        origins (->> raw (map str/trim) (remove str/blank?))]
    (if (seq origins)
      ;; wrap-cors compara cada Origin com re-matches, então os domínios
      ;; precisam ser Patterns (não Strings). Pattern/quote garante que a
      ;; comparação seja literal, sem os pontos virarem curinga.
      (mapv #(re-pattern (java.util.regex.Pattern/quote %)) origins)
      ;; Seguro para desenvolvimento local. Staging/produção deve declarar os
      ;; domínios reais em ALLOWED_ORIGINS, separados por vírgula.
      [#"^http://localhost(:[0-9]+)?$"])))

(def app
  (-> app-routes
      (wrap-cors :access-control-allow-origin (configured-cors-origins)
                 :access-control-allow-methods [:get :post :delete]
                 :access-control-allow-headers ["Content-Type" "Authorization"]
                 ;; String, não Boolean: ring.util.servlet quebra ao serializar
                 ;; um header com valor Boolean ("Don't know how to create ISeq").
                 :access-control-allow-credentials "true")
      (wrap-cookies)
      (wrap-params)
      (wrap-json-body {:keywords? true})
      (wrap-json-response)
      (instrument)))

;;; ----------------------------------------------------------------
;;; Função Principal
;;; ----------------------------------------------------------------
(declare init)

(defn -main [& args]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8080"))]
    (init)
    (println (str "Servidor iniciando na porta " port))
    (jetty/run-jetty app {:port port :join? false})))

(defn init []
  (println "Iniciando servidor...")
  (ensure-schema!)
  (ensure-admin-account!)
  (start-google-sync!))

(defn destroy []
  (println "Parando servidor...")
  (stop-google-sync!))
