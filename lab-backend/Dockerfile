# Builds the lab backend (Quarkus + React UI) without needing Java or Node on the host.
# Build context is the repository root (see docker-compose.yml).
FROM docker.io/library/maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY lab-backend/pom.xml lab-backend/
COPY lab-backend/src lab-backend/src
# Quinoa downloads Node.js itself here, so the image needs no Node installation.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -DskipTests \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=20.18.0

FROM docker.io/library/eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/lab-backend/target/quarkus-app/ ./
EXPOSE 8080
ENTRYPOINT ["java", "-Dquarkus.http.host=0.0.0.0", "-jar", "quarkus-run.jar"]
