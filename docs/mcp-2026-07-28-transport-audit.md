# MCP 2026-07-28 RC — Stateless Streamable HTTP Transport Audit

This document records the audit performed for issue **#85** and the gap-closing
changes shipped alongside it. It is grounded in inspection of the actually-resolved
dependency artifacts, not assumptions.

## 1. What the 2026-07-28 RC requires

From the official changelog (https://modelcontextprotocol.io/specification/draft/changelog):

- **Remove protocol-level sessions** and the `Mcp-Session-Id` header from the
  Streamable HTTP transport. List endpoints no longer vary per-connection.
  Cross-call state uses explicit, server-minted handles passed as ordinary tool
  arguments (SEP-2567).
- **Make MCP stateless:** remove the `initialize` / `notifications/initialized`
  handshake. Every request carries its protocol version, client identity and
  client capabilities in `_meta`
  (`io.modelcontextprotocol/protocolVersion`, `.../clientInfo`,
  `.../clientCapabilities`). Version mismatches return
  `UnsupportedProtocolVersionError` (SEP-2575).
- **Add `server/discover`:** servers MUST implement this RPC to advertise their
  supported protocol versions, capabilities and identity (SEP-2575).
- **Require standard request headers** `Mcp-Method` and `Mcp-Name` on Streamable
  HTTP POSTs, with `HeaderMismatchError` when headers and body disagree (SEP-2243).
- **Remove SSE stream resumability / redelivery** (`Last-Event-ID`, SSE event IDs).
  A broken stream loses the in-flight request; the client re-issues it as a new
  request with a new id (SEP-2575).
- **HTTP+SSE transport reclassified as Deprecated** — migrate to Streamable HTTP
  (SEP-2596).

## 2. What the bundled framework actually ships (evidence)

Dependency resolution (`./mvnw dependency:tree`) shows this repo pulls **only**
`org.springframework.ai:spring-ai-mcp:2.0.0-RC2` (plus `spring-ai-model`,
`spring-ai-commons`, `spring-ai-template-st`). That artifact contains the *client*
tool-callback bridge only (`SyncMcpToolCallback*`, `McpToolUtils`, customizers) —
**no MCP server auto-configuration**. The Spring AI MCP **server** starters
(`spring-ai-starter-mcp-server`, `spring-ai-starter-mcp-server-webmvc`) exist in the
BOM but are **not on this repo's classpath**.

The bundled MCP Java SDK is **2.0.0-RC1** (`io.modelcontextprotocol.sdk:mcp-core:2.0.0-RC1`,
built 2026-06-04), reached transitively. `javap` inspection of `mcp-core` shows:

| Capability (RC) | SDK 2.0.0-RC1 status (evidence) |
|---|---|
| Stateless HTTP transport | **Present** — `HttpServletStatelessServerTransport`, `McpStatelessServerHandler`, `McpStatelessServerFeatures`, `DefaultMcpStatelessServerHandler`. |
| Session-based Streamable HTTP | **Still present** — `HttpServletStreamableServerTransportProvider` holds a `ConcurrentHashMap<String, McpStreamableServerSession>` (session-shaped; this is the pre-RC model). |
| Legacy HTTP+SSE transport | **Still present** — `HttpServletSseServerTransportProvider` (deprecated per SEP-2596). |
| Origin/Host security validation | **Present** — `DefaultServerTransportSecurityValidator` exposes `validateOrigin`/`validateHost` only. |
| `Mcp-Method` / `Mcp-Name` header-vs-body validation | **Not implemented** — the security validator only checks Origin/Host; no header/body agreement check (`HttpServletRequestUtils.extractHeaders` merely copies headers). |
| `server/discover` RPC | **Not observed** in the bundled SDK surface. |
| `_meta` identity / handshake removal | Belongs to the (un-wired) server runtime; cannot be exercised in this repo until a server starter is added. |

**Conclusion:** the `[UNCERTAIN]` markers in the issue resolve to: the bundled
stack does **not** wire an MCP HTTP server in this repo at all today (it only
registers a `ToolCallbackProvider`), and even the bundled SDK RC1 does not yet
implement the RC's header-mismatch validation or `server/discover`. Fully adopting
the framework-level transport (handshake removal, `_meta` identity routing) requires
adding and configuring a Spring AI MCP **server** starter — an architecture change
flagged for explicit human approval rather than smuggled into this issue.

## 3. Gap-closing changes shipped in this repo (additive, backward-compatible)

All changes are additive / opt-in and **do not break existing MCP clients**:

1. **CORS allow-list** (`TransportConfig`): now advertises the standard request
   headers `Mcp-Method` and `Mcp-Name` (SEP-2243) and no longer advertises the
   removed `Mcp-Session-Id` header (SEP-2575).
2. **HeaderMismatch validation** (`McpHeaderValidationFilter`): for `POST /mcp/**`,
   when `Mcp-Method` / `Mcp-Name` are present *and contradict* the body / server
   identity, the request is rejected with `400` + a JSON-RPC `HeaderMismatchError`.
   It is purely additive: **absent** headers pass through (older clients are
   unaffected), **matching** headers pass through, and unparseable bodies are left
   to the transport — only genuine self-contradiction is rejected.
3. **`server/discover`** (`McpDiscoverController`): reachable at `GET /mcp/discover`
   (probe) and `POST /mcp/discover` (JSON-RPC), returning server identity, supported
   protocol versions (`2024-11-05`, `2025-03-26`, `2026-07-28`), capabilities and
   stateless-transport characteristics. The payload is identical on every call (no
   per-connection variance). When a server starter is later wired in, the framework
   routes the `server/discover` JSON-RPC method through the transport.
4. **Cross-call state**: confirmed already uses server-minted handles — async builds
   return a `taskId` (`AsyncBuildService`) that the client passes back as an ordinary
   tool argument to `get_build_task` / `cancel_build_task`. No protocol-level session
   pinning anywhere.
5. **`/mcp/build-events` SSE channel**: documented as a **supplementary, non-protocol
   telemetry feed**, not the MCP transport. It carries no event IDs and offers no
   resumability/redelivery (consistent with SEP-2575) and no session state. Decision:
   retained as an optional dashboard stream, explicitly out of MCP protocol scope.

## 4. Stateless operation summary

- No protocol-level sessions, no `Mcp-Session-Id`, no sticky routing required.
- Round-robin / load-balancer friendly: any replica can serve any request.
- Cross-call state is carried by explicit server-minted handles (e.g. `taskId`).
- See `MCP_INTEGRATION.md` → "Stateless Operation" for client-facing guidance.

## 5. Follow-up (requires human approval)

- Add and configure a Spring AI MCP **server** starter to wire the framework-level
  Streamable HTTP transport, so `_meta` identity (`protocolVersion`/`clientInfo`/
  `clientCapabilities`), handshake removal and `UnsupportedProtocolVersionError`
  are enforced by the runtime rather than only documented/probeable here.
- Upgrade the MCP Java SDK once a release implementing the RC's header validation
  and `server/discover` is available, then delegate to it and retire the local
  shims added in this issue.
