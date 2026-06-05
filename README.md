# One MCP Server for ALL Your JVM Build Tools

Run Maven and Gradle builds through any MCP-compatible LLM client (Claude Desktop, Goose, Continue, etc.). One server, two build tools, auto-detection of your project type — no switching servers, no manual config per project.

```
You:     "Build and test this project"
Claude:  → execute_build_command(projectDir="...", command="clean test")
Server:  → detects pom.xml → mvn clean test ✓
You:     "Now build the Gradle project next door"
Claude:  → execute_build_command(projectDir=".../other", command="build")
Server:  → detects build.gradle.kts → gradle build ✓
```

## Why This Server?

| | This Server | arvindand/maven-tools | gradle-mcp |
|---|---|---|---|
| **Multi build tool** | ✓ Maven + Gradle | ✗ Maven only | ✗ Gradle only |
| **Auto-detection** | ✓ Detects pom.xml, build.gradle(.kts), settings.gradle(.kts) | N/A (single tool) | N/A (single tool) |
| **Unified API** | ✓ Same `execute_build_command` for both | N/A | N/A |
| **Dependency tools** | Coming soon | ✓ Extensive (10+ tools) | ✗ |
| **Security hardening** | ✓ Allowlisting, shell injection blocking, path canonicalization | Partial | Partial |
| **Gradle wrapper support** | ✓ Auto-detects and uses gradlew | ✗ (Maven only) | ✓ |
| **SBT support** | Coming soon | ✗ | ✗ |
| **Project scaffolding** | Coming soon | ✗ | ✗ |
| **Structured output** | Coming soon | ✗ | ✗ |

**Honest take:** If you work exclusively with Maven and need deep dependency inspection (tree traversal, version resolution, transitive analysis), `arvindand/maven-tools` is excellent — it has specialized tools we don't yet match. If you work exclusively with Gradle, `gradle-mcp` is a fine single-tool server. **Choose this server when you want one MCP server that handles multiple build tools, auto-detects your projects, and gives you a unified interface across Maven and Gradle.**

## Supported Build Tools

| Tool | Detection | Home Required | Fallback |
|---|---|---|---|
| Maven | `pom.xml` | Yes (`buildToolHome` or `MAVEN_HOME`) | — |
| Gradle | `build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts` | No | `gradlew` in project → `gradle` on PATH |

| Feature | Maven | Gradle |
|---|---|---|
| Execute builds | ✓ 7 lifecycle phases | ✓ 11 tasks |
| Version query | ✓ (embedder, no external process) | ✓ (CLI) |
| Command allowlisting | ✓ | ✓ |
| Shell injection protection | ✓ | ✓ |
| Path canonicalization | ✓ | ✓ |
| Multi-module projects | ✓ | ✓ (`:subproject:task`) |

When both Maven and Gradle markers exist (e.g., a project with both `pom.xml` and `build.gradle`), the server auto-detects Maven first. You can always explicitly specify which tool to use.

## All Available Tools

### `get_build_tool_version`
Get the installed version of any registered build tool.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `buildToolName` | string | Yes | `"maven"` or `"gradle"` |

### `execute_build_command`
Execute a build command with automatic tool detection.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `buildToolName` | string | No | `"maven"` or `"gradle"`. Omit to auto-detect from project files. |
| `buildToolHome` | string | No | Path to build tool installation. Required for Maven, optional for Gradle (uses wrapper or PATH). |
| `projectDir` | string | Yes | Path to the project directory containing build files. |
| `command` | string | Yes | Build command. Maven: `clean compile`, `test`, `package`, etc. Gradle: `build`, `test`, `clean`, etc. |

**Supported Maven commands:** `clean`, `compile`, `test`, `package`, `install`, `deploy`, `validate`, `dependency:tree` (+ safe flags: `-D`, `-f`, `-P`, `-q`, `-X`, `-T`, `-B`, `-U`, `--batch-mode`, `--non-recursive`)

**Supported Gradle commands:** `clean`, `build`, `test`, `compileJava`, `compileTestJava`, `jar`, `assemble`, `check`, `publishToMavenLocal`, `dependencies`, `projects`, `tasks` (+ safe flags: `-x`, `--exclude-task`, `--parallel`, `--configure-on-demand`, `--build-cache`)

### `list_build_tools`
List all registered build tools and their supported commands. Returns formatted string like:
```
maven: clean, compile, test, package, install, deploy, validate, dependency:tree
gradle: clean, build, test, compileJava, compileTestJava, jar, assemble, check, publishToMavenLocal, dependencies, projects, tasks
```

## Quick Start

1. **Build the JAR:**
   ```bash
   git clone https://github.com/thepragmatik/mcp-server-jvm-build-tools.git
   cd mcp-server-jvm-build-tools
   mvn clean package -DskipTests
   ```

2. **Configure your MCP client** (see Installation below)

3. **Start building:**
   ```
   Get Maven version → get_build_tool_version("maven")
   Compile my project → execute_build_command(projectDir="/path/to/project", command="clean compile")
   Build Gradle project → execute_build_command(projectDir="/path/to/gradle-project", command="build")
   ```

## Installation

### Prerequisites

- Java 21 or later
- Apache Maven (to build the JAR)
- An MCP-compatible client: Claude Desktop, Goose, Continue, Cursor, etc.

### Option 1: JAR + Claude Desktop

Build the JAR:
```bash
git clone https://github.com/thepragmatik/mcp-server-jvm-build-tools.git
cd mcp-server-jvm-build-tools
mvn clean package -DskipTests
# JAR is at target/mcp-server-jvm-build-tools.jar
```

Add to your Claude Desktop config (`claude_desktop_config.json`):
```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "/path/to/java",
      "args": [
        "-jar",
        "/path/to/mcp-server-jvm-build-tools.jar"
      ]
    }
  }
}
```

Set `MAVEN_HOME` in your environment or pass `buildToolHome` when calling `execute_build_command` for Maven projects. Gradle projects work with the wrapper or a system Gradle on PATH — no extra config needed.

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

### Option 3: Goose / Continue / Cursor

Any MCP client that supports stdio transport. Configure with the same command/args as the Claude Desktop example above.

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

## Security

The server enforces multiple layers of defense:

| Layer | What It Protects Against |
|---|---|
| **Command allowlisting** | Only approved Maven lifecycle phases and Gradle tasks are executable. Arbitrary plugin goals (e.g., `exec:exec`, `ant:ant`, `groovy:`) are blocked. |
| **Shell metacharacter blocking** | Attempts at command chaining (`&&`, `||`, `;`), piping (`|`), command substitution (`$()`, backticks), and redirection (`>`, `<`) are rejected. |
| **Dangerous flag blocking** | Gradle flags that enable arbitrary code execution (`--init-script`, `--build-file`, `--project-dir`, `-D`) are blocked. |
| **Path canonicalization** | All paths are resolved via `toRealPath()` to prevent directory traversal (`../../etc/passwd`). |
| **Input validation** | Commands are length-limited (500 chars). Non-existent paths are rejected before execution. |
| **Process isolation** | Maven builds use `MavenInvoker` (out-of-process). Gradle builds use `ProcessBuilder` with `--no-daemon`. |

**What the server does NOT restrict:** Within the allowed command set, you can run any lifecycle phase or task. The server trusts the LLM operator to know what they're doing — it protects against malicious input, not against intentional builds.

**Tested against:** Shell injection (`&&`, `|`, `;`, `$()`, backticks), path traversal (`../`), blocked plugin goals (`exec:exec`), Unicode/zero-width attacks, null-byte injection, denial-of-service via extremely long inputs.

53 security and functional tests. See `MavenSecurityTest.java` and `GradleServiceTest.java` for the full adversarial test suite.

## Comparison: Honest Take on Competitors

### arvindand/maven-tools
**Strengths:** The gold standard for Maven dependency analysis. Offers 10+ specialized tools for dependency tree traversal, version resolution, transitive dependency inspection, and POM manipulation. If dependency management is your primary use case with Maven, this is the tool to beat.

**Our territory:** We offer multi-tool execution (Maven + Gradle, SBT coming), auto-detection, and a unified API. We're adding dependency lookup and structured output to close the gap.

### gradle-mcp
**Strengths:** Deep Gradle integration with Gradle Tooling API for rich build introspection.

**Our territory:** We support both Maven and Gradle in one server, with auto-detection so you never have to specify which tool to use. We focus on unified execution across the JVM ecosystem.

### Our Unique Position
We are the **only MCP server that gives you one interface for all JVM build tools**. No context-switching between servers. No manual configuration per project. One server, auto-detection, unified API.

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
