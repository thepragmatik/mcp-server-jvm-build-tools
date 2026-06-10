# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build
RUN apk add --no-cache maven
COPY . /build
WORKDIR /build
RUN mvn package -DskipTests

# Runtime stage  
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache maven
COPY --from=build /build/target/mcp-server-jvm-build-tools.jar /app/mcp-server-jvm-build-tools.jar
WORKDIR /app
ENTRYPOINT ["java", "-Xms64m", "-Xmx256m", "-XX:+UseG1GC", "-XX:+UseStringDeduplication", "-XX:+ExitOnOutOfMemoryError", "-Djava.awt.headless=true", "-jar", "/app/mcp-server-jvm-build-tools.jar"]
