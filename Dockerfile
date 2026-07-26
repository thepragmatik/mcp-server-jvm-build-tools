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

# Runtime stage — JDK base so Maven/Gradle/SBT can compile
FROM eclipse-temurin:21-jdk-alpine

# Install runtime utilities first (curl needed for Maven download)
RUN apk add --no-cache bash curl unzip

# Install Maven manually (avoids pulling in Alpine's JDK 25 via apk dependency)
ENV MAVEN_VERSION=3.9.11
RUN mkdir -p /usr/share/java && \
    curl -sSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
        -o /tmp/maven.tar.gz && \
    tar -xzf /tmp/maven.tar.gz -C /usr/share/java && \
    mv /usr/share/java/apache-maven-${MAVEN_VERSION} /usr/share/java/maven-3 && \
    ln -s /usr/share/java/maven-3/bin/mvn /usr/bin/mvn && \
    rm /tmp/maven.tar.gz

# Set JAVA_HOME and ensure JDK 21 is the default
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH=/usr/share/java/maven-3/bin:/opt/gradle/bin:/opt/sbt/bin:${JAVA_HOME}/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

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

COPY --from=build /build/target/mcp-server-jvm-build-tools.jar /app/mcp-server-jvm-build-tools.jar
WORKDIR /app
ENTRYPOINT ["java", "-Xms64m", "-Xmx256m", "-XX:+UseG1GC", "-XX:+UseStringDeduplication", "-XX:+ExitOnOutOfMemoryError", "-Djava.awt.headless=true", "-jar", "/app/mcp-server-jvm-build-tools.jar"]
