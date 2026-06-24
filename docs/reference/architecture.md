# Architecture

This server gives AI agents a single, unified interface to JVM build tools — Maven, Gradle, and
SBT — over the Model Context Protocol. It is built with **Spring Boot 4.1.0** and **Spring AI
2.0.0**, which auto-configures the MCP server and discovers tools from annotations.

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
│   Servlet filter chain (Streamable HTTP profile only, runs first):          │
│     (1) OAuthResourceServerFilter   (2) McpHeaderValidationFilter           │
│                                                                             │
│   REST controllers (Streamable HTTP profile only):                          │
│     ServerCardController        (.well-known/mcp-server + health)           │
│     McpDiscoverController       (server/discover: GET probe + POST)         │
│     OAuthProtectedResourceMetadataController (RFC9728 metadata)             │
│     BuildEventController        (supplementary SSE telemetry feed)          │
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
    - Version queries use the **Maven Embedder** (3.9.16), in-process — no external process.
    - Requires a `buildToolHome` pointing at a Maven installation.
    - Detects projects via `pom.xml`.
    - Commands: `clean, compile, test, package, install, deploy, validate`.
    - Safe flags: `-D` (any key), `-f`, `-P`, `-q`, `-X`, `-T`, `-B`, `-U`,
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
startup and generates the JSON schemas exposed via `tools/list`. The generated `inputSchema`
for every tool is **JSON Schema 2020-12** (stamped with the 2020-12 `$schema`); a build-time
guard, `ToolJsonSchemaComplianceTest`, validates every tool schema against the official 2020-12
meta-schema (SEP-2106 — see the [Tools reference](tools.md#json-schema-2020-12-and-deterministic-ordering)).

!!! note "Deterministic `tools/list` ordering (SEP-2549)"
    The provider bean is wrapped by `DeterministicToolCallbackProvider`, which returns the
    catalogue **sorted by tool name** on every call. `MethodToolCallbackProvider` discovers
    `@Tool` methods reflectively, and `Class.getDeclaredMethods()` has no ordering guarantee
    across JVMs/restarts; sorting at the provider boundary makes `tools/list` order a stable
    function of the (unique) tool names, improving client-side and LLM prompt-cache hit rates.
    No tool is added, removed, renamed, or changed — only the iteration order is normalised.

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

## Streamable HTTP transport components

The Streamable HTTP transport is **stateless** (MCP 2026-07-28 RC): no protocol-level sessions,
no `Mcp-Session-Id` header, and no SSE-stream resumability, so any replica can serve any request
and cross-call state is carried by explicit server-minted handles (e.g. an async build `taskId`)
passed as ordinary tool arguments. The following components are active only when the `http`
profile is enabled.

### Servlet filters (run before the controllers)

A short filter chain guards `/mcp/**`, ordered by `@Order`:

- **`OAuthResourceServerFilter`** (`@Order(1)`) — the OAuth 2.1 resource-server gate. **Inert by
  default**; when `buildtools.oauth.resource-server.enabled=true` it requires an
  `Authorization: Bearer <token>` on `/mcp/**`, validating the opaque token locally via
  `ToolAuthorizationService`. A missing/invalid token yields `401` with an RFC6750
  `WWW-Authenticate: Bearer … resource_metadata="…"` challenge. The `server/discover` probe is
  exempt so clients can always learn how to authenticate.
- **`McpHeaderValidationFilter`** (`@Order(2)`) — validates the 2026-07-28 RC request headers
  `Mcp-Method` / `Mcp-Name` (SEP-2243) against the JSON-RPC body on `POST /mcp/**`. It is purely
  additive: **absent** headers pass through (older clients are unaffected) and **matching**
  headers pass through; only a genuine self-contradiction is rejected with `400` + a JSON-RPC
  `HeaderMismatchError`.

### REST controllers

- **`ServerCardController`** — `GET /.well-known/mcp-server` (server metadata for discoverability),
  `GET /health`, `GET /health/ready`, `GET /health/live`.
- **`McpDiscoverController`** — `server/discover` at `GET /mcp/discover` (probe) and
  `POST /mcp/discover` (JSON-RPC), advertising server identity, supported protocol versions
  (`2024-11-05`, `2025-03-26`, `2026-07-28`), capabilities, stateless-transport characteristics,
  and the `cacheHints` block. The payload is identical on every call (no per-connection variance).
- **`OAuthProtectedResourceMetadataController`** — `GET /.well-known/oauth-protected-resource`
  (RFC9728 Protected Resource Metadata). **Always served** under the HTTP profile (additive
  discovery surface) regardless of whether bearer enforcement is enabled.
- **`BuildEventController`** — `GET /mcp/build-events/stream` (a supplementary, **non-protocol**
  Server-Sent Events telemetry feed for dashboards; it carries no MCP JSON-RPC traffic and has no
  resumability).

`McpServerIdentity` is the single source of truth that every discovery surface reads — the server
card, `server/discover`, and the `Mcp-Name` header check — so server name, protocol versions,
capabilities, and `cacheHints` cannot drift between them. `TransportConfig` configures CORS for the
`/mcp/**` paths (advertising the standard `Mcp-Method` / `Mcp-Name` request headers and no longer
the removed `Mcp-Session-Id`).

## W3C Trace Context propagation

For distributed tracing (W3C Trace Context / OpenTelemetry, SEP-414), a dependency-free trace
layer spans build execution:

| Component | Role |
|-----------|------|
| `W3CTraceContext` | Parse/format `traceparent` / `tracestate` / `baggage` values. |
| `McpTraceContext` | Read the exact SEP-414 `_meta` keys and continue an inbound trace. |
| `BuildTracer` | Open a `TraceSpan` around every build in `BuildToolsService` / `AsyncBuildService`. |
| `TraceSpan` / `TraceScope` | Span lifecycle and scoped activation. |
| `TraceContextHolder` | Thread-scoped active context; stamps `TRACEPARENT` / `TRACESTATE` / `BAGGAGE` onto each build subprocess's environment. |

Span parentage is resolved in order: an active (nested) span → the request `_meta` context → a
`TRACEPARENT` already present in the server's own environment (e.g. a CI runner) → otherwise a
fresh, sampled root span. An inbound trace is *continued* only when a request (or the server's
environment) carries one; an untraced build simply gets a fresh root span and is **never
reparented** onto an unrelated trace — so a host/CI trace is preserved and there is **no regression
for untraced builds**. See the [Security reference](security.md#w3c-trace-context-propagation).

## Request flow

```text
tools/call ─▶ Spring AI MCP SDK ─▶ @Tool method on a service bean
                                      │
                                      ├─ validate inputs (length, characters, path canonicalisation)
                                      ├─ resolve build tool (explicit or auto-detect)
                                      ├─ apply allowlist + flag checks
                                      ├─ open trace span (BuildTracer; continues inbound context)
                                      ▼
                              spawn isolated build process
                                      │   (active span's TRACEPARENT/TRACESTATE/BAGGAGE
                                      │    stamped on the subprocess env)
                                      ├─ (analyze_build_output) parse output → JSON
                                      ▼
                              return result to the client
```

## Technology stack

| Component | Version | Purpose |
|-----------|---------|---------|
| Java | 21+ | Runtime |
| Spring Boot | 4.1.0 | Application framework |
| Spring AI | 2.0.0 | MCP server framework |
| Maven Embedder | 3.9.16 | In-process Maven (version queries) |
| Maven Shared Invoker | 3.3.0 | Out-of-process Maven (builds) |

## Extending the server

**Add a build tool:** implement `BuildTool`, register it in `BuildToolProvider`, add a
`BuildOutputParser`, register the parser in `BuildToolsService`, add detection hints in
`detect_build_tool`, add dependency syntax in `DependencyService`, and add tests.

**Add an MCP tool:** create a `@Service` with `@Tool`-annotated methods, define parameters with
`@ToolParam`, **register the bean in `BuildToolsApplication.buildTools(...)`** (this last step is
what actually exposes the tool), and add tests.
