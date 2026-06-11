# Architecture — mcp-server-jvm-build-tools

## Overview

This project is an MCP (Model Context Protocol) server that gives AI agents hands-on access to JVM build tools — Maven, Gradle, and SBT — through a single unified interface. Built with Spring Boot 3.5.14 and Spring AI 2.0.0-RC2, it exposes build operations as MCP tools discoverable by any MCP-compatible client.

## High-Level Architecture

```
┌──────────────────────────────────────────────────────────┐
│                    MCP Client (e.g., Claude Desktop)      │
│                         stdio transport                   │
└──────────────────────┬───────────────────────────────────┘
                       │ JSON-RPC (MCP protocol)
┌──────────────────────▼───────────────────────────────────┐
│              Spring AI MCP Server (auto-configured)       │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │           BuildToolsApplication                   │   │
│  │  @SpringBootApplication                          │   │
│  │  @Bean → ToolCallbackProvider(...)                │   │
│  │    └─ toolObjects(buildToolsService,              │   │
│  │                    dependencyService)              │   │
│  └──────────────────┬───────────────────────────────┘   │
│                     │                                     │
│         ┌───────────┴───────────┐                        │
│         │                       │                        │
│  ┌──────▼──────┐       ┌───────▼────────┐               │
│  │ BuildTools  │       │ Dependency     │               │
│  │ Service     │       │ Service        │               │
│  │ @Service    │       │ @Service       │               │
│  │ 6 MCP tools │       │ 1 MCP tool     │               │
│  └──────┬──────┘       └───────┬────────┘               │
│         │                      │                         │
│  ┌──────▼──────────────────────▼────────┐               │
│  │         BuildToolProvider             │               │
│  │         @Component                    │               │
│  │  Registry + Auto-Detection            │               │
│  └──────┬───────────────┬───────────────┘               │
│         │               │                                 │
│  ┌──────▼──────┐ ┌──────▼──────┐ ┌──────▼──────┐        │
│  │  Maven      │ │  Gradle     │ │  SBT        │        │
│  │  BuildTool  │ │  BuildTool  │ │  BuildTool  │        │
│  │  (embedder  │ │  (CLI via   │ │  (CLI via   │        │
│  │   + invoker)│ │  ProcessBld)│ │  ProcessBld)│        │
│  └─────────────┘ └─────────────┘ └─────────────┘        │
└──────────────────────────────────────────────────────────┘
```

## Core Components

### 1. Application Entry Point — `BuildToolsApplication`

- Spring Boot application with `@SpringBootApplication`
- Registers two service beans as MCP tool callbacks via `MethodToolCallbackProvider`
- Blocks `main` thread after startup to keep the JVM alive for stdio MCP transport
- No web endpoints — purely stdio-based MCP server

### 2. Service Provider Interface — `BuildTool`

Defines the contract that all build tool implementations must satisfy:

| Method | Purpose |
|--------|---------|
| `getName()` | Canonical name ("maven", "gradle", "sbt") |
| `version()` | Query installed version |
| `executeCommand(home, dir, cmd)` | Execute a build command |
| `isProject(dir)` | Detect project markers (e.g., pom.xml) |
| `getSupportedCommands()` | List valid lifecycle commands |
| `getExecutionPrompt()` | LLM execution prompt with tool-specific rules |

New build tools (Bazel, Ant, etc.) can be added by implementing this interface.

### 3. Build Tool Implementations

#### `MavenBuildTool`
- Uses `MavenInvoker` (out-of-process via Maven Shared Invoker 3.3.0)
- Version query uses `MavenEmbedder` (in-process, no external process)
- Requires `buildToolHome` pointing to a Maven installation
- Detects projects via `pom.xml`
- Supported commands: `clean`, `compile`, `test`, `package`, `install`, `deploy`, `validate`
- Safe flags: `-D`, `-f`, `-P`, `-q`, `-X`, `-T`, `-B`, `-U`, `--batch-mode`, `--non-recursive`

#### `GradleBuildTool`
- CLI invocation via `ProcessBuilder` with `--no-daemon --console=plain`
- Auto-detects `gradlew` wrapper; falls back to system `gradle` on PATH
- Detects projects via `build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts`
- Supported commands: `clean`, `build`, `test`, `compileJava`, `compileTestJava`, `jar`, `assemble`, `check`, `publishToMavenLocal`, `dependencies`, `projects`, `tasks`
- Safe flags: `-x`, `--exclude-task`, `--parallel`, `--configure-on-demand`, `--build-cache`
- Blocked flags: `--init-script`/`-I`, `--build-file`/`-b`, `--project-dir`/`-p`, `--include-build`, `--system-prop`, `-D`

#### `SbtBuildTool`
- CLI invocation via `ProcessBuilder` with `--no-colors`
- Auto-detects `sbt` wrapper; falls back to system `sbt` on PATH
- Detects projects via `build.sbt`
- Supported commands: `compile`, `test`, `run`, `package`, `clean`, `assembly`, `publishLocal`, `publish`, `update`, `doc`, `console`
- Same three-layer security model as Gradle

### 4. Tool Registry — `BuildToolProvider`

- `@Component` Spring bean maintaining a `LinkedHashMap<String, BuildTool>` in insertion order
- Registration order (Maven → Gradle → SBT) determines auto-detection priority
- `resolve(name, projectDir)` — explicit lookup or auto-detection by scanning markers
- `getAllTools()` — read-only view for `list_build_tools`

### 5. MCP Tool Services

#### `BuildToolsService` (6 tools)

| Tool Name | Description |
|-----------|-------------|
| `get_build_tool_version` | Version of any registered build tool |
| `execute_build_command` | Execute a build command with auto-detection |
| `list_build_tools` | List all registered tools and commands |
| `detect_build_tool` | Scan project dir for build markers (JSON) |
| `analyze_build_output` | Execute + parse output into structured JSON |
| `validate_build_configuration` | Static analysis of build files |

Security enforced before delegation: 500-char limit, character pattern validation, path canonicalization via `toRealPath()`, directory existence checks.

#### `DependencyService` (1 tool)

| Tool Name | Description |
|-----------|-------------|
| `check_dependency_version` | Query Maven Central REST API for version info |

Features HTTP client with 5s timeout, queries maven-metadata.xml, parses versions with stability classification (STABLE/RC/MILESTONE/BETA/ALPHA/SNAPSHOT), semver-aware comparison, and project context enrichment (returns correct dependency syntax for detected build tool).

### 6. Output Parsers

`BuildOutputParser` interface with two implementations:

| Parser | Extracts |
|--------|----------|
| `MavenOutputParser` | BUILD SUCCESS/FAILURE, test counts, compile errors with file:line, warnings |
| `GradleOutputParser` | BUILD SUCCESSFUL/FAILED, test summaries, error references, warnings |

Both produce structured JSON: `{success, tool, command, testSummary, errors, warnings, errorCount, warningCount, duration}`.

### 7. Maven Invoker — `MavenInvoker`

Low-level Maven execution with two modes:
- `executeCommandUsingMavenInvoker()` — out-of-process via Maven Shared Invoker API
- `executeUsingMavenEmbedder()` — in-process via Maven Embedder API (version queries)
- `getCommands()` — command parsing with allowlist enforcement and shell injection blocking

## How Tools Are Registered

Spring AI's `MethodToolCallbackProvider` scans `@Tool` annotations on service beans at startup:

```java
@Bean
public ToolCallbackProvider buildTools(
        BuildToolsService buildToolsService,
        DependencyService dependencyService) {
    return MethodToolCallbackProvider.builder()
            .toolObjects(buildToolsService, dependencyService)
            .build();
}
```

Each `@Tool` method becomes an MCP tool. `@ToolParam` annotations define parameter schemas. The MCP SDK (bundled with Spring AI) handles `initialize`, `tools/list`, `tools/call`, and JSON-RPC serialization over stdio.

## Security Model

Three consistent layers across all build tools:

1. **Task Allowlist** — Only predefined commands execute; unknown goals are rejected before spawning
2. **Flag Blocklist** — Dangerous flags that enable code execution or file access are blocked
3. **Safe-Argument Pattern** — All arguments must match regex rejecting shell metacharacters

Service-layer protections: command length limit, character validation, path canonicalization, directory existence checks, process isolation (out-of-process Maven, --no-daemon Gradle).

## Technology Stack

| Component | Version | Purpose |
|-----------|---------|---------|
| Java | 21+ | Runtime |
| Spring Boot | 3.5.14 | Application framework |
| Spring AI | 2.0.0-RC2 | MCP server framework |
| MCP SDK | 2.0.0-RC1 | Bundled protocol implementation |
| Maven Embedder | 3.9.9 | In-process Maven |
| Maven Shared Invoker | 3.3.0 | Out-of-process Maven |
| JUnit 5 | (test) | Test framework |
| JaCoCo | (test) | Code coverage |

## Package Structure

```
com.pragmatik.buildtools
├── BuildToolsApplication       # Main class, entry point
├── BuildTool                   # SPI interface
├── MavenBuildTool              # Maven implementation
├── GradleBuildTool             # Gradle implementation
├── SbtBuildTool                # SBT implementation
├── BuildToolProvider           # Registry + auto-detection
├── BuildToolsService           # MCP tool service (6 tools)
├── DependencyService           # MCP tool service (1 tool)
├── MavenInvoker                # Maven utilities + security
├── BuildOutputParser           # SPI for output parsing
├── MavenOutputParser           # Maven output parser
└── GradleOutputParser          # Gradle output parser
```

## Extending with a New Build Tool

1. Implement `BuildTool` with custom parsing and security
2. Register in `BuildToolProvider` constructor
3. Add a `BuildOutputParser` implementation
4. Register parser in `BuildToolsService.outputParsers`
5. Add detection hints in `detectBuildTool()` switch statement
6. Add dependency syntax in `DependencyService.enrichWithProjectContext()`
7. Add integration tests following existing patterns
