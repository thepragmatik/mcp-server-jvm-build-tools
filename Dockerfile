# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build

# Install Maven, Gradle, and SBT
RUN apk add --no-cache maven curl bash

# Install Gradle
ARG GRADLE_VERSION=8.12
RUN curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && rm /tmp/gradle.zip \
    && ln -s "/opt/gradle-${GRADLE_VERSION}/bin/gradle" /usr/local/bin/gradle

# Install SBT
ARG SBT_VERSION=1.10.11
RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" -o /tmp/sbt.tgz \
    && tar -xzf /tmp/sbt.tgz -C /opt \
    && rm /tmp/sbt.tgz \
    && ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt

COPY . /build
WORKDIR /build
RUN mvn package -DskipTests

# Runtime stage  
FROM eclipse-temurin:21-jre-alpine

# Install Maven, Gradle, and SBT
RUN apk add --no-cache maven curl bash

# Install Gradle
ARG GRADLE_VERSION=8.12
RUN curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && rm /tmp/gradle.zip \
    && ln -s "/opt/gradle-${GRADLE_VERSION}/bin/gradle" /usr/local/bin/gradle

# Install SBT
ARG SBT_VERSION=1.10.11
RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" -o /tmp/sbt.tgz \
    && tar -xzf /tmp/sbt.tgz -C /opt \
    && rm /tmp/sbt.tgz \
    && ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt

COPY --from=build /build/target/mcp-server-jvm-build-tools.jar /app/mcp-server-jvm-build-tools.jar
WORKDIR /app
ENTRYPOINT ["java", "-Xms64m", "-Xmx256m", "-XX:+UseG1GC", "-XX:+UseStringDeduplication", "-XX:+ExitOnOutOfMemoryError", "-Djava.awt.headless=true", "-jar", "/app/mcp-server-jvm-build-tools.jar"]
