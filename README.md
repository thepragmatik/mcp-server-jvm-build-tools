# One MCP Server for ALL Your JVM Build Tools

[![CI](https://github.com/thepragmatik/mcp-server-jvm-build-tools/actions/workflows/ci.yml/badge.svg)](https://github.com/thepragmatik/mcp-server-jvm-build-tools/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange)](https://adoptium.net/)

## Table of Contents

- [Using with Agentic AI Solutions](#using-with-agentic-ai-solutions)
- [Why Use This Server](#why-use-this-server)
- [Supported Build Tools](#supported-build-tools)
- [All Available Tools](#all-available-tools)
- [Quick Start](#quick-start)
- [Installation](#installation)
- [Examples](#examples)
- [Security](#security)
- [Project Philosophy](#project-philosophy)
- [CI/CD](#cicd)
- [Contributing](#contributing)
- [License](#license)

Run Maven and Gradle builds through any MCP-compatible LLM client (Claude Desktop, Goose, Continue, etc.). One server, two build tools, auto-detection of your project type — no switching servers, no manual config per project.

```
You:     "Build and test this project"
Claude:  → execute_build_command(projectDir="...", command="clean test")
Server:  → detects pom.xml → mvn clean test ✓
You:     "Now build the Gradle project next door"
Claude:  → execute_build_command(projectDir=".../other", command="build")
Server:  → detects build.gradle.kts → gradle build ✓
```

## Using with Agentic AI Solutions

### How AI Agents Use Build Tools

AI coding agents are transforming how developers work — they can write code, refactor modules, and manage entire projects autonomously. But most agents stop at the code level. This MCP server gives agents **hands-on access to your build pipeline**: they can compile, test, package, check dependency versions, and detect project types — all without you running a single terminal command.

An agent equipped with build tools can:

- **Detect** which build system a project uses (Maven or Gradle) automatically
- **Compile and test** code it just wrote, catching errors in the same session
- **Parse build failures**, fix the root cause, and retry — a true closed-loop workflow
- **Inspect dependency versions** to suggest upgrades or resolve conflicts
- **Package artifacts** for deployment, all from within the agent conversation

### MCP Client Compatibility

This server uses standard MCP stdio transport and has been verified via automated protocol compliance testing:

| Test | Result |
|---|---|
| `initialize` handshake | ✅ PASS |
| `tools/list` discovery (7 tools) | ✅ PASS |
| `tools/call` get_build_tool_version | ✅ PASS |
| `tools/call` list_build_tools | ✅ PASS |
| `tools/call` detect_build_tool | ✅ PASS |

**Status: MCP stdio compliant.** Compatible with any MCP client supporting stdio transport (Claude Desktop, Cursor, Cline, Windsurf, Goose, Continue, GitHub Copilot agent mode, and others).

### MCP Client Configuration

#### Claude Desktop

Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "/path/to/java",
      "args": [
        "-jar",
        "/path/to/mcp-server-jvm-build-tools.jar"
      ],
      "env": {
        "MAVEN_HOME": "/opt/maven"
      }
    }
  }
}
```

Set `MAVEN_HOME` in your environment or in the `env` block above. Gradle works with the wrapper or a system Gradle on PATH — no extra config needed.

#### Cursor

Create `.cursor/mcp.json` in your project root:

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/mcp-server-jvm-build-tools.jar"
      ],
      "env": {
        "MAVEN_HOME": "/opt/maven"
      }
    }
  }
}
```

Restart Cursor after adding the config. The build tools will appear in the agent's tool palette automatically.

#### Cline / Roo Code

Add to `cline_mcp_settings.json` (VS Code user settings or `.vscode/` in your workspace):

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/mcp-server-jvm-build-tools.jar"
      ],
      "env": {
        "MAVEN_HOME": "/opt/maven"
      }
    }
  }
}
```

Cline and Roo Code share the same MCP configuration format. Both extensions will pick up the server on next activation.

#### Windsurf

Configure in Windsurf's Cascade MCP settings (Settings → Cascade → MCP Servers) or via `.windsurfrules`:

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/mcp-server-jvm-build-tools.jar"
      ],
      "env": {
        "MAVEN_HOME": "/opt/maven"
      }
    }
  }
}
```

The MCP server integrates directly with Cascade's agentic workflows.

#### Goose / Continue

Both Goose and Continue support the standard MCP stdio transport. Use the same command/args configuration as Claude Desktop above.

For **Goose**, add to your Goose MCP config (usually `~/.config/goose/mcp.json` or via the Goose UI).

For **Continue**, add to `~/.continue/config.json` under the `mcpServers` key:

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/mcp-server-jvm-build-tools.jar"],
      "env": {
        "MAVEN_HOME": "/opt/maven"
      }
    }
  }
}
```

### Example: Agent-Driven Build & Fix Workflow

Here's a realistic multi-step workflow showing how an AI agent uses the server to build, detect errors, and fix them — all autonomously:

```
User:   "Build my Maven project and fix any compilation errors"

Agent:  get_build_tool_version(buildToolName="maven")
        → "Apache Maven 3.9.9"

Agent:  detect_build_tool(projectDir="/home/dev/my-app")
        → Detected: maven (found pom.xml)

Agent:  execute_build_command(
          buildToolHome="/opt/maven",
          projectDir="/home/dev/my-app",
          command="clean compile"
        )
        → [ERROR] Compilation failure:
          PaymentService.java:[42,18] cannot find symbol: class PaymentGateway

Agent:  check_dependency_version(
          projectDir="/home/dev/my-app",
          dependency="com.example:payment-lib"
        )
        → com.example:payment-lib:2.1.0 (compile scope)

        (Agent reads PaymentService.java, finds missing import)
        (Agent adds: import com.example.payment.PaymentGateway;)

Agent:  execute_build_command(
          buildToolHome="/opt/maven",
          projectDir="/home/dev/my-app",
          command="compile"
        )
        → BUILD SUCCESS

Agent:  "Compilation fixed! The PaymentService.java file was missing an import
        for PaymentGateway from the payment-lib dependency (v2.1.0).
        I've added the import and the project now compiles cleanly."
```

The agent detected the project type, ran the build, parsed the error, checked the dependency version to confirm the class should exist, fixed the source file, and rebuilt — all without you touching a terminal.

## Why Use This Server

- **One server for Maven, Gradle, and SBT builds** — no switching between servers, no separate configs. A single MCP server covers your entire JVM project portfolio.
- **Auto-detection** — drop the server into any project directory and it detects `pom.xml`, `build.gradle(.kts)`, or `build.sbt` automatically. No manual tool selection per project.
- **Structured build output** — parse errors, test counts, and warnings into structured data that AI agents can act on directly, not raw terminal dumps.
- **Build validation** — catch issues before executing. Commands are validated against allowed sets, paths are canonicalized, and shell injection attempts are blocked before they reach the build tool.
- **Dependency version lookups via Maven Central** — check latest versions and stability of any published artifact without leaving the conversation.
- **Security hardening** — layered defense: command allowlisting, shell metacharacter blocking, path canonicalization, input validation, and process isolation.

## Supported Build Tools

| Tool | Detection | Home Required | Fallback |
|---|---|---|---|
| Maven | `pom.xml` | Yes (`buildToolHome` or `MAVEN_HOME`) | — |
| Gradle | `build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts` | No | `gradlew` in project → `gradle` on PATH |
| SBT | `build.sbt` | No | `sbt` on PATH |

| Feature | Maven | Gradle | SBT |
|---|---|---|---|
| Execute builds | ✓ 7 lifecycle phases | ✓ 11 tasks | ✓ |
| Version query | ✓ (embedder, no external process) | ✓ (CLI) | ✓ (CLI) |
| Command allowlisting | ✓ | ✓ | ✓ |
| Shell injection protection | ✓ | ✓ | ✓ |
| Path canonicalization | ✓ | ✓ | ✓ |
| Multi-module projects | ✓ | ✓ (`:subproject:task`) | ✓ |

When multiple build markers exist (e.g., a project with both `pom.xml` and `build.gradle`), the server auto-detects Maven first. You can always explicitly specify which tool to use.

## All Available Tools

### `get_build_tool_version`
Get the installed version of any registered build tool.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `buildToolName` | string | Yes | `"maven"`, `"gradle"`, or `"sbt"` |

### `execute_build_command`
Execute a build command with automatic tool detection.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `buildToolName` | string | No | `"maven"`, `"gradle"`, or `"sbt"`. Omit to auto-detect from project files. |
| `buildToolHome` | string | No | Path to build tool installation. Required for Maven, optional for Gradle/SBT (uses wrapper or PATH). |
| `projectDir` | string | Yes | Path to the project directory containing build files. |
| `command` | string | Yes | Build command. Maven: `clean compile`, `test`, `package`, etc. Gradle: `build`, `test`, `clean`, etc. SBT: `compile`, `test`, `package`, etc. |

**Supported Maven commands:** `clean`, `compile`, `test`, `package`, `install`, `deploy`, `validate`, `dependency:tree` (+ safe flags: `-D`, `-f`, `-P`, `-q`, `-X`, `-T`, `-B`, `-U`, `--batch-mode`, `--non-recursive`)

**Supported Gradle commands:** `clean`, `build`, `test`, `compileJava`, `compileTestJava`, `jar`, `assemble`, `check`, `publishToMavenLocal`, `dependencies`, `projects`, `tasks` (+ safe flags: `-x`, `--exclude-task`, `--parallel`, `--configure-on-demand`, `--build-cache`)

**Supported SBT commands:** `compile`, `test`, `package`, `clean`, `run`, `assembly`

### `list_build_tools`
List all registered build tools and their supported commands. Returns formatted string like:
```
maven: clean, compile, test, package, install, deploy, validate, dependency:tree
gradle: clean, build, test, compileJava, compileTestJava, jar, assemble, check, publishToMavenLocal, dependencies, projects, tasks
sbt: compile, test, package, clean, run, assembly
```

### `detect_build_tool`
Auto-detect which build tool a project uses by scanning for build files in the project directory.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory to scan for build files. |

**Returns:** JSON object with detected tools, matched marker files, wrapper availability, and project structure hints. Example: `{"status":"success","projectDir":"/path/to/project","detections":[{"tool":"maven","matchedFiles":["pom.xml"],"wrapperFiles":[],"hints":["POM-based project"]}]}`

**Detection order:** Checks for `pom.xml` first (Maven), then `build.gradle`/`build.gradle.kts` (Gradle), then `build.sbt` (SBT).

### `check_dependency_version`
Look up the latest version of a Maven Central dependency.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `groupId` | string | Yes | Maven group ID (e.g., `com.google.guava`) |
| `artifactId` | string | Yes | Maven artifact ID (e.g., `guava`) |
| `currentVersion` | string | No | Current version to compare against |
| `stabilityFilter` | string | No | `ALL`, `STABLE_ONLY` (default), or `PREFER_STABLE` |
| `projectDir` | string | No | Project directory for build-tool context |

**Example:**
```
check_dependency_version(groupId="com.google.guava", artifactId="guava")
→ {"groupId":"com.google.guava","artifactId":"guava","latestVersion":"33.4.0","stability":"STABLE"}
```
User:   "Build all my projects"
LLM:    list_build_tools()
        → maven: clean, compile, test, ...
           gradle: clean, build, test, ...

## Quick Start

1. **Build the JAR:**
   ```bash
   git clone https://github.com/thepragmatik/mcp-server-jvm-build-tools.git
   cd mcp-server-jvm-build-tools
   mvn clean package -DskipTests
   ```

2. **Configure your MCP client** — see [Using with Agentic AI Solutions](#using-with-agentic-ai-solutions) above for client-specific configuration examples, or [Installation](#installation) below.

3. **Start building:**
   ```
   Get Maven version → get_build_tool_version("maven")
   Compile my project → execute_build_command(projectDir="/path/to/project", command="clean compile")
   Build Gradle project → execute_build_command(projectDir="/path/to/gradle-project", command="build")
   Detect project type → detect_build_tool(projectDir="/path/to/project")
   Check dependency      → check_dependency_version(projectDir="/path/to/project", dependency="com.example:lib")
   ```

## Installation

### Prerequisites

- Java 21 or later
- Apache Maven (to build the JAR)
- An MCP-compatible client: Claude Desktop, Goose, Continue, Cursor, Cline, Windsurf, etc.

### Option 1: JAR + Claude Desktop

Build the JAR:
```bash
git clone https://github.com/thepragmatik/mcp-server-jvm-build-tools.git
cd mcp-server-jvm-build-tools
mvn clean package -DskipTests
# JAR is at target/mcp-server-jvm-build-tools.jar
```

See [MCP Client Configuration](#mcp-client-configuration) above for Claude Desktop and other client-specific setup instructions.

### Option 2: Docker

```bash
docker build -t mcp-server-jvm-build-tools .
docker run -i --rm \
  -v /path/to/your/projects:/projects \
  -v /opt/maven:/opt/maven \
  -e MAVEN_HOME=/opt/maven \
  mcp-server-jvm-build-tools
```

The image includes Maven out of the box. Mount your project directories and Maven installation as volumes.

### Option 3: Other MCP Clients (Goose, Continue, Cursor, Cline, Windsurf)

Any MCP client that supports stdio transport. See [MCP Client Configuration](#mcp-client-configuration) for detailed setup instructions for each supported client.

## Examples

### Example 1: Maven — Build and Test

```
User:   "Build my project and run the tests"
LLM:    get_build_tool_version(buildToolName="maven")
        → "Apache Maven 3.9.9 ..."

LLM:    execute_build_command(
          buildToolHome="/opt/maven",
          projectDir="/home/me/my-app",
          command="clean test"
        )
        → [BUILD SUCCESS] Tests run: 42, Failures: 0
```

### Example 2: Gradle with Wrapper

```
User:   "Compile the Gradle project"
LLM:    execute_build_command(
          projectDir="/home/me/gradle-app",
          command="compileJava"
        )
        → Server auto-detects build.gradle.kts
        → Uses ./gradlew wrapper
        → BUILD SUCCESSFUL
```

### Example 3: Auto-Detection Across Projects

```
User:   "Build all my projects"
LLM:    list_build_tools()
        → maven: clean, compile, test, ...
           gradle: clean, build, test, ...
           sbt: compile, test, package, ...

LLM:    execute_build_command(projectDir="/home/me/maven-app", command="package")
        → detects pom.xml → uses Maven

LLM:    execute_build_command(projectDir="/home/me/gradle-app", command="build")
        → detects build.gradle → uses Gradle
```

### Example 4: Maven with Custom Flags

```
User:   "Install skipping tests, running 4 threads"
LLM:    execute_build_command(
          buildToolName="maven",
          buildToolHome="/opt/maven",
          projectDir="/home/me/my-app",
          command="clean install -DskipTests -T4"
        )
```

### Example 5: Dependency Version Check

```
User:   "What version of Guava is this project using?"
LLM:    detect_build_tool(projectDir="/home/me/my-app")
        → maven (pom.xml found)

LLM:    check_dependency_version(
          projectDir="/home/me/my-app",
          dependency="com.google.guava:guava"
        )
        → com.google.guava:guava:33.3.1-jre
```

## Security

The server enforces multiple layers of defense:

| Layer | What It Protects Against |
|---|---|
| **Command allowlisting** | Only approved Maven lifecycle phases, Gradle tasks, and SBT commands are executable. Arbitrary plugin goals (e.g., `exec:exec`, `ant:ant`, `groovy:`) are blocked. |
| **Shell metacharacter blocking** | Attempts at command chaining (`&&`, `||`, `;`), piping (`|`), command substitution (`$()`, backticks), and redirection (`>`, `<`) are rejected. |
| **Dangerous flag blocking** | Gradle flags that enable arbitrary code execution (`--init-script`, `--build-file`, `--project-dir`, `-D`) are blocked. |
| **Path canonicalization** | All paths are resolved via `toRealPath()` to prevent directory traversal (`../../etc/passwd`). |
| **Input validation** | Commands are length-limited (500 chars). Non-existent paths are rejected before execution. |
| **Process isolation** | Maven builds use `MavenInvoker` (out-of-process). Gradle builds use `ProcessBuilder` with `--no-daemon`. |

**What the server does NOT restrict:** Within the allowed command set, you can run any lifecycle phase or task. The server trusts the LLM operator to know what they're doing — it protects against malicious input, not against intentional builds.

**Tested against:** Shell injection (`&&`, `|`, `;`, `$()`, backticks), path traversal (`../`), blocked plugin goals (`exec:exec`), Unicode/zero-width attacks, null-byte injection, denial-of-service via extremely long inputs.

239 tests covering security, functionality, and integration. See `MavenSecurityTest.java`, `GradleServiceTest.java`, `SbtBuildToolTest.java`, `BuildOutputParserTest.java`, and `BuildConfigurationValidationTest.java`.

## Project Philosophy

- **Multi-tool by design, not an afterthought** — Maven, Gradle, and SBT support are built into the server's core architecture through a `BuildTool` SPI. Each tool is a first-class implementation, not a bolt-on.
- **Security-first** — layered validation before any command executes. Allowlisting, shell injection blocking, and path canonicalization aren't optional features; they're the foundation every command passes through.
- **Extensible** — new build tools integrate via the `BuildTool` service-provider interface. Adding support for a new build system means implementing one interface, not rearchitecting the server.
- **Pragmatic** — Maven-native terminology, familiar lifecycle concepts, and build-tool-idiomatic commands. The server meets you where you already are, using the vocabulary you already know.

## CI/CD

Every PR runs on 3 JDK versions (21, 23, 25) with test coverage reporting (48% instruction, 33% branch, 46% line). CI file: `.github/workflows/ci.yml`.

## Contributing

Use GitHub Issues. See `WORKFLOW.md` for branch strategy (`feat/*`, `fix/*` → staging → main), commit conventions, quality gates, and cross-review protocol.

## License

Apache License 2.0. See [LICENSE](LICENSE).

---

**Repository:** [github.com/thepragmatik/mcp-server-jvm-build-tools](https://github.com/thepragmatik/mcp-server-jvm-build-tools)
**Author:** Rahul Thakur (@thepragmatik)
**Built with:** Spring Boot 3.4.4, Spring AI 1.0.0-M6, MCP SDK 0.8.0, Maven Embedder 3.9.9
