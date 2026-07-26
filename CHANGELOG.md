# Changelog

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

[1.1.0]: https://github.com/thepragmatik/mcp-server-jvm-build-tools/releases/tag/v1.1.0

[1.0.0]: https://github.com/thepragmatik/mcp-server-jvm-build-tools/releases/tag/v1.0.0
