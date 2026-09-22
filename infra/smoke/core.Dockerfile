# Run the exact JAR produced by clean verify; mirror the production runtime.
FROM eclipse-temurin:21-jre
RUN groupadd --system libra && useradd --system --gid libra --home-dir /app libra
WORKDIR /app
COPY --chown=libra:libra services/core/target/core-service-0.1.0-SNAPSHOT.jar app.jar
USER libra
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
