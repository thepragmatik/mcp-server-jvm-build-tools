# Installation

This guide takes you from a clean checkout to a running server connected to your MCP client.

## Prerequisites

| Requirement | Notes |
|-------------|-------|
| **Java 21+** | The server JAR targets Java 21. [Adoptium](https://adoptium.net/) is recommended. |
| **Apache Maven 3.9+** | Needed to *build* the server JAR. Also required at runtime for Maven *builds* (via `MAVEN_HOME`). |
| **An MCP client** | Claude Desktop, Cursor, Cline / Roo Code, Windsurf, Goose, Continue, GitHub Copilot, or any stdio-capable MCP client. |
| **Gradle / SBT** (optional) | Only needed at runtime if you build Gradle/SBT projects. The server prefers a project's `gradlew`/`sbt` wrapper and falls back to the tool on `PATH`. |

## Step 1 — Build the JAR

```bash
git clone https://github.com/thepragmatik/mcp-server-jvm-build-tools.git
cd mcp-server-jvm-build-tools
mvn clean package -DskipTests
```

The runnable artifact is produced at:

```text
target/mcp-server-jvm-build-tools.jar
```

!!! tip "Run the full test suite (optional)"
    To verify your toolchain, run `mvn -B verify`. The unit and security tests do **not**
    require Maven/Gradle/SBT to be installed.

## Step 2 — Configure your MCP client

Every client uses the same shape: run `java -jar <path-to-jar>`, optionally with JVM flags and
an `env` block. Use an **absolute path** to the JAR — relative paths do not work in most clients.

=== "Claude Desktop"

    Edit `claude_desktop_config.json` (via **Settings → Developer**):

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

    Restart Claude Desktop; the tools appear automatically.

=== "Cursor"

    Create `.cursor/mcp.json` in your project root with the same JSON config, then restart Cursor.

=== "Cline / Roo Code"

    Add the same config to `cline_mcp_settings.json` (VS Code user settings, or `.vscode/` in
    your workspace).

=== "Windsurf"

    Add the server under **Settings → Cascade → MCP Servers**, using the same `command`/`args`/`env`.

=== "Goose"

    Add to `~/.config/goose/mcp.json` (or via the Goose UI) under `mcpServers`.

=== "Continue"

    Add to `~/.continue/config.json` under the `mcpServers` key.

### Recommended JVM flags

The server is intentionally thin. For a small, predictable footprint, add these JVM flags to
`args` (they mirror the values baked into the Docker image):

```json
"args": [
  "-Xms64m",
  "-Xmx256m",
  "-XX:+UseG1GC",
  "-XX:+UseStringDeduplication",
  "-XX:+ExitOnOutOfMemoryError",
  "-Djava.awt.headless=true",
  "-jar",
  "/absolute/path/to/mcp-server-jvm-build-tools.jar"
]
```

See the [Configuration Reference](../reference/configuration.md) for the meaning of each flag and
for every supported environment variable.

## Step 3 — Point Maven at an installation

Maven builds run out-of-process through the Maven Shared Invoker, which needs a Maven home with
an executable `bin/mvn`. Set it once in the client's `env` block:

```json
"env": {
  "MAVEN_HOME": "/opt/maven"
}
```

!!! warning "Maven has no built-in wrapper bootstrap here"
    Unlike Gradle's `gradlew`, the server cannot bootstrap Maven from a project wrapper. If
    `MAVEN_HOME` is not set, every Maven call must pass `buildToolHome` explicitly. Gradle and
    SBT need no installation — they use the project wrapper or fall back to `PATH`.

Common `MAVEN_HOME` locations:

| Platform / installer | Typical path |
|----------------------|--------------|
| macOS (Homebrew) | `/opt/homebrew/opt/maven/libexec` |
| macOS / Linux (SDKMAN) | `~/.sdkman/candidates/maven/current` |
| Linux (apt) | `/usr/share/maven` |

## Step 4 — Verify it works

Start the server directly to confirm it boots:

```bash
java -jar target/mcp-server-jvm-build-tools.jar
```

You should see Spring Boot startup logs, after which the process **blocks**, waiting for MCP
input on stdin. Press ++ctrl+c++ to stop. If it exits immediately, the error is printed to the
terminal.

Then, in your MCP client, ask:

```text
What build tools are available?
```

The agent should call `list_build_tools` and return the registered tools and their commands.

## Alternative: the CLI launcher

The repository ships `scripts/launcher.sh`, which auto-discovers Java, Maven, Gradle, and SBT and
starts the JAR for you:

```bash
./scripts/launcher.sh            # stdio mode (default)
./scripts/launcher.sh --http     # Streamable HTTP mode
./scripts/launcher.sh --port 8080  # custom HTTP port (with --http)
```

It honours `JAVA_HOME`, `MAVEN_HOME`, `GRADLE_HOME`, `SBT_HOME`, `SERVER_PORT`, and `MCP_OPTS`.

## Alternative: Docker

A multi-stage `Dockerfile` (JDK 21 Alpine to build, JRE 21 Alpine to run, Maven via `apk`) is
provided.

```bash
docker build -t mcp-server-jvm-build-tools .
docker run -i --rm \
  -v /host/projects:/projects \
  -v /opt/maven:/opt/maven \
  -e MAVEN_HOME=/opt/maven \
  mcp-server-jvm-build-tools
```

To use Docker from an MCP client, run the `docker` command instead of `java`:

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "docker",
      "args": [
        "run", "-i", "--rm",
        "-v", "/host/projects:/projects",
        "-v", "/opt/maven:/opt/maven",
        "-e", "MAVEN_HOME=/opt/maven",
        "mcp-server-jvm-build-tools"
      ]
    }
  }
}
```

!!! note "Use container paths in prompts"
    With Docker, every `projectDir` you give the agent must be a **container** path
    (`/projects/my-app`), not the host path you mounted from. The `-i` flag is required for the
    stdio transport.

Next, learn how to tune the server in the [Configuration guide](configuration.md).
