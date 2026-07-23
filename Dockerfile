# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build

# Install Maven and utilities
RUN apk add --no-cache maven bash curl unzip

# Install Gradle (latest stable: 9.6.1)
ENV GRADLE_VERSION=9.6.1
RUN curl -sSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip && \
    unzip -q /tmp/gradle.zip -d /opt && \
    ln -s /opt/gradle-${GRADLE_VERSION} /opt/gradle && \
    rm /tmp/gradle.zip

# Install SBT (latest stable: 2.0.3)
ENV SBT_VERSION=2.0.3
RUN curl -sSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" -o /tmp/sbt.tgz && \
    tar -xzf /tmp/sbt.tgz -C /opt && \
    rm /tmp/sbt.tgz

ENV PATH=/opt/gradle/bin:/opt/sbt/bin:$PATH

COPY . /build
WORKDIR /build
RUN mvn package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

# Install Maven and utilities
RUN apk add --no-cache maven bash curl unzip

# Install Gradle (latest stable: 9.6.1)
ENV GRADLE_VERSION=9.6.1
RUN curl -sSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip && \
    unzip -q /tmp/gradle.zip -d /opt && \
    ln -s /opt/gradle-${GRADLE_VERSION} /opt/gradle && \
    rm /tmp/gradle.zip

# Install SBT (latest stable: 2.0.3)
ENV SBT_VERSION=2.0.3
RUN curl -sSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" -o /tmp/sbt.tgz && \
    tar -xzf /tmp/sbt.tgz -C /opt && \
    rm /tmp/sbt.tgz

ENV PATH=/opt/gradle/bin:/opt/sbt/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

COPY --from=build /build/target/mcp-server-jvm-build-tools.jar /app/mcp-server-jvm-build-tools.jar
WORKDIR /app
ENTRYPOINT ["java", "-Xms64m", "-Xmx256m", "-XX:+UseG1GC", "-XX:+UseStringDeduplication", "-XX:+ExitOnOutOfMemoryError", "-Djava.awt.headless=true", "-jar", "/app/mcp-server-jvm-build-tools.jar"]
