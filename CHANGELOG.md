# Changelog

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

[1.0.0]: https://github.com/thepragmatik/mcp-server-jvm-build-tools/releases/tag/v1.0.0
