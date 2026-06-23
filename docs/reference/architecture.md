# Architecture

This server gives AI agents a single, unified interface to JVM build tools — Maven, Gradle, and
SBT — over the Model Context Protocol. It is built with **Spring Boot 3.5.14** and **Spring AI
2.0.0-RC2**, which auto-configures the MCP server and discovers tools from annotations.

## High-level view

```text
┌───────────────────────────────────────────────────────────────────────────┐
│                       MCP client (e.g. Claude Desktop)                      │
│                    stdio transport  OR  Streamable HTTP                      │
└───────────────────────────────┬───────────────────────────────────────────┘
                                 │ JSON-RPC (MCP protocol)
┌───────────────────────────────▼───────────────────────────────────────────┐
│                  Spring AI MCP server (auto-configured)                      │
│                                                                             │
│   BuildToolsApplication  (@SpringBootApplication)                           │
│     └─ @Bean ToolCallbackProvider → MethodToolCallbackProvider              │
│          registers 12 service beans → 28 MCP tools                          │
│                                                                             │
│   BuildToolProvider (@Component): registry + auto-detection                  │
│        ┌──────────────┬──────────────┬──────────────┐                       │
│        ▼              ▼              ▼                                        │
│   MavenBuildTool  GradleBuildTool  SbtBuildTool                              │
│   (invoker +      (CLI via         (CLI via                                  │
│    embedder)       ProcessBuilder)  ProcessBuilder)                          │
│                                                                             │
│   REST controllers (Streamable HTTP mode only):                             │
│     ServerCardController (.well-known + health)   BuildEventController (SSE) │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Core components

### Application entry point — `BuildToolsApplication`

- A `@SpringBootApplication` whose `main` starts Spring and then **blocks the main thread** so the
  JVM stays alive for the stdio transport.
- Declares a single `@Bean ToolCallbackProvider buildTools(...)` that wires the MCP tool surface
  via `MethodToolCallbackProvider.builder().toolObjects(...)`.
- The Streamable HTTP transport is activated with the `http` Spring profile, which starts an
  embedded servlet container.

### Build tool SPI — `BuildTool`

Every build tool implementation satisfies one interface:

| Method | Purpose |
|--------|---------|
| `getName()` | Canonical name — `"maven"`, `"gradle"`, `"sbt"`. |
| `version()` | Query the installed version. |
| `executeCommand(home, dir, cmd)` | Run a build command in a project directory. |
| `isProject(dir)` | Detect this tool's project markers. |
| `getSupportedCommands()` | List the allowlisted lifecycle commands/tasks. |
| `getExecutionPrompt()` | An LLM prompt describing the tool's syntax and security rules. |

New build tools (Bazel, Ant, Mill, …) are added by implementing this interface and registering it.

### Build tool implementations

=== "MavenBuildTool"

    - Executes out-of-process via the **Maven Shared Invoker** (3.3.0).
    - Version queries use the **Maven Embedder** (3.9.9), in-process — no external process.
    - Requires a `buildToolHome` pointing at a Maven installation.
    - Detects projects via `pom.xml`.
    - Commands: `clean, compile, test, package, install, deploy, validate`.
    - Safe flags: `-D` (non-denied keys), `-f`, `-P`, `-q`, `-X`, `-T`, `-B`, `-U`,
      `--batch-mode`, `--non-recursive`.

=== "GradleBuildTool"

    - Executes via `ProcessBuilder` with `--no-daemon --console=plain`.
    - Auto-detects the `gradlew` wrapper; falls back to `gradle` on `PATH`.
    - Detects projects via `build.gradle`, `build.gradle.kts`, `settings.gradle`,
      `settings.gradle.kts`.
    - Commands: `clean, build, test, compileJava, compileTestJava, jar, assemble, check,
      publishToMavenLocal, dependencies, projects, tasks`.
    - Blocked flags: `--init-script`/`-I`, `--build-file`/`-b`, `--project-dir`/`-p`,
      `--include-build`, `--system-prop`/`-D`.

=== "SbtBuildTool"

    - Executes via `ProcessBuilder` with `--no-colors`.
    - Auto-detects an `sbt` wrapper; falls back to `sbt` on `PATH`.
    - Detects projects via `build.sbt`.
    - Commands: `compile, test, run, package, clean, assembly, publishLocal, publish, update, doc,
      console`.
    - Same three-layer security model as Gradle, plus blocked `-D`, `-J`, and launcher flags.

### Tool registry — `BuildToolProvider`

- A `@Component` holding a `LinkedHashMap<String, BuildTool>` in insertion order.
- Registration order (Maven → Gradle → SBT) sets the **auto-detection priority**.
- `resolve(name, projectDir)` performs an explicit lookup or auto-detection by scanning markers.

## The MCP tool surface (28 tools, 12 services)

Tools are registered by passing service beans to `MethodToolCallbackProvider.toolObjects(...)` in
`BuildToolsApplication`. Spring AI scans each bean's `@Tool` and `@ToolParam` annotations at
startup and generates the JSON schemas exposed via `tools/list`.

| Service | Tools | Responsibility |
|---------|:-----:|----------------|
| `BuildToolsService` | 6 | Version, execution, detection, output analysis, config validation |
| `DependencyService` | 1 | Maven Central version lookups |
| `PromptService` | 3 | Build/test/diagnosis prompt templates |
| `BuildResourceService` | 2 | List/read build resources (`build://` URIs) |
| `DependencyResourceService` | 2 | List/read dependency resources |
| `ResourceTemplateService` | 2 | Parameterised URI template resources |
| `SbtProjectService` | 3 | SBT module/test-framework detection, build analysis |
| `BuildAuthService` | 1 | Maven/Gradle credential-configuration scanning |
| `DependencyConflictService` | 1 | Dependency version-conflict detection |
| `BuildPerformanceService` | 2 | Build profiling and performance analysis |
| `JavaVersionService` | 1 | Java/JDK compatibility checking |
| `ToolAuthorizationService` | 4 | Scope-based authorization, audit reads, token validation |
| **Total** | **28** | |

Full per-tool inputs and outputs are in the [Tools / MCP API reference](tools.md).

!!! info "Services present in the source but not currently registered"
    The codebase also contains `AsyncBuildService`, `BuildCacheService`, `TestFlakinessService`,
    and `SupplyChainService`. These `@Service` beans are **not** passed to
    `MethodToolCallbackProvider.toolObjects(...)` in `BuildToolsApplication`, so their `@Tool`
    methods are **not** exposed over MCP. This documentation describes the **28 tools that an MCP
    client actually discovers** via `tools/list`. If those services are wired in later, this
    reference should be updated to match.

## Output parsers

`analyze_build_output` delegates to a `BuildOutputParser` per build tool:

| Parser | Extracts |
|--------|----------|
| `MavenOutputParser` | `BUILD SUCCESS`/`FAILURE`, test counts, compile errors with `file:line`, warnings |
| `GradleOutputParser` | `BUILD SUCCESSFUL`/`FAILED`, test summaries, error references, warnings |
| `SbtOutputParser` | ScalaTest/JUnit pass/fail, `file:line` errors, structured results |

All produce a common JSON shape:
`{ success, tool, command, testSummary, errors, warnings, errorCount, warningCount, duration }`.

## Maven invoker — `MavenInvoker`

Low-level Maven execution with two modes plus security parsing:

- `executeCommandUsingMavenInvoker()` — out-of-process via the Maven Shared Invoker API.
- `executeUsingMavenEmbedder()` — in-process via the Maven Embedder API (used for version queries).
- `getCommands()` — command parsing that enforces the allowlist and rejects shell metacharacters.
  Maven `-D` system properties are passed through verbatim (no key deny-list).

## REST controllers (Streamable HTTP mode)

Only active when the `http` profile is enabled:

- **`ServerCardController`** — `GET /.well-known/mcp-server` (server metadata for discoverability),
  `GET /health`, `GET /health/ready`, `GET /health/live`.
- **`BuildEventController`** — `GET /mcp/build-events/stream` (Server-Sent Events for build events).

`TransportConfig` configures CORS for the `/mcp/**` paths.

## Request flow

```text
tools/call ─▶ Spring AI MCP SDK ─▶ @Tool method on a service bean
                                      │
                                      ├─ validate inputs (length, characters, path canonicalisation)
                                      ├─ resolve build tool (explicit or auto-detect)
                                      ├─ apply allowlist + flag checks
                                      ▼
                              spawn isolated build process
                                      │
                                      ├─ (analyze_build_output) parse output → JSON
                                      ▼
                              return result to the client
```

## Technology stack

| Component | Version | Purpose |
|-----------|---------|---------|
| Java | 21+ | Runtime |
| Spring Boot | 3.5.14 | Application framework |
| Spring AI | 2.0.0-RC2 | MCP server framework |
| Maven Embedder | 3.9.9 | In-process Maven (version queries) |
| Maven Shared Invoker | 3.3.0 | Out-of-process Maven (builds) |

## Extending the server

**Add a build tool:** implement `BuildTool`, register it in `BuildToolProvider`, add a
`BuildOutputParser`, register the parser in `BuildToolsService`, add detection hints in
`detect_build_tool`, add dependency syntax in `DependencyService`, and add tests.

**Add an MCP tool:** create a `@Service` with `@Tool`-annotated methods, define parameters with
`@ToolParam`, **register the bean in `BuildToolsApplication.buildTools(...)`** (this last step is
what actually exposes the tool), and add tests.
