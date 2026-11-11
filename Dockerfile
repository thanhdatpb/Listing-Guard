# syntax=docker/dockerfile:1

FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY core core
COPY rule-engine rule-engine
COPY rag-analyzer rag-analyzer
COPY trademark-scanner trademark-scanner
COPY variation-checks variation-checks
COPY personalization-screening personalization-screening
COPY image-checker image-checker
COPY api api
COPY mcp-server mcp-server
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -DskipTests -pl api -am package

FROM --platform=$TARGETPLATFORM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /workspace/api/target/listing-guard-api.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
