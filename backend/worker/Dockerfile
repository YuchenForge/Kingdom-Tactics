# Build from repo `backend/` context (multi-module: engine + api + worker).
# compose: build.context=./backend, dockerfile=worker/Dockerfile
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY engine ./engine
COPY api ./api
COPY worker ./worker
RUN mvn -pl worker -am package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/worker/target/kingdom-tactics-worker-0.1.0.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
