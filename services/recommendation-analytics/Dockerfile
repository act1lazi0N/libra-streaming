FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY services/core/pom.xml services/core/pom.xml
COPY services/media/pom.xml services/media/pom.xml
COPY services/recommendation-analytics/pom.xml services/recommendation-analytics/pom.xml
RUN mvn -B -pl services/recommendation-analytics -am dependency:go-offline
COPY services/recommendation-analytics/src services/recommendation-analytics/src
RUN mvn -B -pl services/recommendation-analytics -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN groupadd --system libra && useradd --system --gid libra --home-dir /app libra
WORKDIR /app
COPY --from=build --chown=libra:libra /workspace/services/recommendation-analytics/target/recommendation-analytics-service-0.1.0-SNAPSHOT.jar app.jar
USER libra
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

