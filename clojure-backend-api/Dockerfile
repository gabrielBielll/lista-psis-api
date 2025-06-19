# Use an official OpenJDK runtime as a parent image
FROM openjdk:11-jdk

# Install Leiningen
ENV LEIN_VERSION=2.9.10
ENV LEIN_INSTALL=/usr/local/bin/

WORKDIR /usr/local/bin/
RUN apt-get update && \
    apt-get install -y curl && \
    curl -fsSL https://raw.githubusercontent.com/technomancy/leiningen/${LEIN_VERSION}/bin/lein > lein && \
    chmod 0755 lein && \
    ./lein version && \
    apt-get purge -y --auto-remove curl && \
    rm -rf /var/lib/apt/lists/*

# Set the working directory in the container
WORKDIR /app

# Copy the project file and download dependencies
COPY project.clj .
RUN lein deps

# Copy the rest of the application code
COPY . .

# Expose port 3000
EXPOSE 3000

# Define environment variable
ENV PORT 3000

# Run the application
CMD ["lein", "run", "-m", "clojure-backend-api.core"]
