# JVM Build Tools MCP Server

A Java 21+ MCP server for Maven, Gradle, and sbt. It exposes 27 tools over stdio and Streamable HTTP. The 2.0 development line puts project boundaries, scoped HTTP access, and privacy controls on every model-visible tool call.

> Version: `2.0.0-SNAPSHOT`. The next proposed release is `v2.0.0-rc.1`. Older 1.x tool lists and examples live in [the changelog](CHANGELOG.md).

## Quick start

Build and verify with JDK 21 or newer:

```sh
./mvnw -B verify --no-transfer-progress
```

Start stdio with one allowed project tree. Keep this path in local process configuration. In MCP tool calls, use `projectDir: "."` for the root or a relative child such as `"app"`, so the absolute path need not enter the model conversation.

```sh
java -Dbuildtools.projects.allowed-roots=/workspace/projects -jar target/mcp-server-jvm-build-tools.jar
```

For a client configuration, use a local command and arguments:

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "java",
      "args": [
        "-Dbuildtools.projects.allowed-roots=/workspace/projects",
        "-jar",
        "/opt/jvm-build-tools/server.jar"
      ]
    }
  }
}
```

On HTTP, the server binds to `127.0.0.1` and requires a configured bearer key. Set `BUILDTOOLS_API_KEY_LOCAL` from a secret manager and give it only the scopes needed, for example `BUILDTOOLS_API_KEY_LOCAL_SCOPES=build:read,build:execute`. Then run:

```sh
java -Dbuildtools.projects.allowed-roots=/workspace/projects -jar target/mcp-server-jvm-build-tools.jar --spring.profiles.active=http
```

Send MCP requests to `POST /mcp` with the configured bearer key in the Authorization header. A non-loopback bind additionally requires an explicit project root, a configured key, authentication enforcement, and restricted CORS. Do not put a key or a private project path in a prompt, issue, commit, or build log.

## What 2.0 changes

- Every path-bearing tool call is checked against canonical allowed roots. Relative project aliases resolve under the first root. Missing roots deny project access.
- HTTP bearer enforcement is on by default; keys have no scopes unless granted explicitly. Every exposed tool has an assigned scope and catalog drift fails a test.
- MCP tool results emit bounded, redacted diagnostics and selected aggregate counts. Credential inspection, token validation, audit reading, and plan execution are withheld from the model-visible catalog while their ownership and privacy contracts are redesigned.
- Static tool callbacks and credential digests are cached. The container build reuses Maven dependencies, verifies downloaded Gradle/sbt archives, and runs as a nonroot user.

Pattern redaction handles common paths, emails, and secrets; it cannot prove arbitrary build text contains no personal data. Review generated diagnostics before sharing them outside your trusted MCP client. For stronger isolation, run the server and builds in a container with a dedicated project mount and restricted network.

Read the [basic quickstart](docs/user-guide/quickstart-v2.md), [architecture and design review](docs/reference/design-v2.md), [security model](docs/reference/security.md), and [agent contribution workflow](docs/AGENTS.md). The [release plan](docs/ROADMAP.md) tracks what remains before a stable 2.0 release.

Licensed under Apache 2.0.
