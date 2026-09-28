# Architecture

This page describes the **2.0 release-candidate design**. The supported
Java SDK speaks MCP `2025-11-25` over stdio or stateless Streamable HTTP.
The public [runtime-derived catalog](tool-catalog.md) contains 24 tools.
The 1.x tool list is historical. This implementation currently targets the
published MCP `2025-11-25` protocol version.

## Trust boundaries

```mermaid
flowchart LR
    A["🟣 MCP client<br/>model-visible input"] --> T{"🔵 Transport"}
    T -->|stdio| S["🔵 SDK stdio session"]
    T -->|HTTP /mcp| H["🟠 Host + Origin check<br/>body cap + bearer/tool/prompt scope"]
    H --> Q["🔵 SDK servlet<br/>MCP 2025-11-25"]
    S --> C["🟢 Guarded tool callback"]
    Q --> C
    S --> N["🟢 Immutable native prompt catalog<br/>3 static workflows"]
    Q --> N
    N --> T
    C --> P{"🟠 Canonical project root?"}
    P -->|deny| X["🔴 Safe error"]
    P -->|allow| B["🟢 Build-tool service<br/>Maven · Gradle · sbt"]
    B --> O["🟡 Bounded process capture"]
    O --> R["🟡 Privacy projection<br/>counts + redacted diagnostics"]
    R --> C
    C --> T
    classDef client fill:#eee5ff,stroke:#7c3aed,color:#24114b
    classDef transport fill:#dbeafe,stroke:#2563eb,color:#102a56
    classDef decision fill:#ffedd5,stroke:#ea580c,color:#512600
    classDef work fill:#dcfce7,stroke:#16a34a,color:#073b1e
    classDef output fill:#fef9c3,stroke:#ca8a04,color:#443400
    classDef reject fill:#fee2e2,stroke:#dc2626,color:#520909
    class A client
    class T,S,Q transport
    class H,P decision
    class C,B,N work
    class O,R output
    class X reject
```

The purple side is outside the server's control: a client may send prompts
and arguments to a model provider before MCP sees them. Relative
`projectDir` aliases avoid placing absolute host paths in routine tool
calls. The orange checks guard the server boundary; the yellow stages
bound and redact results before they return to the model. A project root
restricts paths but does **not** sandbox build scripts. Run untrusted
workspaces with OS or container isolation, minimal mounts, and restricted
network access.

## Core components

| Layer | Active implementation | Responsibility |
|-------|-----------------------|----------------|
| Application wiring | `BuildToolsApplication` | Registers the annotated tool service beans once. |
| Native prompts | `NativePromptCatalog` | Supplies three immutable, server-authored prompt definitions and messages to both SDK transports; rejects arguments without echoing them. HTTP `prompts/list` and `prompts/get` require `prompt:read`. |
| Tool catalog | `MethodToolCallbackProvider` → `DeterministicToolCallbackProvider` → `GuardedToolCallbackProvider` | Discovers tools, sorts names, limits the public surface to known permissions, substitutes safe descriptions, validates path arguments, and applies output projection. |
| Project boundary | `ProjectAccessPolicy` | Resolves existing paths with `toRealPath()` against configured allowed roots; ambiguous or markerless build-tool detection requires an explicit choice. |
| Build-tool selection | `BuildToolProvider` and `BuildTool` implementations | Selects Maven, Gradle, or sbt and delegates execution or analysis. |
| Process lifetime | `SyncProcessRunner`, `BoundedProcessOutput`, and async build handling | Drains stdout and stderr concurrently, retains bounded head/tail bytes, applies timeouts, and terminates descendants on timeout or cancellation. |
| Result boundary | `ModelOutputPolicy` and `PrivacySafeMcpJsonMapper` | Selects safe fields and normalized diagnostics for tool results; replaces caller-derived JSON-RPC error detail with generic text on both transports. |
| HTTP transport | `HttpMcpServerConfiguration` and servlet filters | Exposes `/mcp` through the SDK's stateless servlet when the `http` profile is active. |
| Stdio transport | `McpServerTransportConfiguration` | Keeps stdout reserved for SDK JSON-RPC and serves the same guarded callbacks. |
| Cache observability | `BuildCacheService` and `CacheMetricsCollector` | Records the last per-tool cache configuration audit score and exposes it as `buildtools.cache.score` with `tool` and `category=overall` tags. No build cache hit rate is measured or exported. |

The native prompt catalog bypasses tool callbacks because its messages are static.
It reads no project state; the existing `prompt_*` tools still use the guarded
callback path. The callback provider is the public tool catalog's source of truth. It currently
serves 24 sorted tools; `ToolScopeCoverageTest` compares the committed
[tool catalog](tool-catalog.md) with those runtime callbacks and their
`ToolPermission` scopes. Adding a method annotation alone does not make a
tool safe or public.

## Request flow

```mermaid
sequenceDiagram
    autonumber
    participant Client as 🟣 Client
    participant HTTP as 🟠 HTTP guards
    participant SDK as 🔵 MCP SDK
    participant Callback as 🟢 Guarded callback
    participant Build as 🟢 Build service
    participant Local as 🟡 Local output
    Client->>HTTP: POST /mcp (HTTP profile)
    HTTP->>HTTP: Validate Host, Origin, size, bearer + tool or prompt scope
    HTTP->>SDK: Bounded request
    SDK->>Callback: tools/call
    Callback->>Callback: Resolve and check project path
    Callback->>Build: Validated arguments
    Build->>Local: Spawn Maven / Gradle / sbt
    Local-->>Build: Bounded stdout + stderr
    Build-->>Callback: Local result
    Callback->>Callback: Redact and project safe fields
    Callback-->>SDK: Counts + ≤12 diagnostics
    SDK-->>Client: MCP result
```

For stdio, the SDK session replaces the HTTP guard stage. The same
callback and output policy still run, but there is no HTTP bearer filter;
local process access and the allowed project roots are the primary
boundaries. A supplementary build-events SSE feed is not the MCP
Streamable HTTP transport. The experimental 2026 `server/discover`
handler is disabled in the supported profile; clients should negotiate
with `initialize` and enumerate tools with `tools/list`.

### HTTP guard order

`McpOriginHostFilter` runs first (`@Order(0)`) and rejects untrusted
Origin and loopback Host values. `OAuthResourceServerFilter` (`@Order(1)`)
requires a configured bearer key in the HTTP profile and checks the
requested tool's scope. `McpHeaderValidationFilter` (`@Order(2)`) applies
the 1 MiB request-body cap and validates optional MCP routing headers.
The SDK servlet then handles the JSON-RPC request. Invalid Origin and
Host requests receive 403 before reaching a tool. HTTP scope checks do
not depend on the older `buildtools.auth.enabled` switch.

## Privacy and performance budgets

| Boundary | Current limit | Effect |
|----------|---------------|--------|
| MCP HTTP POST body | 1 MiB by default | Oversized requests receive 413 before SDK dispatch. |
| Process stdout and stderr | 32 KiB head + 96 KiB tail **per stream** | Readers keep draining large or unterminated output without retaining whole logs. |
| Completed built-in execution status | One signed process exit integer | MCP `execute_build_command` success follows the exit status after parsing; a custom plugin without a typed result reports unknown status. |
| Maven analysis compiler candidates | At most 13 complete lines of 2 KiB per stream | Recognized middle-of-log compiler errors survive head/tail truncation; excessive or oversized candidates set `diagnosticsTruncated`. |
| Tool-result projection input | final 256,000 characters | The projector never parses an unbounded returned string. |
| Model-visible diagnostics | at most 12; messages at most 500 characters | Includes severity, category, and per-result references; raw logs, commands, file paths, and symbols remain local. |

These are retained-data limits, not a CPU, memory, disk, network, or
subprocess sandbox. Redaction is strongest for recognized diagnostics;
arbitrary text can contain private information that patterns cannot
prove absent. Keep raw logs local and use synthetic privacy canaries in
tests. The [release gates](release-gates.md) measure real workloads
instead of inferring latency improvements from source inspection.
Maven analysis retains the existing head/tail capture and timeout behavior;
its additional bounded private collector runs on the same process-drain path
without starting another build. `outputTruncated` distinguishes local capture
truncation from the model-visible `truncated` result-size flag. Gradle and sbt
still use their existing execution paths.

## Architecture review findings

| Finding | Evidence and consequence | Release treatment |
|---------|--------------------------|-------------------|
| A current tool lost useful projected output | Packaged dogfood found `list_build_tools` returning an empty list even though three build tools were registered. The callback's JSON-quoted string needed decoding before the fixed-name projection. | Fixed with policy and protocol regressions; final candidate dogfood must still pass. |
| Maven test totals were doubled | Surefire class summaries and the final aggregate were both added: 829 actual tests appeared as 1,658. Oversized synthetic counts also exposed overflow. | Fixed by selecting each execution's final aggregate, bounding counters, and marking approximations with `countsCapped`; final dogfood must still pass. |
| Ordinary execution scope allowed remote publish and a path selector | `build:execute` accepted Maven `deploy`, sbt `publish`, and Maven `-f` could select a POM outside the allowed root. | Direct remote-publish task names and Maven project/settings selectors are denied; packaged inside/outside project probes and both reviews passed. Configured plugins can still publish, so untrusted builds need credential-free OS isolation. |
| Cross-tool result projection is highly coupled | `ModelOutputPolicy` interprets tool-specific JSON and plain text centrally. Adding a tool requires synchronized changes to its projection, metadata, permissions, and docs; silent field loss is possible. | The first small slice moves `list_build_tools` into a typed `PublicBuildToolListing`, reusing the policy's bounded parse while preserving its public result. Other projections remain centralized; extract them only with characterization and protocol tests. |
| Construction and selection are coupled to concrete classes | `BuildToolsService` and `DependencyService` construct parser/resolver implementations, while `BuildToolProvider` constructs build-tool instances. This makes substitutions and focused tests harder. | Roadmap refactor: inject interfaces or factories and preserve behavior with contract tests. |
| Dormant services expand review work | Eleven methods in four component-scanned services were never registered as MCP callbacks; their misleading `@Tool` and `@ToolParam` annotations have been removed. Ten other annotated methods are registered as callbacks but filtered from the public catalog by the permission map. Plan services also duplicate command vocabulary. | Keep the public catalog default-deny. Design and test each future exposure deliberately; consolidate plan vocabulary separately. |
| Filesystem checks cannot prevent all races | A symlink or build file can change after canonical validation and before a child process opens it. Build scripts can execute arbitrary project code. | Document the limitation now; require external isolation for untrusted projects. |

These findings are based on the current code and synthetic dogfood.
They are not claims that a broader refactor has already shipped. The
[2.0 design review](design-v2.md) records the security decisions and
[roadmap](../ROADMAP.md) tracks release evidence and follow-up work.

## Extending the server

1. Add a focused `@Tool` method to the appropriate service and register
   that service in `BuildToolsApplication` if it is new.
2. Assign exactly one public scope in `ToolPermission`. Add a safe public
   description and an explicit output projection; unknown private fields
   must not pass through by default.
3. Test the input schema, project-root behavior, authorization, redaction,
   and bounded errors with synthetic data.
4. Regenerate the runtime [tool catalog](tool-catalog.md), run
   `./mvnw -B verify --no-transfer-progress`, strict docs, and the
   privacy scan. Follow the [agent PR workflow](../AGENTS.md) for two
   independent reviews.

The Java baseline is 21. The candidate uses Spring Boot 4.1.1,
Spring AI 2.0.1, and the MCP Java SDK 2.0.1. The Maven wrapper,
container verification, JDK matrix, and packaged protocol gates are
described in the [release guide](release-gates.md).
