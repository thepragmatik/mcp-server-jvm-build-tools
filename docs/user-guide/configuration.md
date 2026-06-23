# Configuration

This page covers the configuration you need for everyday use. For an exhaustive list of every
property and its default, see the [Configuration Reference](../reference/configuration.md).

There are three places configuration comes from:

1. **Environment variables** — set in your MCP client's `env` block (toolchain locations, heap).
2. **JVM flags** — set in the client's `args` array (server heap, GC, system-property overrides).
3. **Spring Boot properties** — baked into the JAR via `application.properties`, overridable with
   `-D` flags.

## Environment variables

| Variable | Required | Purpose |
|----------|----------|---------|
| `MAVEN_HOME` | For Maven builds | Path to a Maven installation; the server expects `MAVEN_HOME/bin/mvn` to be executable. Without it, every Maven call must pass `buildToolHome`. |
| `MAVEN_OPTS` | No | JVM options for the **Maven** build process (e.g. `-Xmx1024m`). The server itself ignores this; only out-of-process Maven uses it. |
| `GRADLE_OPTS` | No | JVM options for the **Gradle** build process. |
| `JAVA_HOME` | No | Used by Maven/Gradle/SBT to locate a JVM if they cannot find one. |
| `PATH` | No | Used to locate `gradle`, `sbt`, and `java`. Extend it in `env` if those tools are not on the default `PATH`. |

```json
"env": {
  "MAVEN_HOME": "/opt/maven",
  "MAVEN_OPTS": "-Xmx1024m",
  "GRADLE_OPTS": "-Xmx1024m",
  "PATH": "/opt/gradle/bin:/opt/sbt/bin:/usr/local/bin:/usr/bin:/bin"
}
```

## Server JVM options

The server is a Spring Boot application. Tune its JVM in the client's `args`:

```json
"args": [
  "-Xms64m",
  "-Xmx256m",
  "-XX:+UseG1GC",
  "-XX:+UseStringDeduplication",
  "-XX:+ExitOnOutOfMemoryError",
  "-Djava.awt.headless=true",
  "-jar",
  "/absolute/path/to/mcp-server-jvm-build-tools.jar"
]
```

The server is thin: a 256 MB maximum heap is ample. These are the same values baked into the
Docker image's entrypoint.

## Build-tool JVM settings

| Build tool | How to set JVM options |
|------------|------------------------|
| Maven | `MAVEN_OPTS` (e.g. `-Xmx1024m -XX:MaxMetaspaceSize=256m`). |
| Gradle | `GRADLE_OPTS`, or `org.gradle.jvmargs` in the project's `gradle.properties`. |
| SBT | Configure in the project's build files. The server **blocks** `-J` flags, so SBT JVM tuning via the command line is not available. |

## The properties that keep stdio clean

These properties are pre-set in the shipped JAR and you normally should not change them. They are
listed here so you understand why the server behaves as it does.

```properties
# This is a stdio MCP server: no web server is started by default.
spring.main.web-application-type=none

# stdout carries MCP JSON-RPC only — the banner and console logs are suppressed.
spring.main.banner-mode=off
logging.pattern.console=
logging.level.org.springframework=WARN

# Accept forward-compatible MCP fields (e.g. "extensions") sent by some clients.
spring.jackson.deserialization.fail-on-unknown-properties=false
```

!!! danger "Do not write logs to stdout"
    Under the stdio transport, **stdout must contain only JSON-RPC messages**. Any banner text or
    console log written to stdout will corrupt the MCP protocol. If you need debug logging, send it
    elsewhere — see below.

### Turning on debug logging

To troubleshoot, raise the server's own log level via a `-D` flag in `args`:

```json
"args": [
  "-Dlogging.level.com.pragmatik.buildtools=DEBUG",
  "-jar",
  "/absolute/path/to/mcp-server-jvm-build-tools.jar"
]
```

These `-D` flags are JVM system properties for the **server** process — they are unrelated to the
Gradle/SBT `-D` flags that the server blocks for security.

## Optional: authorization and auditing

Authorization is **off by default**. When you enable it, MCP clients must present a valid API key
with the right scopes to call tools. Audit logging is **on by default**.

```properties
# Require API keys with appropriate scopes (default: false).
buildtools.auth.enabled=false
# 'permissive' = warn but allow; 'enforcing' = deny unauthorized calls.
buildtools.auth.mode=permissive
# Write newline-delimited JSON of tool invocations (default: true).
buildtools.audit.enabled=true
# Audit log location (default: ~/.buildtools/audit.log).
# buildtools.audit.path=/var/log/buildtools/audit.log
```

API keys are supplied as environment variables (or system properties):

```bash
export BUILDTOOLS_API_KEY_AGENT1=sk-your-key-value
export BUILDTOOLS_API_KEY_AGENT1_SCOPES=build:read,build:execute
```

The full scope catalogue and authorization behaviour are documented in the
[Security reference](../reference/security.md#authorization-optional).

!!! warning "Never commit secrets"
    Provide API keys through environment variables, never in source, commits, or logs.

## Streamable HTTP transport (opt-in)

By default the server uses stdio only. The HTTP transport is enabled by activating the `http`
Spring profile (the launcher's `--http` flag does this for you):

```bash
java -Dspring.profiles.active=http -Dserver.port=8080 \
  -jar target/mcp-server-jvm-build-tools.jar
```

Relevant properties (see the [Configuration Reference](../reference/configuration.md#streamable-http-transport)
for the complete set):

```properties
# Comma-separated CORS origins. Restricted to local origins by default (no wildcard).
# Widen for development by listing specific origins, e.g.:
#   mcp.transport.cors.allowed-origins=https://dashboard.example.com
# "*" is honoured for local testing only and must never be used in production.
mcp.transport.cors.allowed-origins=http://localhost:8080,http://127.0.0.1:8080
# SSE stream timeout in milliseconds (default: 30 minutes).
mcp.transport.sse.timeout-ms=1800000
# Maximum concurrent SSE subscribers.
mcp.transport.sse.max-subscribers=100
```

!!! tip "Production HTTP"
    Put the HTTP transport behind a TLS-terminating reverse proxy and restrict
    `mcp.transport.cors.allowed-origins` to known domains. See the
    [Security reference](../reference/security.md#transport-security).

Continue to [Usage](usage.md) to start invoking tools.
