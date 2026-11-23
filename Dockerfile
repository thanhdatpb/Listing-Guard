# syntax=docker/dockerfile:1

FROM --platform=$BUILDPLATFORM eclipse-temurin:24-jdk AS build
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
COPY listing-input-adapters listing-input-adapters
COPY client client
COPY api api
COPY mcp-server mcp-server
COPY contract-tools contract-tools
COPY hf-convert hf-convert
# The release version the API sends as X-Listing-Guard-Version (ADR-022); the release workflow passes the tag without its v.
ARG LISTING_GUARD_RELEASE=dev
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -DskipTests -Dlisting-guard.release=${LISTING_GUARD_RELEASE} -pl api -am package

# The hf-convert job (ADR-030): its own jar and image, so DuckDB never reaches the API image. Built only with
# `--target hf-convert` (the compose services hf-convert do that); a plain build stops at the API image below.
FROM build AS hf-convert-build
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -DskipTests -pl hf-convert -am package

FROM --platform=$TARGETPLATFORM eclipse-temurin:24-jre AS hf-convert
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=hf-convert-build /workspace/hf-convert/target/listing-guard-hf-convert.jar hf-convert.jar
USER app
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/hf-convert.jar"]

# The API image: the last stage, so the default target.
FROM --platform=$TARGETPLATFORM eclipse-temurin:24-jre
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
