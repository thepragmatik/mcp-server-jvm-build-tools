# Quick Start — mcp-server-jvm-build-tools

Get the MCP server running in 5 minutes. For detailed docs, see [README.md](README.md).

## Prerequisites

- Java 21 or later ([Adoptium](https://adoptium.net/) recommended)
- Apache Maven 3.9+ (to build the JAR)
- An MCP-compatible client (Claude Desktop, Cursor, Cline, Goose, Continue, etc.)

## Step 1: Build the JAR

```bash
git clone https://github.com/thepragmatik/mcp-server-jvm-build-tools.git
cd mcp-server-jvm-build-tools
mvn clean package -DskipTests
```

The JAR is at `target/mcp-server-jvm-build-tools.jar`.

## Step 2: Configure Your MCP Client

### Claude Desktop

Edit `claude_desktop_config.json`:

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

Restart Claude Desktop. The build tools appear automatically.

### Cursor

Create `.cursor/mcp.json` in your project root with the same JSON config as above. Restart Cursor.

### Cline / Roo Code

Add to `cline_mcp_settings.json` (VS Code user settings or `.vscode/` in your workspace) with the same JSON config.

### Goose / Continue

Add to your MCP config under the `mcpServers` key with the same JSON config.

### Docker (Alternative)

```bash
docker build -t mcp-server-jvm-build-tools .
docker run -i --rm \
  -v /path/to/your/projects:/projects \
  -v /opt/maven:/opt/maven \
  -e MAVEN_HOME=/opt/maven \
  mcp-server-jvm-build-tools
```

## Step 3: Verify It Works

Open your MCP client and ask:

```
"What build tools are available?"
```

The server should respond with:

```
maven: clean, compile, test, package, install, deploy, validate
gradle: clean, build, test, compileJava, compileTestJava, jar, assemble, check, publishToMavenLocal, dependencies, projects, tasks
sbt: compile, test, run, package, clean, assembly, publishLocal, publish, update, doc, console
```

## Step 4: Your First Build

Navigate to a JVM project and ask:

```
"Detect the build tool for /path/to/my-project"
"Build and test /path/to/my-project"
```

Examples:

```
# Maven project
"Execute 'clean test' on /home/me/my-maven-app"

# Gradle project (auto-detects wrapper)
"Run 'build' on /home/me/my-gradle-app"

# Check dependency versions
"What version of Guava is available? My project is at /home/me/my-app"
```

## Troubleshooting

### "Maven requires buildToolHome"

Set `MAVEN_HOME` in your MCP client's `env` block, or pass it explicitly:

```
execute_build_command(buildToolHome="/opt/maven", projectDir="...", command="compile")
```

### "Gradle task not allowed"

Only specific Gradle tasks are permitted for security. See [TOOLS.md](TOOLS.md) for the full allowlist.

### "Command contains disallowed characters"

Shell metacharacters (&&, |, ;) are blocked. Use plain build commands: `clean compile` not `clean && compile`.

### "Cannot resolve project directory"

The path doesn't exist. Use absolute paths or verify the directory exists on your filesystem.

## Next Steps

- [README.md](README.md) — Full project overview, features, and client configs
- [TOOLS.md](TOOLS.md) — Detailed reference for all 39 MCP tools
- [SECURITY.md](SECURITY.md) — Security model and vulnerability reporting
- [ARCHITECTURE.md](ARCHITECTURE.md) — Internal architecture and extending with new build tools
- [CONTRIBUTING.md](CONTRIBUTING.md) — Development setup and contribution guide
