FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY services/core/pom.xml services/core/pom.xml
COPY services/media/pom.xml services/media/pom.xml
COPY services/recommendation-analytics/pom.xml services/recommendation-analytics/pom.xml
RUN mvn -B -pl services/core -am dependency:go-offline
COPY services/core/src services/core/src
RUN mvn -B -pl services/core -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN groupadd --system libra && useradd --system --gid libra --home-dir /app libra
WORKDIR /app
COPY --from=build --chown=libra:libra /workspace/services/core/target/core-service-0.1.0-SNAPSHOT.jar app.jar
USER libra
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

