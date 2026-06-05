# One MCP Server for ALL Your JVM Build Tools

[![CI](https://github.com/thepragmatik/mcp-server-jvm-build-tools/actions/workflows/ci.yml/badge.svg)](https://github.com/thepragmatik/mcp-server-jvm-build-tools/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange)](https://adoptium.net/)

## Table of Contents

- [Using with Agentic AI Solutions](#using-with-agentic-ai-solutions)
- [Why This Server?](#why-this-server)
- [Supported Build Tools](#supported-build-tools)
- [All Available Tools](#all-available-tools)
- [Quick Start](#quick-start)
- [Installation](#installation)
- [Examples](#examples)
- [Security](#security)
- [Comparison: Honest Take on Competitors](#comparison-honest-take-on-competitors)
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

### Supported AI Clients

| Client | MCP Support | Notes |
|---|---|---|
| **Claude Desktop** | Native | Full MCP support via `claude_desktop_config.json` |
| **Cursor** | Native | MCP via `.cursor/mcp.json` in project root |
| **Cline / Roo Code** | Native | VS Code extensions with `cline_mcp_settings.json` |
| **Windsurf** | Native | MCP support in Cascade settings |
| **Goose** | Native | Full MCP stdio transport support |
| **Continue** | Native | MCP config in `~/.continue/config.json` |
| **GitHub Copilot** | Agent mode | MCP support in VS Code Insiders / agent mode |

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

## Why This Server?

| | This Server | arvindand/maven-tools | rnett/gradle-mcp |
|---|---|---|---|
| **Multi build tool** | ✓ Maven + Gradle | ✗ Maven only | ✗ Gradle only |
| **Auto-detection** | ✓ Detects pom.xml, build.gradle(.kts), settings.gradle(.kts) | N/A (single tool) | N/A (single tool) |
| **Unified API** | ✓ Same `execute_build_command` for both | N/A | N/A |
| **Dependency tools** | ✓ check_dependency_version | ✓ Extensive (10+ tools) | ✗ |
| **Security hardening** | ✓ Allowlisting, shell injection blocking, path canonicalization | Partial | Partial |
| **Gradle wrapper support** | ✓ Auto-detects and uses gradlew | ✗ (Maven only) | ✓ |
| **SBT support** | ✓ SBT support | ✗ | ✗ |
| **Project scaffolding** | Coming soon | ✗ | ✗ |
| **Structured output** | Coming soon | ✗ | ✗ |

> **💡 Honest Take**
>
> If you work exclusively with Maven and need deep dependency inspection (tree traversal, version resolution, transitive analysis), `arvindand/maven-tools` is excellent — it has specialized tools we don't yet match. If you work exclusively with Gradle, `rnett/gradle-mcp` is a fine single-tool server. **Choose this server when you want one MCP server that handles multiple build tools, auto-detects your projects, and gives you a unified interface across Maven, Gradle, and SBT.**

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

**Returns:** The detected build tool name (`"maven"`, `"gradle"`, or `"sbt"`) and the file that triggered detection (e.g., `"maven (pom.xml found)"`). If no recognized build file is found, returns `"unknown"`.

**Detection order:** Checks for `pom.xml` first (Maven), then `build.gradle`/`build.gradle.kts` (Gradle), then `build.sbt` (SBT).

### `check_dependency_version`
Look up the version of a specific dependency used in your project.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory. |
| `dependency` | string | Yes | Dependency coordinates. Maven: `groupId:artifactId` (e.g., `com.google.guava:guava`). Gradle: group:name notation. |
| `buildToolName` | string | No | `"maven"`, `"gradle"`, or `"sbt"`. Omit to auto-detect from project files. |

**Example:**
```
check_dependency_version(projectDir="/home/dev/my-app", dependency="com.google.guava:guava")
→ com.google.guava:guava:33.3.1-jre (compile scope)
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

Docker Hub: `thepragmatik/mcp-server-jvm-build-tools` (see [Docker Hub](https://hub.docker.com/r/thepragmatik/mcp-server-jvm-build-tools))

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

202 tests covering security, functionality, and integration. See `MavenSecurityTest.java`, `GradleServiceTest.java`, and `SbtBuildToolTest.java` for the full adversarial test suite.

## Comparison: Honest Take on Competitors

### arvindand/maven-tools
**Strengths:** The gold standard for Maven dependency analysis. Offers 10+ specialized tools for dependency tree traversal, version resolution, transitive dependency inspection, and POM manipulation. If dependency management is your primary use case with Maven, this is the tool to beat.

**Our territory:** We offer multi-tool execution (Maven + Gradle + SBT), auto-detection, and a unified API. Our `check_dependency_version` tool provides fast dependency lookups, with more analysis tools on the roadmap.

### rnett/gradle-mcp
**Strengths:** Deep Gradle integration with Gradle Tooling API for rich build introspection.

**Our territory:** We support Maven, Gradle, and SBT in one server, with auto-detection so you never have to specify which tool to use. We focus on unified execution across the entire JVM ecosystem, not just one build system.

### Our Unique Position
We are the **only MCP server that gives you one interface for all JVM build tools**. No context-switching between servers. No manual configuration per project. One server, auto-detection, unified API — Maven, Gradle, and SBT under one roof.

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
