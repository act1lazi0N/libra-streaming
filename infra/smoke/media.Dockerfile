# Run the exact Media JAR produced by clean verify on services/media/Dockerfile's runtime stage.
FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --system libra && useradd --system --gid libra --home-dir /app libra
WORKDIR /app
COPY --chown=libra:libra services/media/target/media-service-0.1.0-SNAPSHOT.jar app.jar
USER libra
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
