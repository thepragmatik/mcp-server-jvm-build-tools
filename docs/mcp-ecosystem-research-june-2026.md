# MCP (Model Context Protocol) Ecosystem Research — June 2026

## 1. Transport Layer Evolution

### Current Spec Version: 2025-11-25 (latest stable)
- Previous: 2025-06-18, 2025-03-26, 2024-11-05 (original)
- Spec URL: https://modelcontextprotocol.io/specification/2025-11-25

### Two Official Transports

**stdio (STANDARD, always required for clients):**
- Client launches server as subprocess
- Newline-delimited JSON-RPC over stdin/stdout
- Servers MAY write logging to stderr (for all log types, not just errors)
- Simple, local-only. Dominant for desktop/IDE integrations.

**Streamable HTTP (REPLACES HTTP+SSE from 2024-11-05):**
- Single endpoint supporting both POST and GET
- POST sends JSON-RPC messages; server returns either application/json or text/event-stream (SSE)
- GET opens SSE stream for server-initiated messages (notifications, requests)
- Session management via MCP-Session-Id header
- Resumability: SSE event IDs + Last-Event-ID header for reconnection
- Servers can disconnect at will to support polling (SEP-1699)
- Backwards compatibility: servers can host both old HTTP+SSE endpoints and the new unified endpoint
- Origin header validation REQUIRED — servers must reject invalid Origins with HTTP 403 to prevent DNS rebinding attacks
- Protocol version header: MCP-Protocol-Version: 2025-11-25

**WebSocket: NOT an official transport.** The roadmap explicitly states "We will not be introducing additional official transports this cycle." However, community repos like Atmosphere (github.com/Atmosphere/atmosphere) provide WebSocket/SSE/gRPC transport layers for MCP.

### Transport Roadmap (from Roadmap, updated 2026-03-05):
- Next-gen transport: evolve Streamable HTTP to run statelessly across multiple server instances, behind load balancers
- Scalable session handling: session creation, resumption, migration across server restarts
- MCP Server Cards: .well-known URL for structured server metadata discovery (Server Card WG)
- Transports WG owns this work

### Session -> Stateless Transition (Major Shift):
- SEP-2575 "Make MCP Stateless" (Final, created 2025-06-18): removes initialization handshake, carries protocol version and capabilities per-request
- SEP-2567 "Sessionless MCP via Explicit State Handles" (Final, created 2026-03-11): removes Mcp-Session-Id header, replaces implicit session state with explicit server-minted state handles
- Rationale: sessions had no consistent meaning across clients (per-tool-call, per-launch, per-page-load), making it unreliable for server authors

---

## 2. Tool/Resource/Prompt Patterns

### Core Primitives:

**Tools (tools/list, tools/call):**
- Model-controlled invocation
- JSON Schema 2020-12 as default dialect for inputSchema (SEP-1613, SEP-2106)
- execution.taskSupport field: required, optional, or forbidden
- Icons supported as metadata (SEP-973)
- Tool name format guidance (SEP-986)
- Human-in-the-loop: SHOULD show UI indicators and confirmation prompts
- Input validation errors returned as Tool Execution Errors, not Protocol Errors (SEP-1303)

**Resources (resources/list, resources/read):**
- Application-driven (UI selection, search, auto-inclusion)
- URI-based identification (RFC 3986)
- Subscribe capability for change notifications
- listChanged notification support
- Icons metadata support
- Pagination support

**Prompts (prompts/list, prompts/get):**
- User-controlled (slash commands, UI selection)
- Argument-based templates
- Icons metadata support

### Emerging Patterns:

**Tasks (Experimental in 2025-11-25, now Extension via SEP-2663):**
- Durable state machines for long-running operations
- Requestor/receiver model (either client or server can create tasks)
- States: created, working, input_required, completed, failed, cancelled
- TTL and resource management
- SEP-2663 "Tasks Extension" (Final, created 2026-04-27): formalizes as extension with tasks/get, tasks/update, tasks/cancel

**Elicitation:**
- URL mode elicitation (SEP-1036): secure out-of-band interactions
- Enhanced enums: titled/untitled, single/multi-select (SEP-1330)
- Default values for all primitive types (SEP-1034)

**Sampling with Tools (SEP-1577):**
- LLM sampling requests can include tools and toolChoice parameters

**MCP Apps Extension (SEP-1865, Final):**
- Interactive HTML UIs rendered inside chat (iframes)
- Bidirectional data flow, sandboxed security
- Official repo: modelcontextprotocol/ext-apps (2,410 stars)

---

## 3. Build Tool MCP Servers

### Notable Build/Dev Tool Servers:

- getsentry/XcodeBuildMCP (5,891 stars): iOS/macOS build tools, CLI for agents
- opensumi/core (3,631 stars): AI Native IDE framework, MCP client support
- aws/agent-toolkit-for-aws (852 stars): Official AWS MCP servers, skills, plugins
- AnkleBreaker-Studio/unity-mcp-server (259 stars): 268 tools for Unity, scene management, builds, profiling
- salacoste/mcp-n8n-workflow-builder (226 stars): n8n workflow automation, 17 tools
- mcp-use/mcp-use-ts (175 stars): Framework with client SDK, server SDK, React hooks
- intuit/quickbooks-online-mcp-server (275 stars): QuickBooks data access
- taskade/mcp (148 stars): OpenAPI to MCP codegen
- mcpc-tech/mcpc (96 stars): Compose MCP servers by chaining existing tools

### Key Features in Build Servers:
- Task-based execution for long builds (MCP Tasks extension)
- Multi-instance support for parallel builds
- CLI + MCP dual-mode (e.g., XcodeBuildMCP)
- CI/CD integration via OAuth Client Credentials
- Increasing tool counts (Unity: 268 tools)

---

## 4. Key Players and Repositories

### Official MCP SDKs (modelcontextprotocol org):

| SDK | Stars | Tier | Maintainer |
|-----|-------|------|------------|
| Python | 23,294 | Tier 1 | Anthropic |
| TypeScript | 12,647 | Tier 1 | Anthropic |
| Go | 4,674 | Tier 1 | Google |
| C# | 4,325 | Tier 1 | Microsoft |
| Java | 3,468 | Tier 2 | Spring AI / Broadcom |
| Kotlin | TBD | TBD | JetBrains |
| Rust | TBD | Tier 2 | Community |
| Swift | TBD | Tier 3 | Community |
| Ruby | TBD | Tier 3 | Community |
| PHP | TBD | Tier 3 | Community |

SDK Tiering System (SEP-1730): Tier 1 = Full spec, dedicated maintainers, conformance tests. Tier 2 = Core features, community-maintained. Tier 3 = Early/partial.

### Key Independent Players:

- spring-projects/spring-ai (8,919 stars): Java AI framework; powers Java MCP SDK
- anthropics/claude-quickstarts (16,995 stars): Claude API + MCP examples
- hangwin/mcp-chrome (11,902 stars): Chrome browser MCP server
- microsoft/mcp (3,300 stars): Microsoft official MCP server catalog
- appcypher/awesome-mcp-servers (5,590 stars): Curated server list
- modelcontextprotocol/registry (6,916 stars): Official MCP server registry
- modelcontextprotocol/ext-apps (2,410 stars): MCP Apps protocol + SDK
- mobile-next/mobile-mcp (5,181 stars): iOS/Android mobile automation
- executeautomation/mcp-playwright (5,549 stars): Playwright browser automation

### Governance:
- Linux Foundation project (SEP-932)
- Working Groups: Transports, Agents, Governance, SDK, Registry, Server Card, Inspector V2, Skills Over MCP, Interceptors, File Uploads, Triggers & Events, Tool Annotations
- SEP process: Standards Track + Extensions Track
- Contributor Ladder (SEP-2148)

---

## 5. Security Patterns

### Authorization Framework (2025-11-25):
- OAuth 2.1 (draft-ietf-oauth-v2-1-13)
- OAuth 2.0 Protected Resource Metadata (RFC 9728) for discovery
- Authorization Server Metadata (RFC 8414) + OpenID Connect Discovery 1.0
- Multiple discovery methods tried in priority order
- Client ID Metadata Documents (draft-ietf-oauth-client-id-metadata-document-00) as recommended registration
- Dynamic Client Registration (RFC 7591) as fallback
- scope parameter in WWW-Authenticate header for incremental consent

### OAuth Client Credentials Extension (SEP-1046):
- Machine-to-machine auth (JWT Bearer Assertions RFC 7523 or client_secret)
- For CI/CD, background services, server-to-server

### Enterprise-Managed Authorization Extension:
- Centralized access control via identity providers (SEP-990)

### Key Attack Mitigations:
1. DNS Rebinding: Validate Origin header; reject with 403
2. Session Hijacking: Cryptographically secure session IDs
3. Confused Deputy: Per-client consent required for proxy servers
4. Local Server Binding: Bind to localhost (127.0.0.1) only
5. Authentication Required: For all Streamable HTTP connections

### Emerging Security Tools:
- hol-guard (354 stars): AI antivirus for developer agents
- mcp-governance-sdk: Enterprise governance (Identity, RBAC, Credentials, Auditing)
- mcp-guard: Comprehensive MCP security scanner
- SEP-1024: MCP Client Security Requirements for Local Server Installation

---

## 6. Recent Spec Changes and Notable SEPs

### 2025-11-25 Key Changes from 2025-06-18:
1. OIDC Discovery 1.0 support for auth server discovery (PR #797)
2. Icons as metadata for tools, resources, prompts (SEP-973)
3. Incremental scope consent via WWW-Authenticate (SEP-835)
4. Tool name format guidance (SEP-986)
5. Enhanced enums (SEP-1330)
6. URL mode elicitation (SEP-1036)
7. Tool calling in sampling (SEP-1577)
8. OAuth Client ID Metadata Documents (SEP-991)
9. Tasks experimental support (SEP-1686)
10. JSON Schema 2020-12 as default dialect (SEP-1613)
11. Decouple request payloads from RPC methods (SEP-1319)
12. Input validation as Tool Execution Errors (SEP-1303)
13. SSE polling via server-side disconnect (SEP-1699)
14. OAuth alignment with RFC 9728 (SEP-985)
15. SDK Tiering System (SEP-1730)
16. Formal governance with Working Groups (SEP-932, SEP-1302)

### Post-2025-11-25 Final SEPs:
- SEP-2133: Extensions framework (2025-01-21)
- SEP-1865: MCP Apps (2025-11-21)
- SEP-2567: Sessionless MCP (2026-03-11)
- SEP-2575: Make MCP Stateless (2025-06-18)
- SEP-2663: Tasks Extension (2026-04-27)
- SEP-2549: TTL for List Results
- SEP-2243: HTTP Header Standardization
- SEP-414: OpenTelemetry Trace Context

### Active Working Groups (Roadmap 2026-03-05):
1. Transport Evolution & Scalability
2. Agent Communication (task retry, expiry policies)
3. Governance Maturation (contributor ladder)
4. MCP Server Cards
5. Skills Over MCP
6. Interceptors
7. File Uploads
8. Triggers & Events

---

## Key Takeaways

1. Transport consolidation: Streamable HTTP is the one remote transport. No WebSocket in core spec. Trend toward stateless operation (SEP-2567/2575).

2. Extensions as growth mechanism: SEP-2133 formalized extensions; Tasks, OAuth Client Credentials, MCP Apps, Enterprise Auth all moved to extensions to keep core spec lean.

3. Build tools are hot: XcodeBuildMCP, Unity MCP, AWS toolkit lead GitHub stars; leveraging Tasks for async operations.

4. Security maturing: OAuth 2.1-based auth, multiple client registration methods, DNS rebinding protections, enterprise IdP integration, emerging security scanning tools.

5. Multi-vendor SDK ecosystem: 10 official SDKs across languages, maintained by Anthropic, Google, Microsoft, Spring/Broadcom, JetBrains.

6. MCP Apps: Servers can serve interactive HTML UIs in chat clients with sandboxed iframes.

7. Official registry at modelcontextprotocol/registry (6.9k stars) for server discovery, supporting multiple package types and automated publishing.
