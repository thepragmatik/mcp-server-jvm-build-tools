# Changelog

## [Unreleased]

### Changed

- **#199 — `http` profile binds loopback by default**: `application-http.properties` now sets `server.address=127.0.0.1`, so the Streamable HTTP transport is no longer reachable from other machines out of the box (previously it bound `0.0.0.0:8080` with tool authorization disabled). Override with `--server.address=0.0.0.0` for deliberate network deployments and enable `buildtools.auth.enabled=true` / the OAuth resource-server. A new `HttpBindAdvisory` logs a prominent startup warning when tool authorization is disabled while the servlet transport binds a non-loopback address (silent in stdio mode and on loopback binds; never fires when auth is enabled).

## [1.3.1] - 2026-09-11

### Fixed

- **#187 — `http` profile started no web server**: `application.properties` hard-coded `spring.main.web-application-type=none`, so the documented `--spring.profiles.active=http` (and `scripts/launcher.sh --http`) started the Spring context with no TCP listener. A new `application-http.properties` re-enables the servlet web server (`spring.main.web-application-type=servlet` + `spring.ai.mcp.server.http=true`); `server.port` remains user-configurable (`-Dserver.port`, launcher `--port`). Regression test `HttpProfileWebServerTest`: http profile binds a real TCP listener and serves `GET /health` 200; default profile stays stdio-only with no web server. (#190)
- **#188 — `profile_build` silent failure + poisoned history entries**: `profile_build` now resolves a Maven home up front (new `MavenHomeResolver` reads `MAVEN_HOME` / `maven.home` / `mvn` on PATH) and returns the canonical `Maven requires buildToolHome` error instead of recording a fake 0.0s failed build; validation failures write nothing to `.buildtools/history/`, so the history is no longer poisoned. Docs clarify MAVEN_HOME detection. (#192)
- **#189 — Tool catalogue grouping collapsed to a single `ungrouped` bucket at runtime**: `ToolMetricsAspect` CGLIB-proxies every tool service bean, and `ToolCatalogueSummary` scanned the proxy class (no `@Tool` annotations, mangled class name), so `full` mode advertised `{"ungrouped": [all tools]}` instead of real per-service groups. `serviceGroups` now resolves groups through `AopUtils.getTargetClass`, restoring the deterministic per-service grouping advertised in v1.3.0. `none`/`count` semantics and the legacy payload shape are unchanged. The ordered tool-object list now has a single source of truth shared by tool registration and summary wiring (`BuildToolsApplication#toolObjects`). (#191)

## [1.3.0] - 2026-09-11

### New Feature Packages

- **server/discover RPC Routing** (#176): `server/discover` (SEP-2575) is now answered as a JSON-RPC method on the MCP protocol endpoint (`POST /mcp`), alongside the existing `/mcp/discover` probe and stdio delivery — protocol-speaking clients no longer need the standalone route.
- **Deterministic Tool Catalogue Summary + Grouping** (#177): additive `tools` summary on every discover surface, driven by `ToolCatalogueSummary` and the `buildtools.discover.tools-summary` knob (`none | count | full`, default `full`), with deterministic grouping of the tool catalogue.
- **stdio Backward-Compat Probe** (#178): `server/discover` remains answered over stdio (`StdioDiscoverSession`) for clients that predate the HTTP routes, so legacy stdio integrations keep working.

### Security Fixes

- **#161 — OAuth token endpoint honors `enabled=false` and defaults to off**: all OAuth beans (`OAuthTokenConfig`, `JwtTokenService`, `OAuthClientRegistrationRepository`, `OAuthTokenController`) are now guarded by `@ConditionalOnProperty(name = "buildtools.oauth.token-endpoint.enabled", havingValue = "true", matchIfMissing = false)`. The endpoint is disabled unless explicitly enabled in configuration.

### Testing and Quality

- **Cross-Surface Consistency Suite** (#179): `DiscoverCrossSurfaceConsistencyTest` enforces that the `server/discover` payload is deep-equal across `POST /mcp`, `GET /mcp/discover`, and stdio, and that the well-known card shares its fields with discover from the single `McpServerIdentity` source.
- **#159 — Constant-time secret comparison**: `OAuthTokenController` now uses `MessageDigest.isEqual` for client_secret verification, eliminating the timing side-channel (CWE-208).
- **#160 — Duplicate YAML key fix**: `GitHubActionsGenerator` emits a single `run:` key under `defaults:` (shell/working-directory written inside the one block), fixing invalid generated workflow YAML.

### Documentation

- **docs/TOOLS.md**: documented all three `server/discover` delivery surfaces (JSON-RPC on `POST /mcp`, plain-JSON `/mcp/discover` probe, stdio probe), the tools-summary knob, and the card/discover consistency contract.
- **docs/ARCHITECTURE.md**: documented `McpServerDiscoverJsonRpcController` and the shared single-source discover result construction.


## [1.2.0] - 2026-07-26

### New Feature Packages

- **CI/CD Flow Interpreter** (#143, #158): GitHub Actions pipeline ingestion, analysis, and generation — `PipelineShapeDetector` auto-detects the CI/CD shape, `GitHubActionsGenerator` produces valid YAML workflows from an abstract pipeline model.
- **Build Plan Authoring** (#144, #158): `PlanExecutionEngine` for sequential multi-step build plans with cancellation, retry, and dependency ordering. `PlanStepGenerator` converts build tool commands into structured plans.
- **OAuth 2.1 Client Credentials** (#145, #158): Bearer token endpoint (`POST /oauth/token`) with HMAC-signed JWT tokens, client registration via config, and resource-server-style authorization for MCP transports.
- **Micrometer / Prometheus Observability** (#146, #158): 3 `MeterBinder` implementations (`SecurityMetricsCollector`, `CacheMetricsCollector`, `BuildMetricsCollector`) exposing auth metrics, cache performance, and build counters via `/actuator/prometheus`.

### Testing and Quality

- **Test Suite Growth**: Total suite now at **713 tests** (from 599 in v1.1.0) — covering 4 new packages across JDK 21/23/25 matrix CI.
- **Cancellation Test Robustness**: `PlanExecutionEngineTest.testPlanCancellation` now uses polling-based coordination instead of fragile `Thread.sleep()`, eliminating timing races on CI.
- **Maven Invoker PATH Fallback**: `MavenInvoker` gracefully falls back to system `mvn` from PATH when the configured `buildToolHome` directory doesn't exist, fixing CI test execution across environments.

### Build and Infrastructure

- **Docker Toolchain**: Gradle updated to 9.6.1, SBT to 2.0.3.
- **Merge Workflow**: PR #158 merged and deployed with full autonomous swarm workflow (research → architecture → engineering → QA → VRFY).

### Documentation

- **Design Specs**: Four specification documents in `docs/specs/` covering CI/CD Flow Interpreter, Build Plan Authoring, OAuth 2.1 Client Credentials, and Observability.
- **AGENTS.md**: Updated for autonomous swarm workflow with GitHub transparency requirements.

### Known Issues (QA Findings)

- #159 — Timing side-channel in OAuth client_secret comparison
- #160 — Duplicate YAML `run:` key in CI/CD generator defaults
- #161 — OAuth token endpoint enabled by default when config guard is ineffective

## [1.1.0] - 2026-07-23

### New Build Tool Features

- **POM Analysis, CVE Scanning, Android Support** (#148): 30 MCP tools for JVM builds — server now supports POM dependency analysis, OWASP CVE vulnerability scanning, and Android/Gradle project detection and build execution.
- **Auto-Merge Pipeline** (#149, #150): Gate 4 auto-merge workflow integrated into AGENTS.md and README with step-by-step instructions for autonomous PR merge automation.

### Testing and Quality

- **Test Coverage** (#151): Added test coverage for 5 previously untested service classes — `PromptService`, `BuildResourceService`, profile build, and related components. Total test suite now at 599 tests.
- **@ToolParam Annotation Alignment** (#154): Aligned `@ToolParam` annotations with documentation across 8 tools — parameter names now consistently match the published tool API reference.

### Build and Infrastructure

- **HTTP Transport Profile Fix** (#152): Added `application-http.properties` to correctly enable the HTTP transport profile, resolving a configuration gap that prevented MCP HTTP transport auto-configuration.
- **Docker Build Tool Installation** (#153): Docker image now includes Gradle 9.6.1 and SBT 2.0.3 alongside Maven 3.9.11, completing the full JVM build toolchain.
- **MCP Transport Auto-Configuration** (#155, #156): Manual `McpServerTransportConfiguration` bean that registers `StdioServerTransportProvider` and `McpSyncServer`, fixes Docker JDK version conflict, and includes a comprehensive test harness — all 17 MCP protocol test scenarios pass.

### Documentation

- **Test Documentation** (#151, #155, #156): Added `docs/reference/test-results.md`, `docs/reference/test-plan.md`, and `docs/reference/test-tools-recommendations.md` documenting the full Docker-based test suite with MCP protocol validation scenarios.

## [1.0.0] - 2026-06-24

### MCP-RC Alignment (2026-07-28 Spec)

This release adopts the MCP 2026-07-28 Release Candidate specification across all transport, security, and tool layers — fully backward-compatible with existing MCP clients (additive/negotiated only).

- **Streamable HTTP Transport** (#85): stateless HTTP transport with `Mcp-Method`/`Mcp-Name` header validation, `server/discover` endpoint.
- **JSON Schema 2020-12 Validation** (#86): all tool `inputSchema`s validated as full JSON Schema 2020-12 documents.
- **Deterministic Tool Order + ttlMs/cacheScope** (#87): tools list is deterministically ordered; list/read results carry expiry hints.
- **W3C Trace Context Propagation** (#88): `traceparent`/`tracestate`/`baggage` propagated through build subprocesses when a request carries an active trace context (no regression for untraced builds).
- **OAuth 2.1 Resource-Server** (#89): bearer-token authorization aligned with the MCP OAuth 2.1 resource-server model; protected resource metadata endpoint.

### New Build Tool Features

- **Maven Wrapper** (#81): project ships `mvnw`; CI and docs switched to use it. No host Maven installation needed.
- **OWASP Dependency-Check** (#78): automated dependency vulnerability scan in CI (weekly schedule + per-PR for dependency-changing paths). Docs at `docs/DEPENDENCY_MANAGEMENT.md`.
- **License Header Enforcement** (#80): CI now fails on missing license headers (removed `continue-on-error`).

### Security and Hardening

- **HTTPS Transport Defaults** (#83): CORS restricted to local origins; health details gated behind authorization; sensible defaults out of the box.
- **Static Analysis** (#82): SpotBugs + Spotless integrated into `verify` lifecycle.
- **Maven -D Allowlist Removed** (#97): the server now trusts the client's `-D` choices (resolved the tension between usability and security).
- **Dependency Updates**: 14 automated Dependabot PRs merged, bumping Spring Boot 3.5 to 4.1, Spring AI RC2 to GA, Gradle 8.12, sbt 1.10.10, and all CI actions to their latest versions.

### Documentation and Site

- **Professional Documentation Website** (#95/#110/#111): full MkDocs Material site with User Guide + Technical Reference, deployed to GitHub Pages.
- **MCP Client Integration Guide** (`MCP_INTEGRATION.md`): covers 10+ clients (Claude Desktop, Cursor, Cline, Windsurf, Goose, Continue, GitHub Copilot, LangChain, LlamaIndex).
- **Agent Contributor Guide** (`AGENTS.md`): self-enforcing PR process for autonomous agent workflows.

### Evidence

The server exposes **28 MCP tools** covering Maven, Gradle, and sbt build toolkits. Complete protocol-level evidence at `docs/EVIDENCE.md`.

[1.2.0]: https://github.com/thepragmatik/mcp-server-jvm-build-tools/releases/tag/v1.2.0

[#159]: https://github.com/thepragmatik/mcp-server-jvm-build-tools/issues/159
[#160]: https://github.com/thepragmatik/mcp-server-jvm-build-tools/issues/160
[#161]: https://github.com/thepragmatik/mcp-server-jvm-build-tools/issues/161

[1.1.0]: https://github.com/thepragmatik/mcp-server-jvm-build-tools/releases/tag/v1.1.0

[1.0.0]: https://github.com/thepragmatik/mcp-server-jvm-build-tools/releases/tag/v1.0.0
