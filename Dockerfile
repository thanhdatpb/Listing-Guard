# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY core core
COPY rule-engine rule-engine
COPY rag-analyzer rag-analyzer
COPY api api
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -DskipTests -pl api -am package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /workspace/api/target/listing-guard-api.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
