# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build
RUN apk add --no-cache maven
COPY . /build
WORKDIR /build
RUN mvn package -DskipTests

# Runtime stage  
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache maven curl unzip tar

# Install Gradle 9.6.1
RUN mkdir -p /opt/gradle && \
    curl -fsSL https://services.gradle.org/distributions/gradle-9.6.1-bin.zip -o /tmp/gradle.zip && \
    unzip -q /tmp/gradle.zip -d /opt/gradle && \
    rm /tmp/gradle.zip && \
    ln -s /opt/gradle/gradle-9.6.1/bin/gradle /usr/local/bin/gradle

# Install SBT 2.0.3
RUN mkdir -p /opt/sbt && \
    curl -fsSL https://github.com/sbt/sbt/releases/download/v2.0.3/sbt-2.0.3.tgz -o /tmp/sbt.tgz && \
    tar -xzf /tmp/sbt.tgz -C /opt/sbt && \
    rm /tmp/sbt.tgz && \
    ln -s /opt/sbt/sbt/bin/sbt /usr/local/bin/sbt

COPY --from=build /build/target/mcp-server-jvm-build-tools.jar /app/mcp-server-jvm-build-tools.jar
WORKDIR /app
ENTRYPOINT ["java", "-Xms64m", "-Xmx256m", "-XX:+UseG1GC", "-XX:+UseStringDeduplication", "-XX:+ExitOnOutOfMemoryError", "-Djava.awt.headless=true", "-jar", "/app/mcp-server-jvm-build-tools.jar"]
