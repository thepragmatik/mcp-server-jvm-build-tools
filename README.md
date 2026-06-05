# MCP Server — Build Tools for the JVM

An MCP (Model Context Protocol) server that lets LLM clients execute build commands on JVM projects. Supports **Maven** and **Gradle** with automatic project detection.

## How It Works

You talk to an LLM (Claude, Goose, etc.). The LLM translates your request into build commands and calls the server. The server runs them and returns the output.

```
You:     "Build this project"
Claude:   → execute_build_command(projectDir="...", command="clean compile")
Server:   → mvn clean compile
You:      sees the build output
```

## Supported Build Tools

| Tool   | Detection     | Home Required | Fallback           |
|--------|---------------|---------------|--------------------|
| Maven  | `pom.xml`     | Yes           | `maven.home` prop  |
| Gradle | `build.gradle`, `settings.gradle`, etc. | No | `gradlew` in project, then `gradle` on PATH |

The server auto-detects which build tool to use based on project files. If both Maven and Gradle markers are present, Maven is tried first.

## Available Tools

The server exposes 3 MCP tools:

| Tool | What It Does |
|------|-------------|
| `get_build_tool_version` | Returns the installed version of a build tool. Pass `"maven"` or `"gradle"`. |
| `execute_build_command` | Runs a build command. Auto-detects the tool from project files. |
| `list_build_tools` | Lists registered tools and their supported commands. |

### Examples

**Maven project:**
```
execute_build_command(buildToolName="maven", buildToolHome="/opt/maven",
    projectDir="/path/to/project", command="clean install")
```

**Gradle project with wrapper (no home needed):**
```
execute_build_command(projectDir="/path/to/project", command="build")
```
User:   "Build all my projects"
LLM:    list_build_tools()
        → maven: clean, compile, test, ...
           gradle: clean, build, test, ...

**Any project (auto-detect):**
```
execute_build_command(projectDir="/path/to/project", command="test")
```

## Installation

### Prerequisites

- Java 21 or later
- Maven (to build the JAR)
- An MCP client (Claude Desktop, Goose, Continue, etc.)

### Build

```bash
git clone https://github.com/thepragmatik/mcp-server-jvm-build-tools.git
cd mcp-server-jvm-build-tools
mvn clean package -DskipTests
```

The JAR is at `target/mcp-server-jvm-build-tools.jar`.

### Configure Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or the equivalent on your platform:

```json
{
    "mcpServers": {
        "jvm-build-tools": {
            "command": "/path/to/java",
            "args": ["-jar", "/path/to/mcp-server-jvm-build-tools.jar"]
        }
    }
}
```

**Note:** Don't pass `-Dmaven.home` as a JVM argument — the server's tools handle build tool paths. For Maven, set `MAVEN_HOME` in your environment or pass `buildToolHome` when calling `execute_build_command`.

### Docker

```bash
docker build -t mcp-server-jvm-build-tools .
docker run -i --rm mcp-server-jvm-build-tools
```

Volume-mount your project directory and Maven/Gradle homes as needed.

## Security

The server prevents shell injection (metacharacters like `&&`, `|`, `;`, `$()`, backticks are blocked) and path traversal. It does **not** restrict which build commands you can run — you can use any Maven goal or Gradle task. The server trusts you to know what you're doing.

## CI/CD

Every PR runs on 3 JDK versions (21, 23, 25) with test coverage reporting. See `.github/workflows/ci.yml`.

## Contributing

Use GitHub Issues. See `WORKFLOW.md` for branch strategy and review protocol.

## License

Apache License 2.0. See [LICENSE](LICENSE).
