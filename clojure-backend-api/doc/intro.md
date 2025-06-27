# Guia para o Projeto `clojure-backend-api`

Este documento serve como um guia detalhado para que a seja possivel entender e modificar o código do projeto `clojure-backend-api` de forma segura e eficaz.

## 1. Visão Geral

*   **Propósito do Projeto:** `clojure-backend-api` é uma API desenvolvida em Clojure que gerencia os horários de atendimento de psicólogas. Ela permite criar perfis para psicólogas, visualizar seus horários disponíveis e atualizar esses horários.
*   **Tecnologias Chave:**
    *   **Clojure:** Linguagem de programação funcional dinâmica que roda na JVM.
    *   **Ring:** Biblioteca Clojure para abstração de HTTP cliente/servidor. Usada como base para a construção da API.
    *   **Compojure:** Biblioteca de roteamento para Ring, usada para definir os endpoints da API.
    *   **clojure.java.jdbc:** Biblioteca para interagir com bancos de dados SQL a partir do Clojure.
    *   **PostgreSQL:** Banco de dados relacional utilizado para persistir os dados das psicólogas e seus horários. O campo de horários utiliza o tipo `JSONB` do PostgreSQL.
    *   **Leiningen:** Ferramenta de automação para projetos Clojure (gerenciamento de dependências, build, execução).
    *   **Buddy:** Biblioteca para hashing de senhas (`buddy/buddy-hashers`).
    *   **Cheshire:** Biblioteca para manipulação de JSON.
    *   **Ring-CORS:** Middleware para habilitar Cross-Origin Resource Sharing (CORS).
    *   **Metrics-Clojure:** Biblioteca para coletar métricas da aplicação.

## 2. Arquitetura

O sistema é uma API RESTful simples. O fluxo de uma requisição HTTP típica é o seguinte:

1.  **Requisição HTTP:** Um cliente (frontend, outra API, etc.) envia uma requisição HTTP para um dos endpoints definidos.
2.  **Middleware (Ring):** A requisição passa por uma série de middlewares:
    *   `instrument` (metrics-clojure-ring): Coleta métricas sobre a requisição.
    *   `wrap-json-response`: Converte automaticamente respostas Clojure (mapas) em JSON.
    *   `wrap-json-body`: Converte automaticamente corpos de requisição JSON em mapas Clojure (com chaves keyword `:keywords? true`).
    *   `wrap-cors`: Adiciona headers CORS para permitir requisições de diferentes origens.
3.  **Roteamento (Compojure):** Compojure direciona a requisição para o handler apropriado com base na URL e no método HTTP. As rotas principais estão definidas em `clojure-backend-api.core/app-routes`.
4.  **Handler da Rota:** Uma função Clojure específica (ex: `get-all-horarios-handler`, `update-horarios-handler`) processa a requisição.
    *   Pode realizar validações nos dados da requisição.
    *   Interage com o banco de dados através das funções em `clojure-backend-api.core` (ex: `get-all-schedules`, `update-schedule!`).
    *   Realiza lógica de negócios (ex: verificar senha, limite de psicólogas).
5.  **Interação com Banco de Dados (clojure.java.jdbc):**
    *   As funções de banco de dados constroem e executam queries SQL contra o PostgreSQL.
    *   A configuração do banco de dados é lida da variável de ambiente `DATABASE_URL`.
    *   Os horários são armazenados como `JSONB` e são convertidos para/de mapas Clojure usando as funções auxiliares `->jsonb` e `pgobject->map`.
6.  **Resposta HTTP:** O handler retorna uma estrutura de dados Clojure (geralmente um mapa) que representa a resposta.
7.  **Middleware (Ring):** O middleware `wrap-json-response` converte essa estrutura de dados em uma string JSON e a envia de volta ao cliente com o status HTTP apropriado e headers.

**Componentes Principais:**

*   `clojure-backend-api.core`: Namespace principal contendo toda a lógica da aplicação.
    *   Definição do `app` Ring com todos os middlewares.
    *   Definição das rotas (`app-routes`) usando Compojure.
    *   Handlers para cada rota.
    *   Funções de interação com o banco de dados.
    *   Funções auxiliares (ex: conversão JSONB).
    *   Configuração do banco de dados (`db-spec`).
    *   Lógica de métricas Prometheus.
*   `project.clj`: Arquivo de configuração do Leiningen, define dependências, plugins e configurações do projeto.
*   `Dockerfile`: Define como construir uma imagem Docker para a aplicação.

## 3. Configuração do Ambiente

*   **Variáveis de Ambiente:**
    *   `DATABASE_URL`: Essencial. URL de conexão com o banco de dados PostgreSQL.
        *   Formato esperado: `postgresql://usuario:senha@host:porta/database_name`
        *   Se o banco de dados requer SSL (como no Heroku ou Render), a string de conexão deve incluir `?ssl=true&sslmode=require`. O código já trata de adicionar isso se `DATABASE_URL` for fornecida.
    *   `PORT`: Opcional. Porta em que o servidor Jetty irá rodar. Padrão é `8080`.
*   **Desenvolvimento:**
    *   Pré-requisitos: Leiningen.
    *   Para rodar: `lein run -m clojure-backend-api.core`
*   **Produção (Docker):**
    *   Para construir a imagem: `docker build -t clojure-backend-api .`
    *   Para rodar o container: `docker run -p <host_port>:3000 -e DATABASE_URL="<sua_db_url>" clojure-backend-api` (o `project.clj` define a porta 8080 por padrão se `PORT` não estiver setada, mas o Dockerfile expõe 3000, então atenção a isso).

## 4. Módulos Principais (Detalhado)

O projeto é relativamente simples e a maior parte da lógica reside no namespace `clojure-backend-api.core`.

### 4.1. `clojure-backend-api.core`

Este é o coração da aplicação.

#### 4.1.1. Configuração do Banco de Dados (`db-spec`)

```clojure
(def db-spec
  (let [db-url (env :database-url)]
    (if db-url
      (str db-url "?ssl=true&sslmode=require") ; Adapta para produção com SSL
      (do
        (println "AVISO: DATABASE_URL não definida. O banco de dados não funcionará.")
        nil))))
```

*   Lê a URL do banco da variável de ambiente `DATABASE_URL` usando `environ.core/env`.
*   **Importante para IA:** Se `DATABASE_URL` não estiver definida, `db-spec` será `nil`, e as operações de banco de dados falharão ou retornarão dados vazios/padrão. A aplicação imprime um aviso no console.

#### 4.1.2. Funções Auxiliares JSONB

*   `->jsonb [data]`: Converte um mapa Clojure para um objeto `PGobject` do tipo `jsonb` para inserção/atualização no PostgreSQL.
    ```clojure
    (defn ->jsonb [data]
      (doto (PGobject.)
        (.setType "jsonb")
        (.setValue (json/generate-string data))))
    ```
*   `pgobject->map [pg-obj]`: Converte um `PGobject` (lido do banco) de volta para um mapa Clojure.
    ```clojure
    (defn- pgobject->map [pg-obj]
      (when pg-obj
        (-> pg-obj
            .getValue
            (json/parse-string true)))) ; true para keywords
    ```

#### 4.1.3. Funções de Banco de Dados

Estas funções encapsulam as queries SQL. Todas possuem tratamento básico de exceções que imprimem o erro no console e retornam um valor padrão (geralmente `nil` ou `[]`).

*   `count-psychologists []`: Retorna a contagem de registros na tabela `horarios`. Usado para o limite de 5 psicólogas.
    *   Em caso de erro, retorna `999` (um valor sentinela).
*   `get-all-schedules []`: Retorna uma lista de todos os horários.
    *   Seleciona `psicologa_id`, `nome`, `horarios_disponiveis` da tabela `horarios`.
    *   Converte `horarios_disponiveis` (JSONB) para mapa Clojure.
    *   **Modificação Importante (já implementada):** Garante que `:nome` seja "Nome não cadastrado" se for `nil` no banco.
    *   Retorna `[]` se `db-spec` for `nil` ou em caso de erro.
*   `get-psychologist-by-id [id]`: Busca uma psicóloga pelo `psicologa_id`.
    *   Seleciona `id` (chave primária da tabela, não o `psicologa_id` usado na API), `psicologa_id`, `nome`, `senha_hash`.
    *   Retorna o primeiro resultado ou `nil` se não encontrar ou em caso de erro.
*   `update-schedule! [id new-schedule]`: Atualiza os `horarios_disponiveis` de uma psicóloga.
    *   `new-schedule` (um mapa Clojure) é convertido para JSONB.
    *   Também atualiza o campo `atualizado_em` com o timestamp atual.
    *   Retorna o resultado do `jdbc/update!` (geralmente um vetor com o número de linhas afetadas) ou `nil` em caso de erro.

**Estrutura da Tabela `horarios` (Inferida):**

*   `id`: SERIAL PRIMARY KEY (inferido, pois `get-psychologist-by-id` seleciona `id`)
*   `psicologa_id`: VARCHAR ou TEXT, UNIQUE (usado como identificador na API)
*   `nome`: VARCHAR ou TEXT (nome da psicóloga)
*   `senha_hash`: VARCHAR ou TEXT (hash da senha)
*   `horarios_disponiveis`: JSONB (estrutura flexível para os horários, ex: `{"segunda": ["09:00", "10:00"], "terca": []}`)
*   `criado_em`: TIMESTAMP (inferido, boa prática)
*   `atualizado_em`: TIMESTAMP (explicitamente atualizado em `update-schedule!`)

#### 4.1.4. Handlers das Rotas

Os handlers são funções que recebem o objeto `request` do Ring e retornam uma resposta Ring (um mapa).

*   `get-all-horarios-handler []`:
    *   Chama `get-all-schedules`.
    *   Retorna `200 OK` com a lista de horários no corpo da resposta.
*   `update-horarios-handler [request]`:
    *   Espera `id` (da psicóloga), `senha` e `horarios` (novos horários) no corpo (`:body`) da requisição.
    *   Valida se os campos obrigatórios estão presentes (retorna `400 Bad Request` se não).
    *   Busca a psicóloga por `id` usando `get-psychologist-by-id`.
    *   Verifica a senha usando `buddy.hashers/check` contra o `senha_hash` armazenado.
    *   Se autenticada, chama `update-schedule!` para atualizar os horários.
    *   Retorna `200 OK` em caso de sucesso.
    *   Retorna `401 Unauthorized` se a psicóloga não for encontrada ou a senha estiver incorreta.
*   `create-psychologist-handler [request]`:
    *   Espera `id` (da psicóloga), `nome` e `senha` no corpo da requisição.
    *   Verifica se o limite de 5 psicólogas (`count-psychologists`) foi atingido (retorna `403 Forbidden` se sim).
    *   Valida se os campos obrigatórios (`id`, `nome`, `senha`) estão presentes (retorna `400 Bad Request` se não).
    *   Gera o hash da senha usando `buddy.hashers/derive`.
    *   Insere os dados na tabela `horarios` (`psicologa_id`, `nome`, `senha_hash`, e `horarios_disponiveis` como um JSONB vazio `{}`).
    *   Retorna `201 Created` em caso de sucesso.
    *   Retorna `500 Internal Server Error` se houver erro na inserção no banco.

#### 4.1.5. Métricas Prometheus

*   `simple-prometheus-metrics []`: Gera uma string formatada para Prometheus com as seguintes métricas:
    *   `http_requests_total`: Contador total de requisições (para os handlers instrumentados).
    *   `psychologists_total`: Gauge com o número atual de psicólogas no banco.
    *   `app_uptime_seconds`: Gauge com o tempo de atividade da aplicação.
    *   `app_info`: Gauge com informações da aplicação (versão, serviço, ambiente).
*   `prometheus-metrics-handler []`: Handler para a rota `/metrics` que retorna as métricas.

#### 4.1.6. Definição das Rotas (`app-routes`)

```clojure
(defroutes app-routes
  (GET "/health" [] {:status 200 :headers {"Content-Type" "text/plain"} :body "OK"})
  (GET "/metrics" [] (prometheus-metrics-handler))
  (context "/api" []
    (GET "/horarios" [] (get-all-horarios-handler))
    (POST "/horarios/editar" request (update-horarios-handler request))
    (POST "/horarios/criar" request (create-psychologist-handler request)))
  (route/not-found "Recurso não encontrado"))
```

*   `/health`: Endpoint básico de verificação de saúde.
*   `/metrics`: Endpoint para as métricas Prometheus.
*   `/api`: Contexto base para as rotas da API.
    *   `GET /api/horarios`: Lista todos os horários.
    *   `POST /api/horarios/editar`: Atualiza os horários de uma psicóloga (requer autenticação no corpo).
    *   `POST /api/horarios/criar`: Cria uma nova psicóloga.
*   `route/not-found`: Handler para qualquer rota não encontrada (retorna `404 Not Found`).

#### 4.1.7. Aplicação Ring (`app`)

```clojure
(def app
  (-> app-routes
      (wrap-cors :access-control-allow-origin [#".*"] ; Permite todas as origens
                 :access-control-allow-methods [:get :post]) ; Permite métodos GET e POST
      (wrap-json-body {:keywords? true}) ; Converte corpo JSON para mapa Clojure com keywords
      (wrap-json-response) ; Converte respostas de mapa Clojure para JSON
      (instrument))) ; Adiciona instrumentação de métricas
```
*   Define a pilha de middlewares que processa cada requisição antes de atingir os handlers das rotas. A ordem é importante (de baixo para cima na execução).

#### 4.1.8. Função Principal (`-main`)

```clojure
(defn -main [& args]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8080"))]
    (println (str "Servidor iniciando na porta " port))
    (jetty/run-jetty app {:port port :join? false})))
```
*   Ponto de entrada da aplicação quando executada com `lein run`.
*   Inicia um servidor web Jetty na porta especificada pela variável de ambiente `PORT` ou `8080` por padrão.

## 5. Testes (`test/clojure_backend_api/core_test.clj`)

*   Os testes unitários estão no arquivo `core_test.clj`.
*   **Estratégia de Mocking:** `with-redefs` é amplamente utilizado para substituir temporariamente funções (especialmente `clojure.java.jdbc/query`, `clojure.java.jdbc/insert!`, `clojure.java.jdbc/update!`) e variáveis (como `db-spec`) para isolar os testes da necessidade de um banco de dados real.
    *   **IA Deve Notar:** Ao adicionar novas funções que interagem com o banco ou outras dependências externas, testes similares com `with-redefs` devem ser criados.
*   **Cobertura:**
    *   Funções auxiliares (`->jsonb`, `pgobject->map`).
    *   Funções de banco de dados (casos de sucesso e erro).
    *   Handlers das rotas (diferentes cenários de requisição: válida, inválida, autorizada, não autorizada, etc.).
    *   Métricas Prometheus.
    *   Rota de health check e not-found.
*   **Como Rodar:** `lein test`

## 6. Como Contribuir/Modificar (Diretrizes para IA)

*   **Entendimento do Fluxo:** Antes de modificar, entenda o fluxo da requisição (Middleware -> Roteamento -> Handler -> Lógica de BD -> Resposta).
*   **Imutabilidade e Efeitos Colaterais:** Clojure favorece a imutabilidade. Funções que interagem com o banco de dados (`*!`) ou alteram estado (como `swap!` em átomos) são explicitamente nomeadas ou devem ser tratadas com cuidado.
*   **Consistência:** Mantenha a consistência com o estilo de código existente (formatação, nomenclatura).
*   **Testes:**
    *   **SEMPRE** adicione ou atualize testes para qualquer nova funcionalidade ou correção de bug.
    *   Utilize `with-redefs` para mockar dependências externas nos testes.
    *   Teste tanto os casos de sucesso quanto os de falha/erro.
*   **Tratamento de Erros:**
    *   Funções de banco de dados já possuem um `try-catch` básico. Se adicionar novas, considere um padrão similar.
    *   Handlers devem retornar respostas HTTP apropriadas para erros (400, 401, 403, 404, 500).
*   **Variáveis de Ambiente:** Esteja ciente das variáveis de ambiente (`DATABASE_URL`, `PORT`) e como elas afetam a aplicação.
*   **Segurança:**
    *   Para novas funcionalidades que envolvam dados sensíveis ou operações restritas, implemente verificações de autenticação/autorização apropriadas, similar ao `update-horarios-handler`.
    *   Continue usando `buddy/buddy-hashers` para senhas. **NUNCA** armazene senhas em texto plano.
*   **Manipulação de JSON:**
    *   Use `wrap-json-body` e `wrap-json-response` para conversão automática.
    *   Lembre-se que `wrap-json-body {:keywords? true}` converte chaves JSON para keywords Clojure.
    *   Para o campo `horarios_disponiveis` (JSONB), use `->jsonb` e `pgobject->map`.
*   **Limite de Psicólogas:** A funcionalidade `create-psychologist-handler` tem um limite hardcoded de 5 psicólogas. Se este limite precisar ser alterado, é aqui que a mudança deve ocorrer.
*   **Comentários:** Adicione comentários para explicar lógica complexa ou decisões de design.
*   **Arquivo `project.clj`:** Se adicionar novas dependências, adicione-as aqui e depois execute `lein deps` para baixá-las.

## 7. Glossário de Termos Específicos do Domínio

*   **Psicóloga ID (`psicologa_id`):** Identificador único textual para uma psicóloga, usado na API e no banco.
*   **Horários Disponíveis (`horarios_disponiveis`):** Estrutura JSON (armazenada como JSONB) que detalha os horários de trabalho de uma psicóloga. Exemplo: `{"segunda": ["09:00-10:00", "14:00-15:00"], "terca": []}`. O formato exato dentro do JSON é flexível, mas os exemplos no código usam um mapa onde as chaves são dias da semana (como strings ou keywords) e os valores são listas de strings representando os horários.
*   **Senha Hash (`senha_hash`):** Hash da senha da psicóloga, armazenado no banco para autenticação.

---

Este guia deve fornecer um bom ponto de partida para a IA entender o projeto. Se modificações significativas forem feitas, este documento deve ser atualizado.
