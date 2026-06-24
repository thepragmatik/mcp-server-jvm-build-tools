# MCP Protocol Evidence — 1.0.0 Release Verification

This document captures the **MCP protocol-level evidence** collected against `mcp-server-jvm-build-tools v1.0.0` in an isolated Docker environment. All three supported build toolkits (Maven, Gradle, sbt) were exercised via the standard MCP protocol over stdio transport.

## Test Environment

| Component | Configuration |
|-----------|--------------|
| Container | `swarm-pi` (Debian, JDK 21.0.11) |
| MCP Client | `@modelcontextprotocol/sdk` v1.29.0 |
| Transport | stdio (standard MCP protocol) |
| Server JAR | `mcp-server-jvm-build-tools-1.0.0.jar` |
| Maven | 3.9.9 |
| Gradle | 8.12 |
| sbt | 1.10.10 |
| Host Maven cache | `~/.m2/repository` mounted read-only |

## 1. Tool Discovery (tools/list)

The server exposes **28 MCP tools** across all build lifecycles:

```
  • list_build_tools           — List registered build tools and commands
  • execute_build_command      — Execute Maven/Gradle/sbt builds
  • detect_build_tool          — Auto-detect build tool from project files
  • analyze_build_output       — Parse build output with error/warning extraction
  • validate_build_configuration — Validate pom.xml, build.gradle, build.sbt
  • detect_dependency_conflicts — Scan for version conflicts
  • check_dependency_version   — Check Maven Central for newer versions
  • check_credential_status    — Scan credential configurations
  • get_build_tool_version     — Query installed tool versions
  • analyze_sbt_build          — Parse sbt build structure
  • detect_sbt_modules         — Detect sbt multi-module projects
  • detect_sbt_test_frameworks — Detect sbt test framework usage
  • profile_build              — Build with performance instrumentation
  • analyze_build_performance  — Analyze historical build performance
  • (and 14 more: resource access, prompts, auth, auditing)
```

## 2. Build Tool Auto-Detection (detect_build_tool)

Each test project was correctly identified by its marker file:

| Project | Marker File | Detected Tool | Status |
|---------|------------|---------------|--------|
| `/tmp/mcp-test/maven/` | `pom.xml` | Maven | ✓ passed |
| `/tmp/mcp-test/gradle/` | `build.gradle` | Gradle | ✓ passed |
| `/tmp/mcp-test/sbt/` | `build.sbt` | sbt | ✓ passed |

## 3. Build Execution (execute_build_command)

All three build tools successfully compiled real source code via the MCP protocol:

### Maven compile
```
✓ BUILD SUCCESS — 1 source file compiled, 0.3s
```

### Gradle compileJava
```
✓ BUILD SUCCESSFUL — 1 actionable task: 1 executed, 2s
```

### sbt compile
```
✓ BUILD SUCCESS — 1 Scala source compiled, 4s
```

## 4. Build Tool Command Coverage (list_build_tools)

Each tool exposes its full range of commands:

| Tool | Supported Commands |
|------|-------------------|
| Maven | `clean`, `compile`, `test`, `package`, `install`, `deploy`, `validate` |
| Gradle | `compileJava`, `build`, `dependencies`, `test`, `jar`, `compileTestJava`, `clean`, `assemble`, `publishToMavenLocal`, `projects`, `tasks`, `check` |
| sbt | `publishLocal`, `compile`, `publish`, `doc`, `assembly`, `update`, `run`, `clean`, `package`, `console`, `test` |

## 5. Transport Verification

The server card confirms both transport modes:

```json
{
  "name": "MCP Server - Build Tools for the JVM",
  "version": "1.0.0",
  "transports": ["stdio", "streamable-http"],
  "mcpVersions": ["2024-11-05", "2025-03-26", "2026-07-28"],
  "supportedBuildTools": [
    {"name": "maven", "detectionFile": "pom.xml"},
    {"name": "gradle", "detectionFile": "build.gradle(.kts)"},
    {"name": "sbt", "detectionFile": "build.sbt"}
  ]
}
```

## Conclusion

The MCP server passes end-to-end protocol verification for all three build toolkits. Tools are correctly registered, auto-detected, and executed over the MCP stdio transport. This evidence was collected in an isolated Docker container with the host Maven cache mounted for fast builds — exactly the configuration documented for production deployments.
