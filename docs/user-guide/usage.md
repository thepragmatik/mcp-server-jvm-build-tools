# Usage

Once the server is [installed](installation.md) and configured in your MCP client, you interact
with it the way you interact with any MCP server: the agent **discovers** the tools, then **calls**
them on your behalf in response to your natural-language requests.

## The connection lifecycle

1. **Initialize.** Your MCP client launches the server (`java -jar …`) and performs the MCP
   `initialize` handshake over stdio. The server reports its name and version.
2. **Discover.** The client calls `tools/list`. The server returns the 28 registered tools, each
   with a name, description, and JSON input schema generated from its `@Tool`/`@ToolParam`
   annotations.
3. **Call.** When you ask for something, the agent picks a tool and issues a `tools/call` with the
   required arguments. The server validates the request, runs it, and returns the result.

```text
client ──initialize──▶ server          (handshake; name + version)
client ──tools/list──▶ server          (discover 28 tools + schemas)
client ──tools/call──▶ server          (e.g. execute_build_command {...})
client ◀──result──── server            (text or JSON payload)
```

## Talking to your agent

You don't construct tool calls by hand — you describe what you want, and the agent maps it to a
tool. Useful phrasings:

```text
What build tools are available?
Detect the build tool for /path/to/my-project
Build and test /path/to/my-project
Run 'clean test' on /path/to/my-maven-app
What is the latest version of com.google.guava:guava?
Validate the build configuration in /path/to/my-project
Are there dependency version conflicts in /path/to/my-project?
Is /path/to/my-project compatible with Java 17?
```

## Common starting points

| You want to… | The agent typically calls | Returns |
|--------------|---------------------------|---------|
| See what's available | `list_build_tools` | Registered tools and their commands |
| Identify a project's build tool | `detect_build_tool` | JSON with detected tool(s) and markers |
| Run a build and read the log | `execute_build_command` | Raw build output (text) |
| Run a build and parse results | `analyze_build_output` | Structured JSON (tests, errors, warnings) |
| Check a build file without building | `validate_build_configuration` | JSON validation report |
| Find the latest dependency version | `check_dependency_version` | JSON with latest/stable versions |

The complete catalogue — every tool, its inputs, and outputs — is in the
[Tools / MCP API reference](../reference/tools.md).

## How a tool call is shaped

Under the hood, a `tools/call` carries the tool `name` and an `arguments` object. For example,
running `clean test` on a Maven project looks like this on the wire:

```json
{
  "method": "tools/call",
  "params": {
    "name": "execute_build_command",
    "arguments": {
      "buildToolName": "maven",
      "buildToolHome": "/opt/maven",
      "projectDir": "/path/to/my-maven-app",
      "command": "clean test"
    }
  }
}
```

Key points that apply to most tools:

- **`projectDir` is almost always required** and must be an existing directory. The server
  canonicalises it with `Path.toRealPath()`, so it must resolve to a real path.
- **`buildToolName` is usually optional** — omit it to let the server auto-detect from project
  markers (`pom.xml` → Maven, `build.gradle*` → Gradle, `build.sbt` → SBT).
- **`buildToolHome` is required for Maven** unless `MAVEN_HOME` is set; it is optional for Gradle
  and SBT.
- **Commands must be allowlisted.** Only known phases/tasks run; unknown commands are rejected
  before any process starts.

## Build vs. analyse: choosing the right tool

!!! tip "`execute_build_command` vs. `analyze_build_output`"
    Both run a real build. The difference is the **return shape**:

    | | `execute_build_command` | `analyze_build_output` |
    |---|---|---|
    | Returns | Raw stdout text | Structured JSON |
    | Parsed output | No | Yes — test counts, errors with `file:line`, warnings |
    | Best for | Quick builds, version checks | CI-style analysis, agents that parse results |

    Use `analyze_build_output` when the agent needs to *reason about* failures programmatically;
    use `execute_build_command` when you just want the log.

## Build-tool command syntax

The server preserves each tool's native command syntax:

=== "Maven"

    Space-separated lifecycle phases:

    ```text
    clean compile test package
    ```

=== "Gradle"

    Space-separated tasks. `--no-daemon --console=plain` are added automatically — don't include
    them. Multi-module tasks use colon syntax (`:submodule:build`):

    ```text
    clean build test
    ```

=== "SBT"

    Semicolon-chained tasks (not spaces). `--no-colors` is added automatically:

    ```text
    clean;compile;test
    ```

## Working with multiple projects

The server is **stateless**: each call is independent and carries its own `projectDir`. An agent
can build a Maven project and then a Gradle project in succession with no state leakage. You can
also run multiple server instances — each MCP client config entry spawns its own process.

## Transport options: stdio and Streamable HTTP

Most users run the server over **stdio** (the default) — the MCP client launches `java -jar …` and
talks to it over stdin/stdout, with no network surface. For remote or shared deployments the server
also offers an opt-in **Streamable HTTP** transport.

=== "stdio (default)"

    Configured automatically by your MCP client (see [Installation](installation.md)). No port,
    no HTTP, no tokens — ideal for a local desktop agent.

=== "Streamable HTTP (opt-in)"

    Activate the `http` Spring profile (or pass `--http` to the launcher) and choose a port:

    ```bash
    java -Dspring.profiles.active=http -Dserver.port=8080 \
      -jar target/mcp-server-jvm-build-tools.jar
    ```

    The transport is **stateless** (MCP 2026-07-28 RC): no sessions, no `Mcp-Session-Id`, and no
    SSE-stream resumability, so it sits cleanly behind a load balancer — any replica can serve any
    request. It also exposes discovery surfaces: the server card
    (`GET /.well-known/mcp-server`), `server/discover` (`GET`/`POST /mcp/discover`), and OAuth
    Protected Resource Metadata (`GET /.well-known/oauth-protected-resource`). See the
    [Configuration reference](../reference/configuration.md#streamable-http-transport).

!!! tip "OAuth 2.1 integration for shared/remote deployments"
    Under the HTTP profile the server behaves as an **OAuth 2.1 resource server**. Bearer-token
    enforcement on `/mcp/**` is opt-in (`buildtools.oauth.resource-server.enabled=true`): clients
    then send `Authorization: Bearer <token>`, and a missing/invalid token gets a `401` with a
    `WWW-Authenticate` challenge pointing at the metadata document. For untrusted exposure, front
    the server with a TLS-terminating OAuth gateway. See the
    [Security reference](../reference/security.md#oauth-21-resource-server-http-transport).

## Verifying behaviour quickly

Ask the agent to call `list_build_tools` first. If it returns the Maven/Gradle/SBT commands, the
MCP handshake and tool discovery are working. If the agent reports no tools, the handshake failed —
check that the JAR path is absolute and that the client was fully restarted.

For concrete, end-to-end examples with sample responses, see [Examples](examples.md).
