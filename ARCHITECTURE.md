# Architecture — mcp-server-jvm-build-tools

## Overview

This project is an MCP (Model Context Protocol) server that gives AI agents hands-on access to JVM build tools — Maven, Gradle, and SBT — through a single unified interface. Built with Spring Boot 3.5.14 and Spring AI 2.0.0-RC2, it exposes build operations as MCP tools discoverable by any MCP-compatible client.

## High-Level Architecture

```
+------------------------------------------------------------+
|             MCP Client (e.g., Claude Desktop)               |
|        stdio transport OR Streamable HTTP                   |
+---------------------------+--------------------------------+
                            | JSON-RPC (MCP protocol)
+---------------------------v--------------------------------+
|              Spring AI MCP Server (auto-configured)         |
|                                                            |
|  +------------------------------------------------------+  |
|  |           BuildToolsApplication                       |  |
|  |  @SpringBootApplication                              |  |
|  |  @Bean -> ToolCallbackProvider(12 services)           |  |
|  |    +-- toolObjects(/* 12 service beans */)            |  |
|  +---------------------------+--------------------------+  |
|                              |                              |
|             +----------------v-----------------+            |
|             |  12 Service Beans, 28 MCP Tools  |            |
|             |  Auto-discovered by               |            |
|             |  MethodToolCallbackProvider       |            |
|             +----------------+-----------------+            |
|                              |                              |
|   +--------------------------v---------------------------+  |
|   |          BuildToolProvider (@Component)               |  |
|   |       Registry + Auto-Detection                       |  |
|   +----+-----------------+-----------------+------------+  |
|        |                 |                 |               |
|  +-----v------+   +-----v------+   +-----v------+        |
|  | Maven      |   | Gradle     |   | SBT        |        |
|  | BuildTool  |   | BuildTool  |   | BuildTool  |        |
|  | (embedder  |   | (CLI via   |   | (CLI via   |        |
|  |  + invoker)|   | ProcessBld)|   | ProcessBld)|        |
|  +------------+   +------------+   +------------+        |
|                                                            |
|  +------------------------------------------------------+  |
|  |  REST Controllers (Streamable HTTP mode only)         |  |
|  |  +--------------------+  +-------------------------+  |  |
|  |  | ServerCard         |  | BuildEvent              |  |  |
|  |  | Controller         |  | Controller              |  |  |
|  |  | .well-known,       |  | SSE stream              |  |  |
|  |  | health endpoints   |  |                         |  |  |
|  |  +--------------------+  +-------------------------+  |  |
|  +------------------------------------------------------+  |
+------------------------------------------------------------+
```

## Core Components

### 1. Application Entry Point - BuildToolsApplication

- Spring Boot application with @SpringBootApplication
- Registers 12 service beans as MCP tool callbacks via MethodToolCallbackProvider
- Blocks main thread after startup to keep the JVM alive for stdio MCP transport
- Streamable HTTP mode launched via --spring.profiles.active=http which starts an embedded Tomcat on port 8080

### 2. Service Provider Interface - BuildTool

Defines the contract that all build tool implementations must satisfy:

| Method | Purpose |
|--------|---------|
| getName() | Canonical name ("maven", "gradle", "sbt") |
| version() | Query installed version |
| executeCommand(home, dir, cmd) | Execute a build command |
| isProject(dir) | Detect project markers (e.g., pom.xml) |
| getSupportedCommands() | List valid lifecycle commands |
| getExecutionPrompt() | LLM execution prompt with tool-specific rules |

New build tools (Bazel, Ant, etc.) can be added by implementing this interface.

### 3. Build Tool Implementations

#### MavenBuildTool
- Uses MavenInvoker (out-of-process via Maven Shared Invoker 3.3.0)
- Version query uses MavenEmbedder (in-process, no external process)
- Requires buildToolHome pointing to a Maven installation
- Detects projects via pom.xml
- Supported commands: clean, compile, test, package, install, deploy, validate
- Safe flags: -D, -f, -P, -q, -X, -T, -B, -U, --batch-mode, --non-recursive

#### GradleBuildTool
- CLI invocation via ProcessBuilder with --no-daemon --console=plain
- Auto-detects gradlew wrapper; falls back to system gradle on PATH
- Detects projects via build.gradle, build.gradle.kts, settings.gradle, settings.gradle.kts
- Supported commands: clean, build, test, compileJava, compileTestJava, jar, assemble, check, publishToMavenLocal, dependencies, projects, tasks
- Safe flags: -x, --exclude-task, --parallel, --configure-on-demand, --build-cache
- Blocked flags: --init-script/-I, --build-file/-b, --project-dir/-p, --include-build, --system-prop, -D

#### SbtBuildTool
- CLI invocation via ProcessBuilder with --no-colors
- Auto-detects sbt wrapper; falls back to system sbt on PATH
- Detects projects via build.sbt
- Supported commands: compile, test, run, package, clean, assembly, publishLocal, publish, update, doc, console
- Same three-layer security model as Gradle

### 4. Tool Registry - BuildToolProvider

- @Component Spring bean maintaining a LinkedHashMap<String, BuildTool> in insertion order
- Registration order (Maven -> Gradle -> SBT) determines auto-detection priority
- resolve(name, projectDir) - explicit lookup or auto-detection by scanning markers
- getAllTools() - read-only view for list_build_tools

### 5. MCP Tool Services (12 services, 28 tools)

Services are auto-registered via MethodToolCallbackProvider in BuildToolsApplication.

#### Service Map

| Service | Tools | Description |
|---------|-------|-------------|
| BuildToolsService | 6 | Build execution, detection, validation, output analysis |
| DependencyService | 1 | Maven Central version lookups |
| PromptService | 3 | Build/test/diagnosis prompt templates |
| BuildResourceService | 2 | Build resource listing and reading |
| DependencyResourceService | 2 | Dependency resource listing and reading |
| ResourceTemplateService | 2 | Parameterized URI template resources |
| SbtProjectService | 3 | SBT module detection, test frameworks, build analysis |
| BuildAuthService | 1 | Maven/Gradle credential status scanning |
| DependencyConflictService | 1 | Dependency version conflict detection |
| JavaVersionService | 1 | Java/JDK version compatibility checking |
| BuildPerformanceService | 2 | Build profiling and performance analysis |
| AsyncBuildService | 4 | Async build task lifecycle (execute, get, cancel, list) |
| ToolAuthorizationService | 4 | Scope-based authorization, audit logging, token validation |
| BuildCacheService | 2 | Build cache health analysis and optimization |
| TestFlakinessService | 2 | Flaky test detection and history analysis |
| SupplyChainService | 3 | SBOM generation, supply chain audit, license compliance |

### 6. MCP Tool Services Detail

#### BuildToolsService (6 tools)

| Tool Name | Description |
|-----------|-------------|
| get_build_tool_version | Version of any registered build tool |
| execute_build_command | Execute a build command with auto-detection |
| list_build_tools | List all registered tools and commands |
| detect_build_tool | Scan project dir for build markers (JSON) |
| analyze_build_output | Execute + parse output into structured JSON |
| validate_build_configuration | Static analysis of build files |

Security enforced before delegation: 500-char limit, character pattern validation, path canonicalization via toRealPath(), directory existence checks.

#### DependencyService (1 tool)

| Tool Name | Description |
|-----------|-------------|
| check_dependency_version | Query Maven Central REST API for version info |

Features HTTP client with 5s timeout, queries maven-metadata.xml, parses versions with stability classification (STABLE/RC/MILESTONE/BETA/ALPHA/SNAPSHOT), semver-aware comparison, and project context enrichment (returns correct dependency syntax for detected build tool).

#### PromptService (3 tools)

| Tool Name | Description |
|-----------|-------------|
| prompt_build_and_test | Template for build and test workflows |
| prompt_dependency_audit | Template for dependency analysis |
| prompt_build_diagnosis | Template for build failure diagnosis |

#### BuildResourceService (2 tools)

| Tool Name | Description |
|-----------|-------------|
| list_build_resources | List available build configuration resources |
| read_build_resource | Read build file content via build:// URI scheme |

#### DependencyResourceService (2 tools)

| Tool Name | Description |
|-----------|-------------|
| list_dependency_resources | List dependency resources from all build tools |
| read_dependency_resource | Read dependency details via dependency:// URI scheme |

#### ResourceTemplateService (2 tools)

| Tool Name | Description |
|-----------|-------------|
| list_resource_templates | List parameterized URI template resources |
| resolve_resource_template | Resolve a parameterized resource template |

#### SbtProjectService (3 tools)

| Tool Name | Description |
|-----------|-------------|
| detect_sbt_modules | Detect SBT multi-module project structure |
| detect_sbt_test_frameworks | Detect test frameworks used in SBT project |
| analyze_sbt_build | Analyze SBT build configuration |

#### BuildAuthService (1 tool)

| Tool Name | Description |
|-----------|-------------|
| check_credential_status | Scan Maven settings.xml and Gradle properties for credential configuration |

#### DependencyConflictService (1 tool)

| Tool Name | Description |
|-----------|-------------|
| detect_dependency_conflicts | Detect version conflicts in Maven/Gradle projects |

#### JavaVersionService (1 tool)

| Tool Name | Description |
|-----------|-------------|
| check_java_compatibility | Check Java/JDK version compatibility for build tools |

#### BuildPerformanceService (2 tools)

| Tool Name | Description |
|-----------|-------------|
| profile_build | Profile build execution with timing instrumentation |
| analyze_build_performance | Analyze build configuration for performance optimizations |

#### AsyncBuildService (4 tools)

| Tool Name | Description |
|-----------|-------------|
| execute_build_async | Fire-and-forget async build execution |
| get_build_task | Poll async build task status |
| cancel_build_task | Cancel a running async build task |
| list_build_tasks | List active and recent build tasks |

#### ToolAuthorizationService (4 tools)

| Tool Name | Description |
|-----------|-------------|
| check_tool_authorization | Pre-validate tool access against scope permissions |
| list_available_scopes | Enumerate scopes with tool coverage |
| audit_tool_access | Read OWASP MCP06 audit logs |
| validate_access_token | Validate API keys configured via BUILDTOOLS_API_KEY_* env vars |

#### BuildCacheService (2 tools)

| Tool Name | Description |
|-----------|-------------|
| analyze_cache_health | Build caching configuration audit with hit-rate scoring |
| optimize_build_cache | Build-tool-specific optimization config snippets |

#### TestFlakinessService (2 tools)

| Tool Name | Description |
|-----------|-------------|
| detect_flaky_tests | Multi-run test execution with flakiness scoring |
| analyze_test_history | Historical pass/fail trend analysis |

#### SupplyChainService (3 tools)

| Tool Name | Description |
|-----------|-------------|
| generate_sbom | CycloneDX/SPDX SBOM generation |
| audit_supply_chain | OSV.dev vulnerability cross-referencing |
| check_license_compliance | License classification with risk assessment |

### 7. Output Parsers

BuildOutputParser interface with three implementations:

| Parser | Extracts |
|--------|----------|
| MavenOutputParser | BUILD SUCCESS/FAILURE, test counts, compile errors with file:line, warnings |
| GradleOutputParser | BUILD SUCCESSFUL/FAILED, test summaries, error references, warnings |
| SbtOutputParser | ScalaTest/JUnit pass/fail, file:line errors, structured results |

All produce structured JSON: {success, tool, command, testSummary, errors, warnings, errorCount, warningCount, duration}.

### 8. Maven Invoker - MavenInvoker

Low-level Maven execution with two modes:
- executeCommandUsingMavenInvoker() - out-of-process via Maven Shared Invoker API
- executeUsingMavenEmbedder() - in-process via Maven Embedder API (version queries)
- getCommands() - command parsing with allowlist enforcement and shell injection blocking

### 9. REST Controllers (Streamable HTTP mode)

#### ServerCardController
- GET /.well-known/mcp-server - Server metadata for MCP discoverability
- GET /health - Health check endpoint
- GET /health/ready - Readiness probe (for K8s/Docker orchestration)
- GET /health/live - Liveness probe (for K8s/Docker orchestration)

Used by MCP clients for auto-discovery without requiring full MCP protocol connection.

#### BuildEventController
- GET /mcp/build-events/stream - SSE stream for real-time build events

## How Tools Are Registered

Spring AI's MethodToolCallbackProvider scans @Tool annotations on service beans at startup. Each service bean is registered individually, and Spring AI generates tool schemas from @Tool and @ToolParam annotations. The MCP SDK handles initialize, tools/list, tools/call, and JSON-RPC serialization over stdio or Streamable HTTP.

## Security Model

Three consistent layers across all build tools:

1. Task Allowlist - Only predefined commands execute; unknown goals are rejected before spawning
2. Flag Blocklist - Dangerous flags that enable code execution or file access are blocked
3. Safe-Argument Pattern - All arguments must match regex rejecting shell metacharacters

Extended to 5-layer defense:
4. Input Validation - Path canonicalization, character limits, directory existence checks
5. Process Isolation - Out-of-process Maven, --no-daemon Gradle/SBT, configurable timeouts

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
+-- BuildToolsApplication            # Main class, entry point
+-- BuildTool                        # SPI interface
+-- MavenBuildTool                   # Maven implementation
+-- GradleBuildTool                  # Gradle implementation
+-- SbtBuildTool                     # SBT implementation
+-- BuildToolProvider                # Registry + auto-detection
+-- BuildToolsService                # MCP tool service (6 tools)
+-- DependencyService                # MCP tool service (1 tool)
+-- PromptService                    # MCP prompt service (3 prompts)
+-- BuildResourceService             # MCP resource service (build configs)
+-- DependencyResourceService        # MCP resource service (dependencies)
+-- ResourceTemplateService          # MCP resource template service
+-- SbtProjectService                # MCP tool service (SBT-specific, 3 tools)
+-- BuildAuthService                 # MCP tool service (credential scanning)
+-- DependencyConflictService        # MCP tool service (conflict detection)
+-- JavaVersionService               # MCP tool service (Java compatibility)
+-- BuildPerformanceService          # MCP tool service (profiling, 2 tools)
+-- AsyncBuildService                # MCP tool service (async tasks, 4 tools)
+-- ToolAuthorizationService         # MCP tool service (auth/audit, 4 tools)
+-- BuildCacheService                # MCP tool service (cache health, 2 tools)
+-- TestFlakinessService             # MCP tool service (flaky tests, 2 tools)
+-- SupplyChainService               # MCP tool service (SBOM/audit, 3 tools)
+-- ToolAuditLogger                  # Audit logging component
+-- ToolPermission                   # Permission model
+-- ServerCardController             # REST controller (discoverability)
+-- BuildEventController             # REST controller (SSE events)
+-- MavenInvoker                     # Maven utilities + security
+-- BuildOutputParser                # SPI for output parsing
+-- MavenOutputParser                # Maven output parser
+-- GradleOutputParser               # Gradle output parser
+-- SbtOutputParser                  # SBT output parser
+-- JsonUtils                        # JSON utility helpers
+-- TransportConfig                  # HTTP transport configuration
+-- TransportLoggingFilter           # HTTP request logging filter
```

## Extending with a New Build Tool

1. Implement BuildTool with custom parsing and security
2. Register in BuildToolProvider constructor
3. Add a BuildOutputParser implementation
4. Register parser in BuildToolsService.outputParsers
5. Add detection hints in detectBuildTool() switch statement
6. Add dependency syntax in DependencyService.enrichWithProjectContext()
7. Add integration tests following existing patterns

## Extending with a New MCP Tool

1. Create a new @Service class with @Tool-annotated methods
2. Define parameters using @ToolParam annotations
3. Register the service bean in BuildToolsApplication.buildTools() method
4. Add the tool documentation to TOOLS.md following the existing pattern
5. Add tests following the project's JUnit 5 patterns
