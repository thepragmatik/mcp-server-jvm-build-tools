# Configuration Reference — mcp-server-jvm-build-tools

Complete reference for all configuration options: environment variables, JVM flags, Spring Boot properties, and MCP client configuration.

## Table of Contents

- [Environment Variables](#environment-variables)
- [JVM Configuration](#jvm-configuration)
- [Spring Boot Properties](#spring-boot-properties)
- [MCP Client Configuration](#mcp-client-configuration)
- [Build Tool Configuration](#build-tool-configuration)
- [Docker Configuration](#docker-configuration)

---

## Environment Variables

### MAVEN_HOME

**Required for Maven builds.** Path to a Maven installation directory.

```
MAVEN_HOME=/opt/maven
```

The server expects `MAVEN_HOME/bin/mvn` to be executable. If you don't set this, every Maven `execute_build_command` call must pass `buildToolHome` explicitly.

**Common paths:**
| Platform / Installer | Typical Path |
|---|---|
| macOS (Homebrew) | `/opt/homebrew/opt/maven/libexec` |
| macOS (SDKMAN) | `~/.sdkman/candidates/maven/current` |
| Linux (apt) | `/usr/share/maven` |
| Linux (SDKMAN) | `~/.sdkman/candidates/maven/current` |
| Docker (alpine maven pkg) | `/usr/share/java/maven-3` |

### MAVEN_OPTS

JVM options passed to Maven build processes. Useful for controlling heap size.

```
MAVEN_OPTS=-Xmx1024m -XX:+UseG1GC
```

Set this when Maven builds run out of memory. The server itself does not use `MAVEN_OPTS` — only the out-of-process Maven invocations do.

### GRADLE_OPTS

JVM options passed to Gradle build processes.

```
GRADLE_OPTS=-Xmx1024m -XX:+UseG1GC
```

### JAVA_HOME

Not required by this server directly, but used by Maven, Gradle, and SBT if they can't find a JVM. Set if your system Java is not the default.

```
JAVA_HOME=/usr/lib/jvm/java-21-openjdk
```

### PATH

The server and its child processes use PATH to find `gradle`, `sbt`, and `java`. If `gradle` or `sbt` are not on the default PATH, add them in the MCP client `env` block.

```json
"env": {
  "PATH": "/opt/gradle/bin:/opt/sbt/bin:/usr/local/bin:/usr/bin:/bin"
}
```

---

## JVM Configuration

### Server JVM Options

The MCP server JAR is a Spring Boot application. Configure JVM options in the MCP client's `args` array:

```json
{
  "command": "java",
  "args": [
    "-Xms64m",
    "-Xmx256m",
    "-XX:+UseG1GC",
    "-XX:+UseStringDeduplication",
    "-XX:+ExitOnOutOfMemoryError",
    "-Djava.awt.headless=true",
    "-jar",
    "/path/to/mcp-server-jvm-build-tools.jar"
  ]
}
```

### Docker Default JVM Settings

The Dockerfile ENTRYPOINT uses:

```
-Xms64m -Xmx256m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true
```

| Flag | Purpose |
|---|---|
| `-Xms64m` | Initial heap — low to avoid wasting memory at startup |
| `-Xmx256m` | Maximum heap — the server is thin, 256m is ample |
| `-XX:+UseG1GC` | G1 garbage collector — low pause times for stdio transport |
| `-XX:+UseStringDeduplication` | Reduce memory for duplicate strings (build output can be repetitive) |
| `-XX:+ExitOnOutOfMemoryError` | Fail fast instead of limping in a degraded state |
| `-Djava.awt.headless=true` | No GUI — Spring Boot detects this and skips AWT init |

### Maven Build Process JVM

Maven builds run out-of-process via `MavenInvoker`. To control Maven's JVM:

```
MAVEN_OPTS=-Xmx1024m -XX:MaxMetaspaceSize=256m
```

### Gradle Build Process JVM

Gradle builds run as separate processes. The server always passes `--no-daemon`. To control Gradle's JVM:

```
GRADLE_OPTS=-Xmx1024m -XX:MaxMetaspaceSize=256m
```

Or set `org.gradle.jvmargs` in your project's `gradle.properties`:
```
org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=256m
```

### SBT Build Process JVM

SBT's JVM is controlled by `-J` flags in `SBT_OPTS` or by `javaHome` in `build.sbt`. Note that `-J` flags are blocked by the server for security — configure SBT JVM settings in the project files instead.

---

## Spring Boot Properties

The server's behavior is controlled by `application.properties` in `src/main/resources/`. These properties are baked into the JAR at build time. To override, use JVM system properties (`-D` flags in MCP client args).

### Core Properties

```properties
# Application identity
spring.application.name=@project.name@

# Disable web server — this is a stdio-only MCP server
spring.main.web-application-type=none

# MCP server metadata. Single canonical identity shared by the server card,
# server/discover, and the Mcp-Name HeaderMismatch check (must equal @project.name@).
spring.ai.mcp.server.name=@project.name@
spring.ai.mcp.server.version=@project.version@

# Enable stdio transport (the only supported transport)
spring.ai.mcp.server.stdio=true

# Suppress Spring Boot banner on stdout (critical — MCP transport is on stdout!)
spring.main.banner-mode=off
```

### Logging Configuration

```properties
# Suppress console logging pattern (stdout is for MCP JSON-RPC, not logs)
logging.pattern.console=

# Keep Spring framework noise to a minimum
logging.level.org.springframework=WARN

# Enable build-tools debug logging (uncomment to troubleshoot)
# logging.level.com.pragmatik.buildtools=DEBUG
```

**Important:** Because the server uses stdio for MCP transport, stdout must carry ONLY JSON-RPC messages. Any Spring Boot log output or banner text on stdout will corrupt the MCP protocol. The settings above ensure a clean stdout.

### Jackson / JSON Configuration

```properties
# Allow unknown JSON fields in MCP protocol messages
# Claude Desktop sends "extensions" field which is part of newer MCP spec
# but might not be recognized by the bundled MCP SDK
spring.jackson.deserialization.fail-on-unknown-properties=false
```

Without this property, any MCP client sending protocol extensions (like the `extensions` field in the initialize request) would cause a Jackson deserialization error, breaking the handshake.

### Cache Hints (MCP RC, SEP-2549)

The MCP upcoming spec adds `ttlMs` (freshness hint, in milliseconds) and `cacheScope`
(`public`/`private`) to list/read results so clients and gateways can cache them. This server's
tool/prompt/resource catalogue is static for the lifetime of the process, so the list surfaces
advertise a generous, `public` TTL while per-project content reads advertise a short, `private` TTL.

```properties
# Freshness hint (ms) for the static catalogue list surfaces (tools/list, prompts/list,
# resources/list, resources/templates/list). Advertised as cacheScope "public". Default: 24h.
buildtools.cache.catalog-ttl-ms=86400000

# Freshness hint (ms) for per-project content reads (resources/read). Advertised as
# cacheScope "private" because reads reflect mutable on-disk project state. Default: 5min.
buildtools.cache.read-ttl-ms=300000
```

These values are surfaced as per-method `{ttlMs, cacheScope}` hints under the `cacheHints` key of
the server card (`/.well-known/mcp-server`) and the `server/discover` result. The bundled MCP SDK
does not yet model `ttlMs`/`cacheScope` as typed fields on its result records, so the hints are
advertised on the discovery surfaces (additive, backward-compatible) rather than emitted on each
individual result. See `docs/mcp-cacheable-result-gap.md` for the upstream dependency.

### Overriding Properties at Runtime

Any Spring Boot property can be overridden via the `-D` flag in the MCP client args:

```json
"args": [
  "-Dlogging.level.com.pragmatik.buildtools=DEBUG",
  "-jar",
  "/path/to/mcp-server-jvm-build-tools.jar"
]
```

Note: these `-D` flags are JVM system properties passed to the server JVM, NOT the blocked Gradle/SBT `-D` flags.

---

## MCP Client Configuration

### Supported Clients

The server uses standard MCP stdio transport. Compatible with any MCP client supporting stdio.

| Client | Config File | Notes |
|---|---|---|
| Claude Desktop | `claude_desktop_config.json` | Set via Claude > Settings > Developer |
| Cursor | `.cursor/mcp.json` | Project-level MCP config |
| Cline / Roo Code | `cline_mcp_settings.json` | VS Code user settings or `.vscode/` |
| Windsurf | Settings > Cascade > MCP Servers | Also supports `.windsurfrules` |
| Goose | `~/.config/goose/mcp.json` | Or via Goose UI |
| Continue | `~/.continue/config.json` | Under `mcpServers` key |
| GitHub Copilot | Agent mode | Stdio transport support |

### Common Config Pattern

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "java",
      "args": [
        "-Xms64m",
        "-Xmx256m",
        "-XX:+UseG1GC",
        "-jar",
        "/absolute/path/to/mcp-server-jvm-build-tools.jar"
      ],
      "env": {
        "MAVEN_HOME": "/opt/maven",
        "MAVEN_OPTS": "-Xmx1024m",
        "GRADLE_OPTS": "-Xmx1024m"
      }
    }
  }
}
```

### Config Field Reference

| Field | Required | Description |
|---|---|---|
| `command` | Yes | Path to `java` executable. Use absolute path if not on PATH. |
| `args` | Yes | JVM flags + `-jar` + absolute path to the JAR. |
| `env` | No | Environment variables: `MAVEN_HOME`, `MAVEN_OPTS`, `GRADLE_OPTS`, `JAVA_HOME`, `PATH`. |
| `cwd` | No | Working directory. Not required — the server doesn't rely on CWD. |

### Testing Your Config

The simplest way to verify your MCP config is to start the server directly:

```bash
java -jar /path/to/mcp-server-jvm-build-tools.jar
```

You should see Spring Boot startup logs, then the server blocks (waits for stdio input). Press Ctrl+C to stop. If it crashes, the error will be visible in the terminal.

---

## Build Tool Configuration

### Maven Configuration

**Installation requirement:** A Maven installation must exist at `MAVEN_HOME` with `bin/mvn` executable.

**User/settings files:** Maven reads `~/.m2/settings.xml` and project-level `.mvn/maven.config` as normal. Proxy settings, mirrors, and repository configuration work as they do with CLI Maven.

**Allowed commands:** `clean`, `compile`, `test`, `package`, `install`, `deploy`, `validate`, `dependency:tree`

**Allowed flags:** `-Dproperty=value`, `-f file`, `-P profile`, `-q`, `-X`, `-T threads`, `-B`, `-U`, `--batch-mode`, `--non-recursive`

### Gradle Configuration

**No installation required.** The server auto-detects the Gradle wrapper (`gradlew`) in the project directory. Falls back to `gradle` on PATH.

**Wrapper generation:** If your project doesn't have `gradlew`, generate it:
```bash
gradle wrapper --gradle-version 8.12
```

**Always-added flags:** `--no-daemon` (process isolation) and `--console=plain` (machine-readable output). These are appended automatically — don't add them to your command.

**Allowed tasks:** `clean`, `build`, `test`, `compileJava`, `compileTestJava`, `jar`, `assemble`, `check`, `publishToMavenLocal`, `dependencies`, `projects`, `tasks`

**Allowed flags:** `-x task`, `--exclude-task task`, `--parallel`, `--configure-on-demand`, `--build-cache`

**Multi-module projects:** Use colon syntax: `:submodule:build`. The task name is extracted from the last colon-separated segment and validated against the allowlist.

### SBT Configuration

**No installation required.** The server auto-detects `sbt` wrapper in the project directory. Falls back to `sbt` on PATH.

**First-run overhead:** SBT downloads its launcher and compiles build definitions. The first `execute_build_command` for an SBT project may take 1-3 minutes. Warm up manually or configure `SBT_OPTS` for faster startup.

**Always-added flags:** `--no-colors` (machine-readable output). Appended automatically.

**Allowed tasks:** `compile`, `test`, `run`, `package`, `clean`, `assembly`, `publishLocal`, `publish`, `update`, `doc`, `console`

**SBT-specific task chaining:** Use semicolons: `clean;compile;test` (not spaces like Maven/Gradle).

---

## Docker Configuration

### Dockerfile

Multi-stage build: JDK 21 Alpine for compilation → JRE 21 Alpine for runtime. Maven is installed via `apk` in both stages.

**JVM flags baked into ENTRYPOINT:**
```
-Xms64m -Xmx256m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true
```

### Running with Docker

```bash
docker build -t mcp-server-jvm-build-tools .
docker run -i --rm \
  -v /host/projects:/projects \
  -v /opt/maven:/opt/maven \
  -e MAVEN_HOME=/opt/maven \
  mcp-server-jvm-build-tools
```

**Key points:**
- `-i` (interactive) is required for stdio transport
- `--rm` cleans up the container after exit
- Volume mounts make host directories available inside the container
- `-e MAVEN_HOME` sets the Maven installation path inside the container
- All project paths in LLM prompts must use container paths (`/projects/my-app` not `/host/projects/my-app`)

### Docker + MCP Client

When using Docker, your MCP client config runs the `docker run` command instead of `java -jar`:

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "docker",
      "args": [
        "run", "-i", "--rm",
        "-v", "/home/me/projects:/projects",
        "-v", "/opt/maven:/opt/maven",
        "-e", "MAVEN_HOME=/opt/maven",
        "mcp-server-jvm-build-tools"
      ]
    }
  }
}
```
