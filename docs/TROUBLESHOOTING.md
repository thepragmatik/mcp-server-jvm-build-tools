# Troubleshooting Guide — mcp-server-jvm-build-tools

Common problems and their solutions when using the MCP server for JVM build tools.

## Table of Contents

- [MCP Client Issues](#mcp-client-issues)
- [Build Tool Installation Issues](#build-tool-installation-issues)
- [Runtime Errors](#runtime-errors)
- [Dependency Service Issues](#dependency-service-issues)
- [Docker Issues](#docker-issues)
- [Logging and Debugging](#logging-and-debugging)
- [Performance Tuning](#performance-tuning)
- [Getting Help](#getting-help)

---

## MCP Client Issues

### Server not appearing in client tool list

**Symptoms:** Client (Claude Desktop, Cursor, etc.) starts but the build tools don't appear.

**Causes and fixes:**

1. **Wrong JAR path** — The `args` in your MCP config must use an absolute path to the JAR. Relative paths like `./target/...jar` or `~/path/...jar` won't work in most clients.

   ```json
   // WRONG
   "args": ["-jar", "./target/mcp-server-jvm-build-tools.jar"]

   // RIGHT
   "args": ["-jar", "/home/me/mcp-server-jvm-build-tools/target/mcp-server-jvm-build-tools.jar"]
   ```

2. **Java not on PATH** — Use the absolute path to `java` in the `command` field:

   ```json
   "command": "/usr/lib/jvm/java-21-openjdk/bin/java"
   ```

3. **MCP client caches old config** — Fully restart the client (not just reload). For Claude Desktop: `File > Quit` then reopen. For Cursor: `Cmd+Shift+P > Reload Window`.

4. **Server crashes at startup** — Check the client's logs. Claude Desktop logs to `~/Library/Logs/Claude/mcp*.log` (macOS) or `%APPDATA%\Claude\logs` (Windows). Cursor logs to the Output panel (select "MCP" from the dropdown).

### "Could not attach to transport" error

**Symptoms:** Client reports connection failure or timeout.

**Fixes:**

- Verify the JAR was built successfully: `java -jar /path/to/jar.jar` should print Spring Boot startup logging then block (it waits for stdio input).
- Ensure no other process is using the same MCP server name.
- Check that `spring.main.banner-mode=off` is active (no unexpected stdout output before JSON-RPC).

### MCP client reports unknown fields error

**Symptoms:** Client logs show Jackson deserialization errors mentioning unknown fields like `extensions`.

**Fix:** This is already handled by `spring.jackson.deserialization.fail-on-unknown-properties=false` in `application.properties`. If you've overridden this, add it back. This setting allows forward compatibility with newer MCP protocol versions.

---

## Build Tool Installation Issues

### "Maven requires buildToolHome"

**Symptoms:** `IllegalArgumentException: Maven requires buildToolHome.`

**Fixes:**

1. **Set MAVEN_HOME in your MCP client config:**

   ```json
   "env": {
     "MAVEN_HOME": "/opt/maven"
   }
   ```

2. **Pass it explicitly in the LLM prompt:**

   ```
   execute_build_command(buildToolHome="/opt/maven", projectDir="/my/project", command="compile")
   ```

3. **Verify the Maven installation:**
   ```bash
   /opt/maven/bin/mvn --version   # should print version info
   ```

4. **Common MAVEN_HOME paths:**
   - macOS (Homebrew): `/opt/homebrew/opt/maven/libexec`
   - macOS (SDKMAN): `~/.sdkman/candidates/maven/current`
   - Linux (apt): `/usr/share/maven`
   - Docker: `/usr/share/java/maven-3`

### Gradle wrapper not found

**Symptoms:** Server uses system `gradle` instead of `gradlew`, or Gradle commands fail.

**Fixes:**

1. Make `gradlew` executable:
   ```bash
   chmod +x gradlew
   ```

2. Generate the wrapper if missing:
   ```bash
   gradle wrapper
   ```

3. The server checks these locations in order:
   - `buildToolHome/bin/gradle` (if `buildToolHome` specified)
   - `buildToolHome/gradlew` (if `buildToolHome` is a project dir)
   - `projectDir/gradlew` (project wrapper)
   - `gradle` on system PATH

### SBT not found or wrong version

**Symptoms:** `sbt exited with code 1` or version query fails.

**Fixes:**

1. Install SBT: `brew install sbt` (macOS) or `apt install sbt` (Linux). For SDKMAN: `sdk install sbt`.
2. SBT can take a long time on first run (downloading launcher + compiling). The first `execute_build_command` may time out. Warm it up: `cd /path/to/project && sbt --no-colors compile`.
3. SBT version is pinned in `project/build.properties`. Check compatibility with your Scala version.
4. SBT wrapper detection: the server checks for an executable named `sbt` in the project directory.

### "Gradle task not allowed"

**Symptoms:** `IllegalArgumentException: Gradle task not allowed: X`

**Fix:** Only 12 Gradle tasks are allowed for security. The full list:
`clean`, `build`, `test`, `compileJava`, `compileTestJava`, `jar`, `assemble`, `check`, `publishToMavenLocal`, `dependencies`, `projects`, `tasks`

For multi-module projects, you can use colon-prefixed paths: `:subproject:build` is allowed as long as the task name (`build`) is in the allowlist.

### "Command contains disallowed characters"

**Symptoms:** `IllegalArgumentException: Command contains disallowed characters.`

**Cause:** Shell metacharacters (`&&`, `|`, `;`, `$()`, backticks, `>`, `<`) are blocked for security. The regex pattern is: `^[a-zA-Z0-9\s._=/:@;\-]+$` (with an optional `gradle\w*\s+` prefix).

**Fix:** Use the build tool's native multi-command syntax:
- **Maven:** `clean compile test` (space-separated goals)
- **Gradle:** `clean build test` (space-separated tasks)
- **SBT:** `clean;compile;test` (semicolon-chained tasks)

### "Blocked Gradle flag" or "Blocked sbt flag"

**Symptoms:** Error mentioning a blocked flag like `--init-script`, `-D`, `-J`, etc.

**Blocked Gradle flags:** `--init-script`/`-I`, `--build-file`/`-b`, `--project-dir`/`-p`, `--include-build`, `--system-prop`/`-D`

**Blocked SBT flags:** `-D`, `-J`, `-sbt-dir`, `-sbt-boot`, `-sbt-launch-dir`, `-ivy`, `-maven-launcher`

**Blocked Maven plugin prefixes:** `exec:`, `ant:`, `antrun:`, `sql:`, `groovy:`, `shell:`, `help:`, `dependency:` (except `dependency:tree`), `resources:`, `plugin:`, `archetype:`, `release:`

**Why?** These flags and plugins enable arbitrary code execution. Use the allowed flags instead:
- Maven: `-Dproperty=value`, `-f`, `-P`, `-q`, `-X`, `-T`, `-B`, `-U`, `--batch-mode`, `--non-recursive`
- Gradle: `-x`/`--exclude-task`, `--parallel`, `--configure-on-demand`, `--build-cache`

---

## Runtime Errors

### "Cannot resolve path"

**Symptoms:** `IllegalArgumentException: Cannot resolve path: NoSuchFileException`

**Fixes:**

- Use absolute paths. The server canonicalizes all paths via `Path.toRealPath()`, which requires the path to exist.
- For Docker users: the path must exist inside the container. Use volume mounts: `-v /host/path:/projects`.
- Path traversal (`../`) is automatically resolved but the target must exist.

### Build succeeds in terminal but fails via MCP server

**Causes:**

1. **Different working directory** — The server sets the working directory to `projectDir`. If your project relies on relative paths outside that directory, it will fail.
2. **Different environment** — MCP client environments may not have the same PATH, JAVA_HOME, or other variables.
3. **Gradle daemon conflicts** — The server always uses `--no-daemon`. If you have a Gradle daemon running with different settings, the build may differ.

### "Command too long (max 500 characters)"

**Fix:** Break the command into multiple calls:

```
# Instead of one massive command:
execute_build_command(projectDir="...", command="clean compile test package -DskipTests -T4 -Pproduction ...")
# Do it in steps:
execute_build_command(projectDir="...", command="clean compile -T4")
execute_build_command(projectDir="...", command="test -Pproduction")
```

### SBT build times out on first run

**Why:** SBT downloads its launcher JAR and compiles the build definition on first run per project. This can take 1-3 minutes.

**Fix:** Warm up the SBT project first from the terminal:

```bash
cd /path/to/sbt-project
sbt --no-colors compile  # let it download + compile
# Now use via MCP server — subsequent runs are fast
```

### Out of memory during builds

**Symptoms:** Build fails with `OutOfMemoryError` or `GC overhead limit exceeded`.

**Fixes:**

1. **Increase JVM heap for the server process:**
   ```json
   "args": ["-Xms128m", "-Xmx512m", "-jar", "/path/to/jar.jar"]
   ```
   The Docker image defaults to `-Xms64m -Xmx256m` which is usually sufficient for the server itself. Build tool processes use their own JVM settings.

2. **For Maven builds,** set `MAVEN_OPTS` in the MCP client env:
   ```json
   "env": {
     "MAVEN_HOME": "/opt/maven",
     "MAVEN_OPTS": "-Xmx1024m"
   }
   ```

3. **For Gradle builds,** set `GRADLE_OPTS`:
   ```json
   "env": {
     "GRADLE_OPTS": "-Xmx1024m"
   }
   ```

---

## Dependency Service Issues

### "Dependency not found on Maven Central"

**Symptoms:** `{"error":true,"message":"Dependency not found on Maven Central: group:artifact"}`

**Fixes:**

- Verify the groupId and artifactId are correct. Check on [search.maven.org](https://search.maven.org).
- Remember that groupId uses dots (e.g., `com.google.guava`), not slashes.
- Some artifacts are only on other repositories (Google, Gradle Plugin Portal, etc.) — only Maven Central is queried.
- Check for typos: `spring-boot-starter-web` vs `spring-boot-start-web`.

### "Network error checking dependency version"

**Causes:**

- No internet access from the server process.
- Firewall/proxy blocking `https://repo1.maven.org/maven2`.
- Maven Central temporarily unavailable.

**Fixes:**

- Verify connectivity: `curl https://repo1.maven.org/maven2/com/google/guava/guava/maven-metadata.xml`
- The HTTP client has a 5-second connect timeout. Slow networks may cause timeout.
- Run without network restriction (Docker users: ensure container has outbound internet).

### Version comparison seems wrong

**How version comparison works:**
- Versions are split on `.` and `-`, then compared segment-by-segment numerically.
- Only version numbers are compared; qualifiers like `-jre`, `-SNAPSHOT`, `-rc1` are stripped for numeric comparison.
- Upgrade type: MAJOR (first segment differs), MINOR (second), PATCH (third+).

**Stability classification:**
- `SNAPSHOT` — contains `snapshot` in version string
- `ALPHA` — contains `alpha` or `-a`
- `BETA` — contains `beta` or `-b`
- `MILESTONE` — contains `milestone`, `-m`, or `.m`
- `RC` — contains `-rc` or `.rc`
- `STABLE` — none of the above

---

## Docker Issues

### Cannot mount project directories

**Symptoms:** The container can't see your project files.

**Fixes:**

1. Use absolute paths for volume mounts:
   ```bash
   docker run -i --rm \
     -v /home/me/my-project:/projects/my-project \
     -v /opt/maven:/opt/maven \
     -e MAVEN_HOME=/opt/maven \
     mcp-server-jvm-build-tools
   ```

2. For Docker Desktop on macOS, ensure the directory is shared in Docker Desktop settings (Preferences > Resources > File Sharing).

3. When using the MCP server inside Docker, project dirs in LLM prompts must use the container path (`/projects/my-project`), not the host path.

### Maven not found inside container

**Symptoms:** Maven version query fails, or build commands fail with "mvn: not found".

**Fix:** The Docker image installs Maven at build time (`apk add --no-cache maven`). If you need a specific Maven version, customize the Dockerfile to download a specific version.

### Container exits immediately

**Symptoms:** `docker run` exits without staying alive.

**Check:** The server blocks its main thread after Spring Boot startup. If the JAR fails to start, the process exits. Check container logs:
```bash
docker logs <container-id>
```

---

## Logging and Debugging

### Enable debug logging

The server suppresses Spring logging by default (`logging.level.org.springframework=WARN`). To see more detail:

1. Create a custom `application.properties` with:
   ```properties
   logging.level.com.pragmatik.buildtools=DEBUG
   logging.level.org.springframework=INFO
   ```

2. Or set via JVM system property:
   ```json
   "args": ["-Dlogging.level.com.pragmatik.buildtools=DEBUG", "-jar", "/path/to/jar.jar"]
   ```

3. Build output is captured as-is. If a build fails, the server returns the raw stdout/stderr which usually contains enough information to diagnose.

### Where to find MCP transport logs

- **Claude Desktop:** `~/Library/Logs/Claude/mcp*.log` (macOS), `%APPDATA%\Claude\logs` (Windows)
- **Cursor:** Output panel → select "MCP" from dropdown
- **Goose:** Check Goose logs in `~/.config/goose/logs/`
- **Continue:** Check the VS Code Output panel → select "Continue"

### Common error patterns in server output

| Error Pattern | Likely Cause |
|---|---|
| `Connection refused` | MCP client couldn't start the server process |
| `Broken pipe` | Client disconnected unexpectedly |
| `IllegalStateException: No ToolCallbackProvider` | Spring AI misconfiguration — JAR may be corrupted |
| `ClassNotFoundException: javax/xml/...` | JVM doesn't include XML modules (use JDK 21+, not a minimal JRE) |

---

## Performance Tuning

### Build times are slow via MCP server

**Causes and fixes:**

1. **--no-daemon overhead (Gradle):** The server always uses `--no-daemon` for process isolation. This adds ~2-5 seconds per Gradle command. For repeated builds, consider warming the Gradle cache first.

2. **SBT first-run overhead:** SBT downloads its launcher and compiles build definitions on first project access. Warm up manually before using via MCP.

3. **Maven Embedder startup:** Version queries use the embedder in-process, which initializes Maven components. The first call is slower; subsequent calls within the same server session are fast.

4. **Network latency for dependency checks:** `check_dependency_version` makes an HTTP call to Maven Central. Caching is not implemented — each call is independent.

### Reducing memory usage

The Docker image uses G1GC with string deduplication and exits on OOM:
```
-Xms64m -Xmx256m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:+ExitOnOutOfMemoryError
```

For memory-constrained environments:
- Reduce `-Xmx` (128m is usually sufficient for the server alone)
- The server is a thin proxy — most memory is consumed by build tool processes, not the server itself
- Set `MAVEN_OPTS` and `GRADLE_OPTS` to limit build tool memory

---

## Getting Help

If your issue isn't covered here:

1. Check [SECURITY.md](SECURITY.md) if it's security-related
2. Check [TOOLS.md](TOOLS.md) for exact tool parameter schemas
3. Check [ARCHITECTURE.md](ARCHITECTURE.md) for understanding internal behavior
4. Open a [GitHub Issue](https://github.com/thepragmatik/mcp-server-jvm-build-tools/issues) with:
   - Server version
   - MCP client and version
   - Build tool and version
   - Full error message
   - Steps to reproduce
