# Backward Compatibility: MCP 2026-07-28 Spec

## How We Support Both Old and New Clients

### Protocol Version Negotiation
The server advertises all supported protocol versions in the server card:
```json
"mcpVersions": ["2024-11-05", "2025-03-26", "2026-07-28"]
```
Clients negotiate their preferred version during `initialize`.

### What Changed

| Change | Impact on Old Clients | Impact on New Clients |
|--------|----------------------|----------------------|
| Added 2026-07-28 to mcpVersions | None — non-breaking addition | Enables 2026-07-28 negotiation |
| Added Mcp-Method header support | None — header is optional | Enables stateless routing |
| Advertised cacheHints (ttlMs/cacheScope, SEP-2549) | None — unknown `cacheHints` key is ignored | Enables response/catalogue caching |
| Deterministic `tools/list` ordering (SEP-2549) | None — same tools, only the order is normalised | Improves client/prompt cache hit rates |
| Added extensions capability | None — descriptive only | Enables Tasks and MCP Apps |

### Breaking Changes (NOT YET Implemented)
The following changes from the 2026-07-28 spec are NOT yet implemented
and would BREAK existing clients:

- Removing Mcp-Session-Id header
- Removing session management
- Changing error code format
- Deprecating Roots/Sampling/Logging

These will be implemented in future phases with a deprecation window.

### Cache Hints — Advertised, Not Yet Emitted Per Result (SEP-2549)
The MCP RC `CacheableResult` interface adds top-level `ttlMs`/`cacheScope` fields to
`tools/list`, `prompts/list`, `resources/list`, `resources/read`, and
`resources/templates/list` results. The bundled MCP SDK
(`io.modelcontextprotocol.sdk` `2.0.0-RC1`, via `spring-ai-mcp` `2.0.0-RC2`) does **not**
yet model these as typed result fields, so the server advertises its caching policy on the
discovery surfaces (server card `cacheHints` + `server/discover`) as an additive,
backward-compatible interim. It will move to per-result `CacheableResult` fields once the
upstream SDK exposes them. See `docs/mcp-cacheable-result-gap.md` (upstream dependency
tracked with the Spring AI RC pin, #78).

### Testing Old Client Compatibility
```bash
# Test with 2025-11-25 client
curl -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize",
       "params":{"protocolVersion":"2025-11-25","capabilities":{},
       "clientInfo":{"name":"old-client","version":"1.0"}}}'
```
