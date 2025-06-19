# Use a imagem oficial do Clojure com Leiningen
FROM clojure:lein-2.11.2

# Define o diretório de trabalho dentro do container
WORKDIR /usr/src/app

# Copia o arquivo de projeto primeiro para aproveitar o cache do Docker
COPY project.clj .

# Baixa as dependências. Se o project.clj não mudar, essa camada será reutilizada
RUN lein deps

# Copia o restante do código da aplicação
COPY . .

# Expõe a porta que o servidor irá rodar
EXPOSE 8080

# Comando para iniciar o servidor em modo de produção quando o container for iniciado.
# Render irá definir a variável de ambiente $PORT, que o lein-ring usará automaticamente.
CMD ["lein", "ring", "server-headless"]
