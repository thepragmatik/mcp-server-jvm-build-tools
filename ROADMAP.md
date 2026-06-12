# ROADMAP — mcp-server-jvm-build-tools
## Research-Driven Enhancement Plan (June 2026)

Based on analysis of:
- Current 39-tool capabilities (README, TOOLS.md, ARCHITECTURE.md, CHANGELOG)
- MCP protocol evolution (2025-11-25 stable -> 2026-07-28 RC)
- JVM build tool landscape (Gradle 9.6, Maven 4.0.0-beta-5, SBT 2.0.0-RC16)

---

## Research Summary

### MCP Protocol — What's Coming
The 2026-07-28 spec is a MAJOR breaking change:
- **Stateless protocol**: No sessions. Every request carries version + capabilities in headers
- **server/discover RPC**: Required endpoint for version negotiation
- **Subscriptions**: POST-response streams for server->client change notifications
- **Tasks extension (official)**: Polling-based async ops replacing blocking calls
- **Multi Round-Trip Requests (MRTR)**: Replaces sampling/elicitation with inputRequest/inputResponse pattern
- **CacheableResult**: Freshness hints for client-side caching
- **OpenTelemetry tracing**: Standardized trace context
- **Deprecated**: Roots, Sampling, Logging, HTTP+SSE transport

### JVM Build Tools — What's New
- **Gradle 9.5-9.6**: Task provenance in errors, config cache hit-rate improvements,
  type-safe Kotlin Settings plugins, immutable domain collections, Develocity CLI
  integration, wrapper retries, improved CLI rendering
- **Maven 4.0.0-beta-5**: Java 17 minimum, Maven Resolver 2.0.2, project-specific
  settings.xml, BOM import exclusions, profile OS activation, mvnd improvements
- **SBT 2.0.0-RC16**: Execution log for cache debugging, BSP improvements, parallel
  dependency resolution, Scala 3.8.4, cross-build caching, FarmHash-based
  incremental compilation

### Ecosystem Trends
- Supply chain security (SBOMs, SLSA, artifact signing) is the #1 JVM infra priority
- Build caching is becoming first-class in all three build tools
- Async/non-blocking MCP tools needed for long-running operations
- Multi-tool server differentiation is eroding

---

## Prioritized Roadmap

### P0 — Adapt to MCP Spec 2026-07-28     [HIGH value / HIGH effort]

**What**: Upgrade from MCP 2024-11-05 to the stateless 2026-07-28 protocol.

**Why**: Existential. Without this, the server won't work with MCP clients
adopting the new spec. The 2026-07-28 RC is already published and SDKs are
adopting it. This is not optional.

**Scope**:
- Implement server/discover RPC (required)
- Remove session state; move to header-based version/capability negotiation
- Implement subscription support (subscriptions/listen) for build status changes
- Replace blocking tool calls with MRTR pattern where appropriate
- Add OpenTelemetry trace context (traceparent) propagation
- Add CacheableResult freshness hints on stable responses (version lists, tool lists)
- Update tool output ordering to be deterministic (for LLM prompt cache hit rates)
- Remove any dependency on deprecated Roots/Sampling features
- Bump Spring AI MCP SDK dependency when available

**Effort**: High (4-6 weeks). Protocol is fundamentally restructured.

---

### P1 — MCP Tasks Extension for Async Builds     [HIGH value / MEDIUM effort]

**What**: Support the new MCP tasks extension for long-running builds. Instead of
blocking the client for a 3-minute mvn clean install, return a task handle
immediately and let the client poll for progress and results.

**Why**: Builds are the #1 long-running operation an AI agent triggers. The current
blocking model forces agents to hang. The MCP tasks extension is purpose-built for
this. Transforms UX from "wait and hope" to "fire, track, and act on results."

**Scope**:
- New tool: execute_build_async(projectDir, command) -> returns task handle
- Task lifecycle: queued -> running -> completed/failed/cancelled
- Progress notifications via tasks/update (phase-by-phase for Maven, task-by-task for Gradle)
- Build output streaming per phase/task
- Task cancellation support (kill build process)
- Persist task results in .buildtools/tasks/ for cross-session retrieval
- Integrate with existing build history from profile_build

**Effort**: Medium (2-3 weeks). Leverages existing execute_build_command + profile_build.
New: task lifecycle management, polling endpoint, cancellation.

**Dependencies**: P0 (requires 2026-07-28 tasks extension support in MCP SDK)

---

### P2 — SBOM Generation & Supply Chain Audit     [HIGH value / MEDIUM effort]

**What**: Automate CycloneDX/SPDX SBOM generation across Maven, Gradle, and SBT.
Parse results into structured data, cross-reference with vulnerability databases.

**Why**: Supply chain security is THE dominant JVM infrastructure concern in
2025-2026. US Executive Order 14028, EU Cyber Resilience Act, and SLSA framework
drive SBOM adoption. Every enterprise JVM team needs this capability.

**Scope**:
- New tool: generate_sbom(projectDir, format="cyclonedx"|"spdx")
  - Maven: trigger cyclonedx-maven-plugin (or detect if configured)
  - Gradle: trigger org.cyclonedx.bom plugin
  - SBT: trigger sbt-cyclonedx plugin
- Parse SBOM JSON into structured component inventory
- New tool: audit_supply_chain(projectDir)
  - Cross-reference dependencies with OSV.dev / GitHub Advisory Database
  - Report known CVEs with severity, fix versions, remediation steps
  - Check artifact signing status (Maven Central pgp, Gradle signature verification)
- New tool: check_license_compliance(projectDir)
  - Extract all dependency licenses from SBOM
  - Flag copyleft/restricted licenses (GPL, AGPL vs Apache, MIT, BSD)
  - Generate compliance report by category

**Effort**: Medium (2-3 weeks). Plugin invocation uses existing infrastructure.
SBOM parsing is JSON. OSV/GitHub APIs are straightforward HTTP calls.

---

### P3 — Test Flakiness Detection & History     [HIGH value / MEDIUM effort]

**What**: Run tests N times across builds, detect non-deterministic results, and
build a flakiness score per test. Integrate with build history for trend analysis.

**Why**: Flaky tests erode trust in CI, slow delivery, and waste engineering time.
The server already runs builds and parses test output. Adding repeat execution +
cross-run comparison turns build data into actionable quality signal.

**Scope**:
- New tool: detect_flaky_tests(projectDir, iterations=5, testFilter="...")
  - Runs tests N times (default 5)
  - Tracks pass/fail per test method across runs
  - Computes flakiness score: non-deterministic failures / total runs
  - Flags: score > 0 = FLAKY, > 0.5 = VERY FLAKY
  - Generates fix suggestions: thread safety, timing, order-dependency
- New tool: analyze_test_history(projectDir)
  - Reads .buildtools/history/ for past build test results
  - Computes pass rate trends, identifies degrading tests
  - Suggests tests to quarantine based on historical flakiness
- Persist test results with timestamps and git commit SHA

**Effort**: Medium (2-3 weeks). Leverages analyze_build_output test parsing and
profile_build history. New: multi-run orchestration, cross-run diffing.

---

### P4 — Build Caching Health Analysis     [MEDIUM value / LOW-MEDIUM effort]

**What**: Analyze build caching effectiveness across Gradle (config cache, build
cache), SBT (execution log, incremental compilation), and Maven (mvnd, build cache
extensions). Detect cache misses and suggest configuration improvements.

**Why**: All three build tools invest heavily in caching in 2025-2026. Gradle 9.6
targets config cache hit-rate improvements. SBT 2.0 introduced execution logs for
cache debugging. Most teams don't know if their caching actually works.

**Scope**:
- New tool: analyze_cache_health(projectDir)
  - Gradle: Parse --info output for cache hit/miss stats, check caching settings
  - SBT: Parse execution log (target/global-logging/exec-*.log) for cacheHit entries
  - Maven: Check for mvnd usage, build cache extension configuration
- Report: cache hit rate %, top cache-miss sources, configuration gaps
- New tool: optimize_build_cache(projectDir)
  - Generate suggested configuration snippets for each build tool
  - Gradle: enable config cache, remote build cache, parallel
  - SBT: Coursier parallel download, execution log analysis
  - Maven: mvnd configuration, build cache extension setup
- Integrate with existing analyze_build_performance

**Effort**: Low-Medium (1-2 weeks). Mostly log parsing + config analysis.

---

## NOT Included (Intentionally)

| Idea | Why Rejected |
|------|-------------|
| Bazel/Buck support | Bazel has its own MCP server; effort high for niche audience |
| Gradle Develocity integration | Too vendor-specific; limited to Develocity-licensed teams |
| Incremental compilation analysis | Subsumed by P4 (cache health) which covers broader ground |
| PR build comparison | Better served by CI-integrated tools |
| Maven 4 lifecycle migration helper | Maven 4 is beta; premature |
| Multi-module health dashboard | Data aggregation, not an MCP tool |

---

## Effort Summary

| # | Item | Value | Effort | Est. Weeks | Depends On |
|---|------|-------|--------|------------|------------|
| P0 | MCP 2026-07-28 Protocol | HIGH | HIGH | 4-6 | — |
| P1 | Tasks Extension (Async Builds) | HIGH | MEDIUM | 2-3 | P0 |
| P2 | SBOM + Supply Chain Audit | HIGH | MEDIUM | 2-3 | — |
| P3 | Test Flakiness Detection | HIGH | MEDIUM | 2-3 | — |
| P4 | Build Caching Health Analysis | MEDIUM | LOW-MED | 1-2 | — |

**Total**: 11-17 weeks (sequential P0->P1, P2/P3/P4 parallelizable)

**Recommended order**: P0 first (blocking), then P2+P3+P4 in parallel, then P1.

---

## Success Metrics

- P0: Passes MCP spec compliance suite for 2026-07-28; works with Claude Desktop, Cursor
- P1: 30s+ builds return task handle in <500ms; progress streams in real-time
- P2: SBOM contains 100% of direct+transitive deps; CVE lookup covers OSV + GitHub Advisory
- P3: Flaky tests detected with <5% false positive rate across 5-run baseline
- P4: Cache hit-rate analysis matches Gradle build scan numbers within 5%
