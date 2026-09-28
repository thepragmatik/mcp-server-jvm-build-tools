# JVM Build Tools MCP Server

A Java 21+ MCP server for Maven, Gradle, and sbt. It exposes 24 tools over stdio and Streamable HTTP. The 2.0 development line puts project boundaries, scoped HTTP access, and privacy controls on every model-visible tool call.

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

HTTP requests to `/mcp` validate the `Origin` header when present and reject invalid origins with 403. Loopback binds also reject non-local `Host` values to prevent DNS rebinding. If a local reverse proxy sends its own Host value, add its hostname to `mcp.transport.allowed-hosts` (comma-separated); keep `mcp.transport.cors.allowed-origins` restricted to trusted browser origins. CLI clients may omit `Origin`.

## What 2.0 changes

- Every path-bearing tool call is checked against canonical allowed roots. Relative project aliases resolve under the first root. Missing roots deny project access.
- HTTP bearer enforcement is on by default; keys have no scopes unless granted explicitly. Every exposed tool has an assigned scope and catalog drift fails a test.
- MCP tool results emit selected aggregate counts and up to 12 structured diagnostics with severity, failure category, per-result references, optional file type and line, and bounded normalized messages. Credential inspection, token validation, audit reading, and plan execution are withheld from the model-visible catalog while their ownership and privacy contracts are redesigned.
- Dependency analysis returns counts of dependencies, managed entries, and imported BOMs. CVE scanning returns scan and severity counts. Dependency coordinates, per-dependency classifications, and CVE identities stay local.
- Static tool callbacks and credential digests are cached. The container build reuses Maven dependencies, verifies downloaded Gradle/sbt archives, and runs as a nonroot user.

Build diagnostics retain recognized failure phrases while withholding arbitrary identifiers and values; unknown or unsafe text gets a server-authored fallback. A file reference only links diagnostics within one result; use local build output to find the file and symbol before editing. Other model-visible text still uses pattern redaction, which cannot prove arbitrary text contains no personal data. Review results before sharing them outside your trusted MCP client. For stronger isolation, run the server and builds in a container with a dedicated project mount and restricted network.

Read the [basic quickstart](docs/user-guide/quickstart-v2.md), [current tool catalog](docs/reference/tool-catalog.md), [2.0 configuration](docs/reference/configuration-v2.md), [migration guide](docs/user-guide/migration-v2.md), [architecture and design review](docs/reference/design-v2.md), [security model](docs/reference/security.md), and [agent contribution workflow](docs/AGENTS.md). The [release plan](docs/ROADMAP.md) tracks what remains before a stable 2.0 release.

Licensed under Apache 2.0.
