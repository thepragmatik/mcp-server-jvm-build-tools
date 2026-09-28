# syntax=docker/dockerfile:1.7
# Maven and JDK are supplied by the official image. Dependency downloads persist
# in a BuildKit cache across source edits; only the final jar enters the runtime image.
FROM maven:3.9.16-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B package -DskipTests --no-transfer-progress

FROM maven:3.9.16-eclipse-temurin-21-alpine
ARG GRADLE_VERSION=9.8.0
ARG SBT_VERSION=2.0.9

RUN apk add --no-cache bash curl unzip \
    && addgroup -S buildtools && adduser -S -G buildtools -h /home/buildtools buildtools

# Verify upstream checksums before extracting optional build tool launchers.
RUN set -eu; \
    curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip; \
    curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip.sha256" -o /tmp/gradle.sha256; \
    test "$(sha256sum /tmp/gradle.zip | cut -d ' ' -f1)" = "$(cat /tmp/gradle.sha256)"; \
    unzip -q /tmp/gradle.zip -d /opt; \
    ln -s "/opt/gradle-${GRADLE_VERSION}" /opt/gradle; \
    rm /tmp/gradle.zip /tmp/gradle.sha256

RUN set -eu; \
    url="https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz"; \
    curl -fsSL "$url" -o /tmp/sbt.tgz; \
    curl -fsSL "$url.sha256" -o /tmp/sbt.sha256; \
    test "$(sha256sum /tmp/sbt.tgz | cut -d ' ' -f1)" = "$(cut -d ' ' -f1 /tmp/sbt.sha256)"; \
    tar -xzf /tmp/sbt.tgz -C /opt; \
    rm /tmp/sbt.tgz /tmp/sbt.sha256

COPY --from=build /build/target/mcp-server-jvm-build-tools.jar /app/server.jar
RUN mkdir -p /workspace /home/buildtools/.m2 \
    && chown -R buildtools:buildtools /app /workspace /home/buildtools
ENV HOME=/home/buildtools
ENV PATH=/opt/gradle/bin:/opt/sbt/bin:${PATH}
WORKDIR /workspace
USER buildtools
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-Djava.awt.headless=true", "-jar", "/app/server.jar"]
