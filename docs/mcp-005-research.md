# mcp-005 Research: `server/discover` Capability Design Space

**Date:** 2026-09-11 · **Status:** Research complete — implementation not started
**Baseline:** `main` @ `7231f31` (branch `mission-baseline`)

This document surveys the design space for the mcp-005 `server/discover`
capability, grounded in the code as it exists at `7231f31`. It is the research
input for the issues filed under this feature; it proposes no implementation
code itself.

---

## 1. What already exists (survey of the actual code)

### 1.1 The discovery surfaces today

| Surface | Code | Notes |
|---|---|---|
| `GET /mcp/discover` + `POST /mcp/discover` (JSON-RPC envelope) | `transport/McpDiscoverController.java` | Standalone Spring `@RestController`. Payload built from `McpServerIdentity`. Its javadoc states explicitly: *"When the Spring AI MCP server starter is wired in, the framework routes the `server/discover` JSON-RPC method through the transport; this controller provides the reachable, dependency-free surface today."* — i.e. the **real MCP JSON-RPC endpoint (`POST /mcp`) does not yet answer the `server/discover` method.** |
| `GET /.well-known/mcp-server` (server card) + `/health`, `/health/ready`, `/health/live` | `transport/ServerCardController.java` | Rich card: identity, transports, `mcpVersions`, `discover: "/mcp/discover"` pointer, capabilities, `cacheHints`, supported build tools, requirements, features, deprecations, security, registry. |
| Shared identity source | `application/McpServerIdentity.java` | Single bean all surfaces read: name/version from `spring.ai.mcp.server.name`/`.version`, vendor, `SUPPORTED_PROTOCOL_VERSIONS = [2024-11-05, 2025-03-26, 2026-07-28]`, `latestProtocolVersion`, capabilities map, `cacheHints` (SEP-2549 `ttlMs`/`cacheScope`, TTLs configurable via `buildtools.cache.catalog-ttl-ms` / `buildtools.cache.read-ttl-ms`), stateless `transportProfile`. |
| Header validation | `transport/McpHeaderValidationFilter.java` | `Mcp-Method`/`Mcp-Name` HeaderMismatch check on `POST /mcp/**`; identity echoed from the same bean. |
| OAuth exemption | `security/OAuthResourceServerFilter.java` | `DISCOVER_PATH = "/mcp/discover"` is deliberately exempt from bearer enforcement (pre-auth surface). |
| Tool catalogue | `application/BuildToolsApplication.java` → `MethodToolCallbackProvider` wrapped in `tool/DeterministicToolCallbackProvider.java` | Static, name-sorted catalogue (SEP-2549 deterministic order). |

### 1.2 Existing tests (the pattern to extend)

`src/test/java/com/pragmatik/buildtools/transport/McpDiscoverControllerTest.java`
covers: GET payload contents (serverInfo/protocolVersions/latestProtocolVersion/capabilities), stateless transport metadata, JSON-RPC id echo, null-body POST, the `2026-07-28` constant, and cacheHints shape. `ServerCardControllerTest.java` and `TransportConfigTest.java` cover the neighbouring surfaces.

### 1.3 Governing spec statements (per docs/ROADMAP.md and issue #85)

The MCP 2026-07-28 RC (SEP-2575) requires servers to implement `server/discover`
advertising supported protocol versions, capabilities, and identity; clients may
call it before any other request for up-front version selection, or as a
backward-compatibility probe **on stdio**. Version mismatch yields
`UnsupportedProtocolVersionError`; requests carry version/identity/capabilities
in `_meta`.

---

## 2. Gaps between what exists and what the RC + roadmap want

1. **`server/discover` is not routable on the MCP protocol endpoint.** The
   controller serves `/mcp/discover` (REST + hand-rolled JSON-RPC envelope), but
   the framework-routed `POST /mcp` JSON-RPC method `server/discover` — the form
   the RC actually mandates — is not implemented. A client that speaks only the
   protocol gets nothing.
2. **No stdio backward-compatibility probe.** The RC allows `server/discover`
   as a stdio probe; today stdio mode has no discover path at all (the HTTP
   controllers are inactive with `spring.main.web-application-type=none`).
3. **The discover result does not describe the tool catalogue.** It advertises a
   bare `capabilities.tools = {}` object. It exposes no tool count, no tool
   names, and no grouping — so a client cannot make a meaningful pre-connection
   decision about whether this server is useful. The closed MISSION record
   lists "toolset organization / tool grouping for better discoverability" as
   remaining work.
4. **No cross-surface consistency tests.** The card and discover read the same
   bean, but nothing *tests* that the card's `mcpVersions`, `capabilities`,
   `cacheHints` and `discover` pointer agree with the discover result, so drift
   protection is by convention only.
5. **No config surface for what discover advertises.** `McpServerIdentity`
   fixes the advertised set in code (constants); there is no way to, e.g.,
   toggle extensions advertisement or restrict advertised transports per
   deployment, and no documented property contract for that.

---

## 2. What `server/discover` should expose (design space)

| Option | Description | Assessment |
|---|---|---|
| **A. Minimal RC-compliant result** | `serverInfo` + `protocolVersions` + `capabilities` only. | Already essentially shipped via the REST controller; the gap is routing it through the MCP endpoint. Lowest risk; does not advance discoverability. |
| **B. RC result + catalogue summary** | A + a `tools` summary (count, names in deterministic order, optional grouping by service) in the discover result. | Directly serves the "toolset grouping for better discoverability" goal; cheap because the catalogue is static and already name-sorted via `DeterministicToolCallbackProvider`. Risk: payload size for clients that only need version negotiation — mitigate by making the summary opt-in (see config). |
| **C. Full capability descriptors** | B + per-tool input/output schema digests or per-service capability documents. | High maintenance cost (duplication with `tools/list`); reject for mcp-005. |

**Recommendation: Option B, with the catalogue summary as an additive,
opt-in field.** The mandatory core stays exactly the RC shape already produced
by `McpDiscoverController.discoverResult()`; grouping rides on top.

### API shape (recommended)

- `POST /mcp` (JSON-RPC, framework-routed) MUST answer method
  `server/discover` with the same result object.
- `GET/POST /mcp/discover` stays as the dependency-free HTTP probe (unchanged
  contract; card's `discover` pointer keeps working).
- Result fields: existing `serverInfo`, `protocolVersions`,
  `latestProtocolVersion`, `capabilities`, `cacheHints`, `transport`, plus
  additive `tools` object: `{ "count": N, "names": [...], "groups": { "<service>": [names] } }`.
  Additive fields are backward-compatible for clients that ignore unknown keys
  (the server itself sets `spring.jackson.deserialization.fail-on-unknown-properties=false`
  for the same reason in reverse).

### Config surface

- `buildtools.discover.tools-summary` = `none | count | full` (default `full`).
  `none` reproduces today's payload exactly, giving deployments a knob to shed
  payload size without a code change.
- Reuse the existing property-binding pattern of `McpServerIdentity`
  (`@Value` with literal fallback so plain-construction unit tests and the
  Spring runtime agree — see the cache TTL fields for the precedent).

### Security / placement constraints

- `/mcp/discover` must remain exempt from bearer enforcement
  (`OAuthResourceServerFilter.DISCOVER_PATH`) — it is a pre-auth surface by
  design; any new summary data must therefore contain no secrets (tool names
  and service group names are fine; descriptions must stay generic).
- Any framework-routed variant on `POST /mcp` inherits the existing
  `McpHeaderValidationFilter` behaviour; discover requests must not break the
  HeaderMismatch check.

---

## 3. Intended feature slices (map to the filed issues)

1. **Slice 1 — protocol routing:** make the actual MCP JSON-RPC endpoint answer
   `server/discover` (verify what Spring AI 2.0.0-RC2 supports; fall back to an
   explicit handler if not). *Issue 1.*
2. **Slice 2 — catalogue summary:** extend the discover result with the
   deterministic tool summary + grouping, driven by
   `DeterministicToolCallbackProvider`, with the config knob. *Issue 2.*
3. **Slice 3 — stdio probe path:** document/implement the RC's stdio
   backward-compatibility probe story for stdio-only deployments. *Issue 3.*
4. **Slice 4 — consistency & docs:** cross-surface consistency tests (card ⇄
   discover ⇄ identity) and documentation updates (TOOLS.md "Server Card &
   Discover" section, ARCHITECTURE.md surface map). *Issue 4.*

## 4. Test strategy

- **Unit:** controller-level tests in the existing style
  (`McpDiscoverControllerTest`) for every new field and config permutation
  (`none|count|full`).
- **Consistency:** a new test asserting the server card and discover result
  agree on every shared field (`name`, `version`, `protocolVersions`/`mcpVersions`,
  `capabilities`, `cacheHints`, transport profile) — turns the "single shared
  source" javadoc claim into a verified invariant.
- **Routing (Slice 1):** MockMvc/WebMvcTest (or integration test under the
  `http` profile, matching the pattern used by `TransportConfigTest`) proving
  `POST /mcp` with `{"method":"server/discover"}` returns the JSON-RPC result
  envelope, and that the method is exempt from auth when the OAuth filter is
  enabled.
- **Determinism:** assert tool names arrive sorted (reuse the
  `DeterministicToolCallbackProvider` guarantee) and identical across repeated
  calls (SEP-2549 / prompt-cache friendliness).
- **Full gate:** `mvn -B verify` green per AGENTS.md before any PR.

## 5. Out of scope for mcp-005

- Emitting per-result `ttlMs`/`cacheScope` on list/read results (tracked
  separately in `docs/mcp-cacheable-result-gap.md`; issue #87 closed the
  deterministic-order half).
- MCP Registry submission pipeline, server icons/branding, completions.