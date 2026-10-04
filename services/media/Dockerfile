FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY services/core/pom.xml services/core/pom.xml
COPY services/media/pom.xml services/media/pom.xml
COPY services/recommendation-analytics/pom.xml services/recommendation-analytics/pom.xml
RUN mvn -B -pl services/media -am dependency:go-offline
COPY services/media/src services/media/src
RUN mvn -B -pl services/media -am package -DskipTests

FROM eclipse-temurin:21-jre
# ffprobe only inspects frozen sources (M19). The distribution package is the single trusted executable; its path is
# passed to the service explicitly and nothing is looked up on PATH.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --system libra && useradd --system --gid libra --home-dir /app libra
WORKDIR /app
COPY --from=build --chown=libra:libra /workspace/services/media/target/media-service-0.1.0-SNAPSHOT.jar app.jar
USER libra
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

