# Release Notes — v0.2.0

**Release Date:** June 12, 2026
**Previous Release:** [v0.1.0](https://github.com/thepragmatik/mcp-server-jvm-build-tools/releases/tag/v0.1.0)

---

## Overview

v0.2.0 is a major expansion of the MCP Server for JVM Build Tools. This release grows from 7 tools to 28 MCP tools across 12 services, adds SBT support alongside Maven and Gradle, introduces HTTP transport with SSE, delivers build performance profiling and dependency conflict detection, adds build cache health analysis, test flakiness detection, SBOM generation and supply chain auditing, tool authorization, and MCP tasks for async build execution, and upgrades the foundation to Spring Boot 3.5.14, Spring AI 2.0.0-RC2, and Java 21.

**43 commits** since v0.1.0. **375 tests** across **22 test classes**.

---

## Foundation Upgrade

- **Spring Boot** upgraded from 3.4.x to 3.5.14
- **Spring AI** upgraded from 1.0.0-M6 to 2.0.0-RC2
- **Java baseline** upgraded from 17 to 21
- **Maven Embedder** 3.9.9, **Maven Shared Invoker** 3.3.0
- PRs: [#34](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/34), [#28](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/28)

---

## New Features

### SBT Build Tool Support ([#44](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/44))
- **detect_sbt_modules** — multi-module SBT project detection
- **detect_sbt_test_frameworks** — auto-detect ScalaTest, specs2, MUnit, uTest, ScalaCheck, JUnit, Weaver
- **analyze_sbt_build** — structural analysis of plugins, Scala version, resolvers, settings
- 11 SBT lifecycle commands via execute_build_command with security hardening

### Build Performance Profiling ([#53](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/53))
- **profile_build** — timing-instrumented build execution with phase/task breakdown
- **analyze_build_performance** — read-only configuration analysis with optimization suggestions
- Trend detection: SLOWER / FASTER / STABLE comparison against historical runs

### Dependency Conflict Detection ([#50](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/50))
- **detect_dependency_conflicts** — cross-build-tool dependency conflict analysis
- Detects duplicate version declarations (WARNING) and dependencyManagement mismatches (ERROR)
- Resolves Maven property references, generates structured resolution plans

### Java Version Compatibility Checker ([#56](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/56))
- **check_java_version** — inspect project Java source/target/release configuration
- Reports compatibility status, LTS alignment, and upgrade recommendations

### Credential Management ([#48](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/48))
- **check_credential_status** — read-only credential scan of Maven settings.xml, Gradle properties, env vars
- Masked credential values in output, BuildAuthService for repo auth management

### Dependency Intelligence ([#45](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/45), [#33](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/33))
- **check_dependency_version** — multi-build-tool extraction with Maven Central REST API
- MCP resource exposure for dependency data via list/read_dependency_resource

### Resource Templates ([#46](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/46))
- Parameterized URI templates for MCP resources, dynamic exposure based on project context

### Prompt Templates
- **prompt_build_and_test**, **prompt_dependency_audit**, **prompt_build_diagnosis**
- **list_build_resources / read_build_resource** — structured build resource access

### Streamable HTTP Transport ([#47](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/47))
- SSE streaming support, CORS configuration, TransportLoggingFilter
- TransportConfig for transport mode selection

### MCP Server Card and Discoverability ([#50](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/50))
- GET /.well-known/mcp-server — MCP-004 compliant server card
- GET /health, /health/ready, /health/live — container orchestration probes

### CLI Launcher and Registry
- scripts/launcher.sh — auto-discovers Java, Maven, Gradle, SBT with --http flag
- mcp-registry.json — ecosystem discoverability manifest

### Documentation Suite ([#38](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/38), [#51](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/51))
- ARCHITECTURE.md, CONTRIBUTING.md, WORKFLOW.md, SECURITY.md, TOOLS.md
- QUICKSTART.md, CONFIGURATION.md, FAQ.md, TROUBLESHOOTING.md
- MCP_INTEGRATION.md for 9+ MCP clients, docs/INDEX.md

### Tool Authorization ([#57](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/57))
- **check_tool_authorization** — verify tool access against role-based scopes
- **list_available_scopes** — enumerate all registered authorization scopes
- **audit_tool_access** — audit trail of tool invocations per scope
- Role-based scope enforcement with empty-scope-safe defaults

### MCP Tasks Extension ([#58](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/58))
- **execute_build_async** — non-blocking long-running build execution
- **get_build_task** — poll async task status and results
- **cancel_build_task** — cancel a running async build task
- **list_build_tasks** — list all active and recent build tasks
- Background task queue with progress tracking and configurable concurrency limits

### SBOM Generation & Supply Chain Audit ([#59](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/59))
- **generate_sbom** — CycloneDX-format SBOM from build dependencies
- **audit_supply_chain** — scan dependencies against known vulnerability databases
- **check_license_compliance** — audit dependency licenses against allow/block lists
- Reports with severity ratings, CVE references, and remediation suggestions

### Test Flakiness Detection ([#60](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/60))
- **detect_flaky_tests** — statistical flakiness detection from test history
- **analyze_test_history** — trend analysis of test outcomes over time
- Supports Maven Surefire and Gradle test report XML parsing

### Build Cache Health Analysis ([#61](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/61))
- **analyze_cache_health** — cache hit/miss ratio analysis with optimization guidance
- **optimize_build_cache** — read-only cache configuration recommendations
- Supports both Maven (local + remote cache) and Gradle (build cache, configuration cache)

### Docker Support
- Multi-stage Docker build for containerized deployment

---

## Fixes

- **BuildAuthService registration** ([#49](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/49)) — registered in ToolCallbackProvider
- **Compilation errors** resolved on staging branch
- **JSON Schema enum constraints** added via @Schema(allowableValues) for better MCP client UX

---

## Breaking Changes

- **Java 21 required** (was Java 17). Projects running the server must use JDK 21+.
- **Spring AI 2.0.0-RC2** API changes — migration from FunctionCallback to MethodToolCallbackProvider.

---

## Statistics

| Metric | v0.1.0 | v0.2.0 |
|--------|--------|--------|
| MCP Tools | 7 | 39 |
| Build Systems | Maven, Gradle | Maven, Gradle, SBT |
| Tests | baseline | 375 |
| Documentation Files | 2 | 15+ |
| Java Version | 17 | 21 |
| Spring Boot | 3.4.x | 3.5.14 |
| Spring AI | 1.0.0-M6 | 2.0.0-RC2 |

---

## All PRs in This Release

| PR | Title |
|----|-------|
| [#61](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/61) | feat: build cache health analysis and optimization |
| [#60](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/60) | feat: test flakiness detection and history analysis |
| [#59](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/59) | feat: SBOM generation and supply chain audit |
| [#58](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/58) | feat: MCP tasks extension for async build execution |
| [#57](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/57) | feat: tool authorization |
| [#55](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/55) | chore: release |
| [#56](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/56) | feat: Java version compatibility checker |
| [#54](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/54) | docs: build performance profiling |
| [#53](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/53) | feat: build performance profiling |
| [#51](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/51) | docs: June 2026 features |
| [#50](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/50) | feat: dependency conflicts, server card |
| [#49](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/49) | fix: BuildAuthService registration |
| [#48](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/48) | feat: credential management, JSON Schema |
| [#47](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/47) | feat: streamable HTTP transport |
| [#46](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/46) | feat: resource template service |
| [#45](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/45) | feat: multi-build-tool dependency extraction |
| [#44](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/44) | feat: SBT project analysis tools |
| [#38](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/38) | docs: comprehensive documentation update |
| [#35](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/35) | docs: stack versions |
| [#34](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/34) | feat: upgrade foundation |
| [#33](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/33) | feat: dependency intelligence |
| [#31](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/31) | test: BuildToolProvider unit tests |
| [#28](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/28) | feat: upgrade foundation |

---

## v1.3.0 — server/discover RPC Routing, Tool Catalogue Summary, Security Hardening

*2026-09-11*

### Overview

v1.3.0 aligns discovery with the MCP spec (SEP-2575) by answering `server/discover` as a JSON-RPC method on the MCP protocol endpoint (`POST /mcp`), alongside the existing `/mcp/discover` probe and a stdio backward-compat probe, and adds a deterministic tool catalogue summary to every discover surface. Ships two security fixes and a cross-surface consistency test suite.

### New Features

- **server/discover RPC Routing** (#176): `server/discover` (SEP-2575) is answered as a JSON-RPC method on `POST /mcp`, alongside the existing `/mcp/discover` probe and stdio delivery.
- **Deterministic Tool Catalogue Summary + Grouping** (#177): additive `tools` summary on every discover surface, driven by `ToolCatalogueSummary` and the `buildtools.discover.tools-summary` knob (`none | count | full`, default `full`).
- **stdio Backward-Compat Probe** (#178): `server/discover` remains answered over stdio (`StdioDiscoverSession`) for clients that predate the HTTP routes.

### Security Fixes

- **#161 — OAuth token endpoint honors `enabled=false` and defaults to off**: all OAuth beans guarded by `@ConditionalOnProperty(name = "buildtools.oauth.token-endpoint.enabled", havingValue = "true", matchIfMissing = false)`.
- **#159 — Constant-time secret comparison**: `MessageDigest.isEqual` in `OAuthTokenController` (CWE-208).
- **#160 — Duplicate YAML key fix**: `GitHubActionsGenerator` emits a single `run:` key under `defaults:`.

### Testing & Quality

- **Cross-Surface Consistency Suite** (#179): `DiscoverCrossSurfaceConsistencyTest` enforces that the discover payload is deep-equal across `POST /mcp`, `GET /mcp/discover`, and stdio, and that the well-known card shares fields with discover from a single `McpServerIdentity` source.
- **744 tests passing** (JDK 21); CI green on JDK 21/23/25.

### All PRs

| PR | Title |
|----|-------|
| [#185](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/185) | release: v1.3.0 — bump version, changelog, README What's New |
| [#184](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/184) | fix(#161): honor token-endpoint.enabled=false for all oauth beans |
| [#183](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/183) | [mcp-005] Cross-surface consistency tests + docs for server/discover (#179) |
| [#182](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/182) | [mcp-005] server/discover stdio backward-compatibility probe |
| [#181](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/181) | feat(#177): deterministic tool catalogue summary + grouping on server/discover |
| [#180](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/180) | [mcp-005] Route server/discover through the MCP JSON-RPC endpoint (POST /mcp) |

---

## v1.2.0 — CI/CD Flow Interpreter, Build Plan Authoring, OAuth 2.1, Observability

*2026-07-26*

### Overview

v1.2.0 adds four new feature packages: CI/CD Flow Interpreter & Generation, Build Plan Authoring, OAuth 2.1 Client Credentials Grant, and Micrometer/Prometheus Observability. Implemented via a fully autonomous swarm workflow (research → architecture → engineering → QA → VRFY).

### New Features

- **CI/CD Flow Interpreter** (#143, #158): GitHub Actions pipeline ingestion, analysis, and generation — `PipelineShapeDetector` auto-detects CI/CD shape, `GitHubActionsGenerator` produces valid YAML workflows.
- **Build Plan Authoring** (#144, #158): `PlanExecutionEngine` for sequential multi-step build plans with cancellation, retry, and dependency ordering.
- **OAuth 2.1 Client Credentials** (#145, #158): Bearer token endpoint (`POST /oauth/token`) with HMAC-signed JWT tokens and client registration via config.
- **Micrometer / Prometheus Observability** (#146, #158): 3 `MeterBinder` implementations exposing auth metrics, cache performance, and build counters.

### Testing & Quality

- **713 tests passing** across JDK 21/23/25 matrix (from 599 in v1.1.0)
- Cancellation test now polling-based (no timing races on CI)
- Maven Invoker PATH fallback for CI compatibility

### Breaking Changes

- **OAuth token endpoint disabled by default** — must set `buildtools.oauth.token-endpoint.enabled=true` explicitly

### Statistics

| Metric | v0.2.0 | v1.2.0 |
|--------|--------|--------|
| MCP Tools | 39 | 43 |
| Tests | 375 | 713 |
| Build Systems | Maven, Gradle, SBT | Maven, Gradle, SBT |
| Java Version | 21 | 21 / 23 / 25 |
| Spring Boot | 3.5.14 | 4.1 |
| Spring AI | 2.0.0-RC2 | 2.0.0-GA |

### All PRs

| PR | Title |
|----|-------|
| [#158](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/158) | feat: v1.2.0 — CI/CD Flow Interpreter, Build Plan, OAuth, Observability |
| [#157](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/157) | fix: remove duplicate YAML run: key (#160) |
| [#147](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/147) | ci(deps): bump actions/setup-python |
| [#146](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/146) | build(deps): bump build-quality-plugins |

---

## v1.1.0 — POM Analysis, CVE Scanning, Android Support

*2026-07-23*

### Overview

v1.1.0 introduces POM analysis, CVE scanning, Android/Gradle project support, Docker toolchain with Gradle 9.6.1 and SBT 2.0.3, and MCP transport auto-configuration. 599 tests across the JDK 21/23/25 matrix.

### New Features

- **POM Analysis & CVE Scanning**: 30 MCP tools for JVM builds — dependency graph analysis, OWASP CVE vulnerability scanning, and remediation suggestions.
- **Android/Gradle Project Support**: Full Android project detection and build execution via Gradle.
- **Docker Toolchain**: Gradle 9.6.1 and SBT 2.0.3 alongside Maven 3.9.11.
- **MCP Transport Auto-Configuration**: `StdioServerTransportProvider` and `McpSyncServer` with comprehensive test harness — all 17 MCP protocol scenarios pass.
- **Auto-Merge Pipeline** (#149, #150): Autonomous PR merge workflow integrated into AGENTS.md.

### Testing & Quality

- **599 tests passing** (from 375 in v0.2.0)
- Tool param alignment across 8 tools
- HTTP transport profile fix for MCP HTTP transport auto-configuration

---

## v1.0.0 — MCP-RC Alignment Release

*2026-06-24*

### Overview

Adopts the MCP 2026-07-28 Release Candidate specification across all transport, security, and tool layers — fully backward-compatible with existing MCP clients. 376 tests.

### Key Changes

- **MCP-RC Alignment**: Transport, tool schema, and security alignment with the 2026-07-28 RC
- **OAuth 2.1 Resource Server**: Bearer token authentication for MCP HTTP transport
- **JSON Schema 2020-12**: Updated tool parameter schemas with `@Schema(allowableValues)` enum constraints
- **28 MCP Tools** for build execution, analysis, dependency management, and diagnostics
- **Spring AI 2.0.0-GA**: Migration from RC2 to GA

---

**Upgrading:** JDK 21+ required. Docker image bundles JDK 21.
