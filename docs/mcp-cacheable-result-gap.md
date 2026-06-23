# MCP `CacheableResult` (ttlMs / cacheScope) — implementation status & upstream gap

**Spec:** MCP upcoming-spec changelog, _Minor changes_ (SEP-2549).
**Issue:** #87. **Upstream pin:** Spring AI RC (#78).

## Spec requirement (quoted)

> "Require `ttlMs` and `cacheScope` fields on results returned by `tools/list`,
> `prompts/list`, `resources/list`, `resources/read`, and `resources/templates/list` via a new
> `CacheableResult` interface. `ttlMs` is a freshness hint (in milliseconds) allowing clients to
> cache responses and reduce polling; `cacheScope` (`"public"` or `"private"`) controls whether
> shared intermediaries may cache the response. Both fields complement existing `listChanged`
> notifications (SEP-2549)."

> "Servers SHOULD return tools from `tools/list` in a deterministic order to enable client-side
> caching and improve LLM prompt cache hit rates."

## Summary

| Acceptance criterion | Status |
|----------------------|--------|
| Deterministic `tools/list` order | **Implemented** — `DeterministicToolCallbackProvider` sorts the catalogue by tool name; covered by `DeterministicToolCallbackProviderTest` and `ToolCatalogueOrderingTest`. |
| `ttlMs`/`cacheScope` as typed `CacheableResult` fields on each result | **Blocked upstream** — not modelled by the bundled MCP SDK. Advertised on the discovery surfaces as an additive interim (see below). |
| Document the gap + upstream dependency | **This document.** |

## Verification — why per-result `CacheableResult` fields cannot be emitted yet

The bundled MCP SDK is `io.modelcontextprotocol.sdk:mcp-core:2.0.0-RC1` (transitive via
`org.springframework.ai:spring-ai-mcp:2.0.0-RC2`). Inspecting its result records shows they model
only their payload, `nextCursor`, and the `_meta` extensibility map — **no** `ttlMs`/`cacheScope`
fields and **no** `CacheableResult` interface:

```
$ javap -p 'io.modelcontextprotocol.spec.McpSchema$ListToolsResult'
public final class ...McpSchema$ListToolsResult extends Record implements ...McpSchema$Result {
  private final java.util.List<...Tool> tools;
  private final java.lang.String nextCursor;
  private final java.util.Map<java.lang.String,java.lang.Object> meta;
  ...
}
$ find . -name '*Cacheable*'    # (no results)
```

The same holds for `ListPromptsResult`, `ListResourcesResult`, `ReadResourceResult`, and
`ListResourceTemplatesResult`. Because the SDK does not expose these as first-class fields, a
spec-compliant `CacheableResult` (top-level `ttlMs`/`cacheScope`) cannot be emitted on individual
results without forking the SDK schema. The fields are therefore **blocked on an upstream SDK
release** that adds the `CacheableResult` interface.

## What is implemented now

1. **Deterministic ordering.** `DeterministicToolCallbackProvider` wraps the
   `MethodToolCallbackProvider` and returns the catalogue sorted by tool name on every call.
   `MethodToolCallbackProvider` discovers `@Tool` methods reflectively, and
   `Class.getDeclaredMethods()` has no ordering guarantee across JVMs/restarts; sorting at the
   provider boundary makes `tools/list` order a stable function of the (unique) tool names.

2. **Advertised caching policy.** `McpServerIdentity#cacheHints()` is the single source of truth for
   the per-method `{ttlMs, cacheScope}` policy and is surfaced under the `cacheHints` key of:
   - the server card — `GET /.well-known/mcp-server`
   - `server/discover` — `GET`/`POST /mcp/discover`

   ```json
   "cacheHints": {
     "tools/list":               { "ttlMs": 86400000, "cacheScope": "public" },
     "prompts/list":             { "ttlMs": 86400000, "cacheScope": "public" },
     "resources/list":           { "ttlMs": 86400000, "cacheScope": "public" },
     "resources/templates/list": { "ttlMs": 86400000, "cacheScope": "public" },
     "resources/read":           { "ttlMs": 300000,   "cacheScope": "private" }
   }
   ```

   The catalogue is static for the lifetime of the process, so the list surfaces use a generous,
   `public` TTL; per-project content reads reflect mutable on-disk state, so `resources/read` uses a
   short, `private` TTL. Both TTLs are configurable
   (`buildtools.cache.catalog-ttl-ms`, `buildtools.cache.read-ttl-ms`).

## Backward compatibility

Both changes are additive and negotiation-free:

- **Deterministic ordering** returns the exact same set of tools with the same definitions — only
  the iteration order is normalised. No tool is added, removed, renamed, or changed.
- **`cacheHints`** is a new top-level key on the discovery surfaces. Existing clients that do not
  know the key simply ignore it (the spec and this server already tolerate unknown fields). No
  existing field changes shape or meaning.

## Migration path (when the upstream SDK adds `CacheableResult`)

1. Bump the MCP SDK / `spring-ai-mcp` to the release that exposes `ttlMs`/`cacheScope` on the result
   records (tracked with the Spring AI RC pin, #78).
2. Wire a result customizer (once the Spring AI MCP server starter routes list/read through the
   transport) that stamps each result's `ttlMs`/`cacheScope` from `McpServerIdentity#cacheHints()`.
3. Keep the discovery-surface `cacheHints` block as a discovery-time summary (it remains useful for
   gateways probing the card before connecting).
