# clojure-backend-api

A simple Clojure backend server using Ring and Compojure.

## Prerequisites

- [Leiningen](https://leiningen.org/)
- [Docker](https://www.docker.com/) (for containerization)

## Development

To run the development server:

```bash
lein run -m clojure-backend-api.core
```

The server will start on `http://localhost:3000`.

## Docker

To build the Docker image:

```bash
docker build -t clojure-backend-api .
```

To run the Docker container:

```bash
docker run -p 3000:3000 clojure-backend-api
```
