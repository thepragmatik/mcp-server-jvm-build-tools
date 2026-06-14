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
| Added ttlMs configuration | None — default 5min cache TTL | Enables response caching |
| Added extensions capability | None — descriptive only | Enables Tasks and MCP Apps |

### Breaking Changes (NOT YET Implemented)
The following changes from the 2026-07-28 spec are NOT yet implemented
and would BREAK existing clients:

- Removing Mcp-Session-Id header
- Removing session management
- Changing error code format
- Deprecating Roots/Sampling/Logging

These will be implemented in future phases with a deprecation window.

### Testing Old Client Compatibility
```bash
# Test with 2025-11-25 client
curl -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize",
       "params":{"protocolVersion":"2025-11-25","capabilities":{},
       "clientInfo":{"name":"old-client","version":"1.0"}}}'
```
