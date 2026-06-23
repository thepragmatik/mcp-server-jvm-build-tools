# Frequently Asked Questions

## General

### What is this project?

An MCP (Model Context Protocol) server that lets AI coding agents execute and inspect Maven,
Gradle, and SBT builds. One server for all three build tools, with automatic project-type
detection — agents can compile, test, package, check dependencies, and validate build
configurations through a conversation.

### Who is this for?

Developers using AI coding agents (Claude Desktop, Cursor, Cline, Windsurf, Goose, Continue,
GitHub Copilot, …) who want their agents to interact directly with JVM build tools instead of only
generating code.

### Do I need to run this on a server?

No. It runs locally alongside your MCP client over **stdio** (standard input/output) — no network
ports, no HTTP endpoints, no cloud dependencies. Think of it as a local bridge between your agent
and your build tools. An opt-in [Streamable HTTP transport](reference/security.md#transport-security)
exists for web deployments.

### Which Java version do I need?

Java 21 or later to build and run the server. The JAR targets Java 21. The build tools themselves
may use their own JVMs, controlled by `JAVA_HOME`, `MAVEN_OPTS`, or `GRADLE_OPTS`.

### Can I run this without Docker?

Yes. The primary deployment model is a plain JAR: `java -jar mcp-server-jvm-build-tools.jar`. Docker
is an alternative for containerised setups. See the [Installation guide](user-guide/installation.md).

### Is this production-ready?

The project is pre-1.0 and actively maintained, with a layered security model and a CI matrix
across JDK 21, 23, and 25. Breaking changes may occur before 1.0.

---

## Build tools

### Why does Maven require `buildToolHome` but Gradle doesn't?

- **Maven** has no wrapper-bootstrap equivalent to Gradle's `gradlew` in this server, so it must be
  installed and pointed to via `MAVEN_HOME` or `buildToolHome`. The server uses the Maven Shared
  Invoker API, which needs a Maven home directory.
- **Gradle** ships a self-contained `gradlew` wrapper; the server detects it in your project and
  uses it automatically, falling back to `gradle` on `PATH`.
- **SBT** likewise often includes an `sbt` wrapper, which the server auto-detects.

### Why are some commands blocked?

Security. The server enforces an allowlist of safe commands and blocks dangerous flags that could
execute arbitrary code. See the [Security reference](reference/security.md).

**Allowed (build work):** `mvn clean compile test package`, `gradle build test`, `sbt compile test`.

**Blocked (could run arbitrary code):** `mvn exec:exec`, `gradle --init-script /evil/script`,
`sbt -J-Dsome.system.property`.

### How does auto-detection work?

`detect_build_tool` scans the project directory for marker files in priority order:

1. **Maven:** `pom.xml`
2. **Gradle:** `build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts`
3. **SBT:** `build.sbt`

For hybrid projects, all tools are detected but Maven is prioritised when no tool name is given. You
can always pass `buildToolName` to override.

### What's the difference between `execute_build_command` and `analyze_build_output`?

| | `execute_build_command` | `analyze_build_output` |
|---|---|---|
| Returns | Raw text (stdout) | Structured JSON |
| Parsed output | No | Yes — test counts, errors, warnings |
| Use case | Quick builds, version checks | CI-style analysis, error parsing |

### Does `validate_build_configuration` actually run the build?

No. It is static analysis: it reads build files and checks for syntax errors, missing required
elements, duplicate dependencies, and similar issues — without spawning a build. It validates
`pom.xml`, `build.gradle`, and `build.gradle.kts`. SBT `build.sbt` validation is not yet implemented.

### What if my project uses a build tool that isn't listed?

The server is extensible via the `BuildTool` SPI. To add Bazel, Ant, Mill, etc., implement the
interface and register it. See the [Architecture reference](reference/architecture.md#extending-the-server).

---

## Security

### Can the LLM run arbitrary shell commands through my build tool?

No. Multiple layers stand in the way: a command allowlist, shell-metacharacter blocking, dangerous
flag blocking, path canonicalisation, and process isolation. See the
[Security reference](reference/security.md).

### Can the LLM delete my files with `mvn clean`?

`mvn clean` deletes the `target/` directory — an intentional build operation, not an attack. The
server trusts the operator to use build tools appropriately; it defends against *injection*, not
against allowed build operations.

### What ports does the server open?

None, by default. The server disables the web server (`spring.main.web-application-type=none`) and
communicates over stdio. The HTTP transport is opt-in and listens only when the `http` profile is
active.

### Is the MCP transport encrypted?

Stdio transport is local — it runs on the same machine between the client and the server process and
does not traverse a network. For the opt-in HTTP transport, place a TLS-terminating reverse proxy in
front. See the [Security reference](reference/security.md#transport-security).

---

## MCP integration

### Which MCP clients are supported?

Any client supporting stdio transport, including Claude Desktop, Cursor, Cline / Roo Code, Windsurf,
Goose, Continue, and GitHub Copilot (agent mode). See the
[Installation guide](user-guide/installation.md#step-2-configure-your-mcp-client).

### How do I verify the server is working?

1. Build the JAR: `mvn clean package -DskipTests`.
2. Run it: `java -jar target/mcp-server-jvm-build-tools.jar`.
3. It should print Spring Boot startup logs and then **block**, waiting for MCP input.
4. In your client, ask "What build tools are available?" — the agent should call `list_build_tools`.

### Does the server work with multiple projects simultaneously?

Yes. It is stateless — each `execute_build_command` carries its own `projectDir` with no state
leakage between calls. You can also run multiple server instances (one per client config entry).

---

## Troubleshooting

### The server starts but my agent doesn't see the tools

The MCP handshake likely failed. Check that: the JAR path is **absolute**; the client was **fully
restarted**; and the client logs show no JSON-RPC errors.

### Build succeeds in my terminal but fails via the server

Common causes: the working directory is `projectDir` (relative paths outside it won't resolve); the
server's environment differs from your shell (`PATH`, `JAVA_HOME`); and Gradle runs with
`--no-daemon`, which can differ from a warmed-up daemon.

### Maven build fails with a `MAVEN_HOME` error

Set `MAVEN_HOME` in the client `env` block, or pass `buildToolHome` explicitly in each Maven call.
See the [Installation guide](user-guide/installation.md#step-3-point-maven-at-an-installation).

---

## Development

### How do I add support for a new build tool?

Implement the `BuildTool` interface (`getName`, `version`, `executeCommand`, `isProject`,
`getSupportedCommands`, `getExecutionPrompt`), register it in `BuildToolProvider`, add a
`BuildOutputParser`, add detection hints in `detect_build_tool`, add dependency syntax in
`DependencyService`, and add tests. See the
[Architecture reference](reference/architecture.md#extending-the-server).

### How do I run the tests?

```bash
mvn -B verify
```

The suite spans security, functional, integration, SBT analysis, dependency-conflict, performance,
credential-scanning, Java-compatibility, and MCP protocol-compliance tests, and runs on a CI matrix
across JDK 21, 23, and 25. The unit and security tests do not require Maven/Gradle/SBT installations.

### Can I use this project in my own product?

Yes. It is licensed under the **Apache License 2.0** — use, modify, and distribute it freely,
including commercially. Attribution is appreciated.
