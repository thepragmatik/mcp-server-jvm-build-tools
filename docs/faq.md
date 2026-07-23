# Frequently Asked Questions — mcp-server-jvm-build-tools

## Table of Contents

- [General](#general)
- [Build Tools](#build-tools)
- [Security](#security)
- [MCP Integration](#mcp-integration)
- [Troubleshooting](#troubleshooting)
- [Development](#development)

---

## General

### What is this project?

An MCP (Model Context Protocol) server that lets AI coding agents execute Maven, Gradle, and SBT build commands. One server for all three build tools, with automatic project type detection. Agents can compile, test, package, check dependencies, and validate build configurations — all through a conversation.

### Who is this for?

Developers using AI coding agents (Claude Desktop, Cursor, Cline, Windsurf, Goose, Continue, etc.) who want their agents to interact directly with JVM build tools instead of just generating code.

### Do I need to run this on a server?

No. It runs locally alongside your MCP client. It communicates over stdio (standard input/output) — no network ports, no HTTP endpoints, no cloud dependencies. Think of it as a local bridge between your AI agent and your build tools.

### Which Java version do I need?

Java 21 or later to build and run the server. The server JAR itself targets Java 21. Build tools (Maven, Gradle, SBT) may use their own Java versions, controlled by `JAVA_HOME`, `MAVEN_OPTS`, or `GRADLE_OPTS`.

### Can I run this without Docker?

Yes. The primary deployment model is a plain JAR: `java -jar mcp-server-jvm-build-tools.jar`. Docker is an alternative for containerized setups. See the [Quick Start](user-guide/installation.md) for both options.

### Is this production-ready?

The project is pre-1.0 (v0.2.x) but actively maintained with 401 tests, a CI matrix across JDK 21/23/25, and a layered security model. It's used in real agentic workflows. Breaking changes may occur before 1.0.

### What's the difference between this and arvindand/maven-tools-mcp?

This project supports Maven, Gradle, AND SBT through a unified interface with auto-detection. The DependencyService integrates with build tool detection — when you check a dependency version with a `projectDir`, it auto-detects the build tool and returns the correct dependency declaration syntax for that tool (Maven XML, Gradle string notation, or SBT libraryDependencies).

---

## Build Tools

### Why does Maven require buildToolHome but Gradle doesn't?

**Maven:** No built-in wrapper mechanism comparable to Gradle's `gradlew`. Maven must be installed and pointed to via `MAVEN_HOME` or `buildToolHome`. The server uses the Maven Shared Invoker API, which requires a Maven home directory.

**Gradle:** The `gradlew` wrapper script is a self-contained shell script that bootstraps Gradle. It's commonly checked into version control. The server detects `gradlew` in your project directory and uses it automatically. Falls back to `gradle` on PATH.

**SBT:** Like Gradle, SBT projects often include an `sbt` wrapper script. The server auto-detects it.

### Can I use a Maven wrapper (mvnw)?

Yes. The server detects `mvnw` in your project directory via `detect_build_tool`. However, `mvnw` is less common than `gradlew` and relies on Maven being available to bootstrap. The server uses MavenInvoker directly with a Maven home, so `mvnw` detection is informational only — Maven builds still need `buildToolHome`.

### Why are some commands blocked?

Security. The server enforces an allowlist of safe build commands and blocks dangerous flags that could execute arbitrary code. See [Security](reference/security.md) for the full security model.

**Allowed because they do build work:**
- `mvn clean compile test package` — standard lifecycle phases
- `gradle build test` — standard tasks
- `sbt compile test` — standard tasks

**Blocked because they could run arbitrary code:**
- `mvn exec:exec` — runs arbitrary system commands
- `gradle --init-script /evil/script` — runs arbitrary Groovy/Kotlin
- `sbt -J-Dsome.system.property` — injects arbitrary JVM flags into SBT's JVM

### How does auto-detection work?

`detect_build_tool` scans the project directory for marker files in this order:

1. **Maven:** `pom.xml`
2. **Gradle:** `build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts`
3. **SBT:** `build.sbt`

When multiple markers exist (hybrid project), the server detects all tools but prioritizes Maven for auto-detection when no tool name is specified. You can always explicitly pass `buildToolName` to override.

### What if my project uses a build tool not listed?

The server is extensible via the `BuildTool` SPI. To add support for Bazel, Ant, Mill, or another build tool, implement the `BuildTool` interface and register it with `BuildToolProvider`. See [Architecture](reference/architecture.md) for the extension guide.

### What's the difference between execute_build_command and analyze_build_output?

| | execute_build_command | analyze_build_output |
|---|---|---|
| Returns | Raw text (stdout) | Structured JSON |
| Parsed output | No | Yes — test counts, errors, warnings |
| Use case | Quick builds, version checks | CI analysis, error parsing |
| Build tools supported | Maven, Gradle, SBT | Maven, Gradle, SBT |

Use `execute_build_command` for simple builds where you just need the output text. Use `analyze_build_output` when you need machine-readable results — especially useful for agents that need to parse test failures and compilation errors.

### Does validate_build_configuration actually run the build?

No. It's a static analysis tool that reads build files and checks for syntax errors, missing required elements, duplicate dependencies, and other issues — without spawning a build process. It's fast, safe, and can catch problems before you waste time on a failed build.

Currently validates: `pom.xml` (XML well-formedness, required elements, duplicate deps, plugin version consistency), `build.gradle` (Groovy DSL), `build.gradle.kts` (Kotlin DSL). SBT `build.sbt` validation is not yet implemented.

---

## Security

### Can the LLM run arbitrary shell commands through my build tool?

No. The server enforces multiple layers of defense:

1. **Command allowlist** — only predefined build tasks are permitted
2. **Shell metacharacter blocking** — `&&`, `|`, `;`, `$()`, backticks, `>`, `<` are rejected
3. **Dangerous flag blocking** — flags like `--init-script`, `-D`, `-J` are blocked
4. **Path canonicalization** — `../` traversal is resolved and validated
5. **Process isolation** — Maven runs out-of-process, Gradle uses `--no-daemon`

### Can the LLM delete my files with `mvn clean`?

Yes. `mvn clean` deletes the `target/` directory (build outputs). This is an intentional build operation, not an attack. The server trusts the LLM operator to use build tools appropriately. It defends against malicious input injection, not against intentional build operations.

### What happens if the MCP client sends a malicious request?

The server validates every input parameter:
- Command length limited to 500 characters
- Regex pattern validation on all command tokens
- Path canonicalization via `Path.toRealPath()` (file must exist)
- Directory existence checks before execution

A maliciously crafted tool call would be rejected with an `IllegalArgumentException` before any process is spawned.

### Is the MCP transport encrypted?

Stdio transport is local — it runs within the same machine between the MCP client and the server process. It does not go over a network. The attack surface is MCP JSON-RPC messages, filesystem paths, and spawned processes. See the [Security](reference/security.md) page for the full threat model.

### What ports does the server open?

None. The server explicitly disables the web server (`spring.main.web-application-type=none`). It communicates solely over stdio.

---

## MCP Integration

### Which MCP clients are supported?

Any MCP client that supports stdio transport. Verified with:

- **Claude Desktop** — fully tested
- **Cursor** — MCP protocol compliance verified
- **Cline / Roo Code** — same config format as Claude Desktop
- **Windsurf** — Cascade MCP integration
- **Goose** — stdio transport support
- **Continue** — VS Code MCP integration
- **GitHub Copilot** — agent mode stdio support

### How do I verify the server is working?

1. Build the JAR: `mvn clean package -DskipTests`
2. Run it directly: `java -jar target/mcp-server-jvm-build-tools.jar`
3. It should print Spring Boot startup logs and then block (waiting for MCP input)
4. If it exits immediately, there's a startup error — check the output

Once configured in your MCP client, ask the agent:
```
"What build tools are available?"
```
The agent should call `list_build_tools` and return the list of tools and commands.

### Does the server work with multiple projects simultaneously?

Yes. The server is stateless — each `execute_build_command` call is independent with its own `projectDir`. An agent can build a Maven project, then immediately build a Gradle project, with no state leakage between them.

### Can I run multiple instances of the server?

Yes. Each MCP client configuration entry spawns a separate server process. You can have different configurations for different projects or build tool versions.

---

## Troubleshooting

### The server starts but my agent doesn't see the tools

This usually means the MCP handshake failed. Check:
1. The JAR path is absolute (relative paths don't work in most clients)
2. Your MCP client is fully restarted (not just reloaded)
3. Client logs for JSON-RPC errors

### Build succeeds in my terminal but fails via the MCP server

Common causes:
- The server sets the working directory to `projectDir` — if your project uses relative paths outside the project directory, they won't resolve
- The server's environment may differ from your terminal (PATH, JAVA_HOME, etc.)
- Gradle: the server uses `--no-daemon`, which may produce different results than a warmed-up daemon

### Maven build fails with "Cannot resolve MAVEN_HOME"

Set `MAVEN_HOME` in the MCP client `env` block:
```json
"env": {
  "MAVEN_HOME": "/opt/maven"
}
```
Or pass it explicitly in every `execute_build_command` call:
```
execute_build_command(buildToolHome="/opt/maven", projectDir="...", command="compile")
```

For full troubleshooting help, see the [Configuration](user-guide/configuration.md) guide.

---

## Development

### How do I add support for a new build tool?

Implement the `BuildTool` interface:

1. Create a new class implementing `BuildTool` (e.g., `BazelBuildTool`)
2. Implement all 6 methods: `getName()`, `version()`, `executeCommand()`, `isProject()`, `getSupportedCommands()`, `getExecutionPrompt()`
3. Register in `BuildToolProvider` constructor
4. Add detection hints in `BuildToolsService.detectBuildTool()` switch statement
5. Optionally create a `BuildOutputParser` implementation and register it
6. Add dependency syntax in `DependencyService.enrichWithProjectContext()`
7. Add tests

See [Architecture](reference/architecture.md) for the full extension guide.

### How do I run the tests?

```bash
cd mcp-server-jvm-build-tools
mvn verify
```

401 tests across 23 test classes covering security, functionality, integration, SBT project analysis, dependency conflict detection, build performance profiling, credential scanning, Java version compatibility, build cache health analysis, and MCP protocol compliance. Tests do NOT require actual Maven/Gradle/SBT installations — the security and validation tests are unit tests.

### What's the coverage?

67% instruction, 57% branch, 67% line coverage (JaCoCo), measured excluding the bootstrap class. Enforcement thresholds are configured in the POM and checked during `verify`: the build fails below 60% line or 50% branch coverage. The lower branch coverage is expected — many branches handle edge cases and error paths that are tested indirectly through security and integration tests.

### Where do I report bugs or request features?

[GitHub Issues](https://github.com/thepragmatik/mcp-server-jvm-build-tools/issues). Include:
- Server version
- MCP client and version
- Build tool and version
- Steps to reproduce
- Expected vs actual behavior

### How do I contribute?

See the [Agent Contributor Guide](AGENTS.md) for the development workflow.

### Can I use this project in my own product?

Yes. The project is licensed under Apache License 2.0. You can use, modify, and distribute it freely, including in commercial products. Attribution is appreciated.
