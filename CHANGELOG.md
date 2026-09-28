# Changelog

## [2.0.0-rc.2] - 2026-09-29

- Opt-in Maven version security checks now expose only bounded aggregate OSV status, CVE count, and known/unknown highest severity through MCP; advisory and package identities stay local.
- OSV responses with unexpected fields now fail incomplete instead of appearing clean, and token-shaped version strings are withheld from model-visible results.
- Reuse default Maven Central and OSV HTTP clients across service instances to avoid selector-thread growth under repeated construction; the Docker process limit remains a resource gate.
- CVE scan extraction now selects only project-level POM dependencies using a secure XML parser and ignores commented or quoted Gradle code. Inherited POM versions and dynamic supported Gradle calls fail incomplete before OSV egress instead of producing a misleading clean count.
- The central project guard now checks build markers through one held, no-symlink project directory handle where Java `SecureDirectoryStream` is available. Providers without it keep canonical-path compatibility checks; those checks still have a path-swap race, so untrusted projects require isolation.
- OSV batch scans now bound requests and response bodies, remove sequential retry storms, and fail incomplete without counts when a dependency was not checked. Sparse OSV matches retain an affected-dependency count and explicit unknown-severity status instead of passing the default HIGH filter as clean. Scan file reads reject nonregular markers and use a bounded worker/deadline; a final-file swap race still requires isolation for hostile projects. The scan remains limited to recognized direct declarations. Single-coordinate OSV responses now score validated CVSS v3.1 base vectors using FIRST equations and rounding; unsupported versions, malformed vectors, and non-base vectors retain unknown severity. Sparse batch results still cannot support severity-filtered counts.
- Maven Central version lookup now validates bounded group/artifact coordinates before egress, refuses redirects, caps metadata responses at 1 MiB with a request deadline, parses strict UTF-8 and DTD-free XML, and returns fixed errors without caller or server text.
- Gradle and sbt test-summary parsers now saturate oversized or inconsistent counts at 1,000,000 and mark `countsCapped`, matching Maven's bounded result contract without overflow or negative passed counts.
- CVE scans now read POM and Gradle build files through a held, race-safe directory handle, reject unsafe paths, oversized files, and invalid UTF-8 before OSV egress, and serialize only bounded Maven coordinates in OSV requests. Model-visible scan failures use fixed messages.
- Maven POM configuration validation now uses a 1 MiB bounded, offline XML parser that rejects malformed XML, DTDs, and external entities, checks direct elements accurately, and emits finite privacy-safe diagnostics through MCP. Gradle validation reads are also bounded to 1 MiB. Both validators use race-resistant, no-symlink directory-handle traversal and fail closed if the local filesystem/JDK lacks that support. The Docker base switches to Noble Java 21 with a build-time support probe. Gradle issues use the same safe projection.
- Removed the `buildtools.cache.hit.rate` gauge, which always reported an unmeasured `0.0`. Remove `buildtools_cache_hit_rate` dashboard queries and alerts; `buildtools.cache.score` remains a cache health score (0-100), not a comparable per-tool hit rate. sbt scoring can include locally parsed cache hits and misses.
- Built-in Maven, Gradle, and sbt MCP execution now reports completed-process `exitCode` and derives success from it, even when output markers disagree. Legacy custom plugins report unknown status; Gradle/sbt MCP analysis now retains bounded stdout diagnostics from failed builds.
- Maven build analysis now preserves a bounded set of compiler diagnostics from the middle of large process output, while keeping raw logs local. The structured result marks retained-output and diagnostic truncation explicitly.
- Maven, Gradle, and sbt now surface a bounded, generic `test` diagnostic when failed-test totals are available but no useful test detail survives output capture. The diagnostic never includes test names, paths, assertions, or log text.
- Three static native MCP build-workflow prompts are available over stdio and HTTP; HTTP retrieval requires `prompt:read`. The legacy `prompt_*` tools remain.

- Failed test assertions with `expected` values and source locations remain test diagnostics rather than compiler errors.
- `analyze_build_output` now provides schema-checked MCP `structuredContent` alongside the existing redacted JSON text result for older clients.
- `execute_build_command` now provides the same schema-checked structured result alongside its unchanged redacted JSON text, without a second build execution.
- `list_build_tools` uses a typed public listing projection and reuses the bounded JSON parse; its MCP result shape and fixed tool order remain unchanged.

## [2.0.0-rc.1] - Release candidate

This breaking prerelease is tagged only after review, conformance, dependency, and performance gates pass.

### Changed

- Project access now requires configured roots. Relative project aliases resolve beneath the first root; traversal and symlink escapes are rejected.
- HTTP binds loopback and enables bearer enforcement by default. The built-in development key is gone, API keys have no implicit scopes, and every HTTP tool call checks its assigned scope.
- The model-visible catalog has 24 explicitly scoped tools. Credential inspection, token validation, audit reading, stored-plan execution, and raw resource-reading tools are withheld while their privacy and ownership contracts are redesigned.
- Tool results expose bounded, redacted diagnostics and selected safe fields. Raw build output and exception details no longer cross the MCP boundary.
- Unknown and hybrid project auto-detection now asks for an explicit build tool instead of silently selecting Maven.
- Discovery advertises the Java SDK's documented MCP 2025-11-25 revision, not the custom 2026 draft feature set.

### Fixed

- The packaged jar now includes the HTTP and metrics profiles and Logback configuration.
- Invalid tool schemas abort startup and tool inputs are validated.
- Docker runs as a nonroot user, verifies Gradle and sbt downloads, and reuses a BuildKit Maven cache.

See [the 2.0 quickstart](docs/user-guide/quickstart-v2.md) and [design review](docs/reference/design-v2.md) for migration details and remaining risks.

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
