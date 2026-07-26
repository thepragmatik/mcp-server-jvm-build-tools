# MCP Specification Delta — July 2026

**Research date:** 2026-07-26  
**Previous research:** `docs/mcp-ecosystem-research-june-2026.md` (last updated June 2026)  
**Target spec alignment:** 2026-07-28 (the upcoming MCP specification release)

---

## 1. Current State

The MCP spec is in a 10-week validation window between the **2026-07-28 Release Candidate** (locked May 21, 2026) and the **final specification publication on July 28, 2026** (2 days from this research date).

| Version | Status | Date |
|---------|--------|------|
| 2024-11-05 | Original spec | Nov 2024 |
| 2025-03-26 | Minor revision | Mar 2025 |
| 2025-06-18 | Minor revision | Jun 2025 |
| 2025-11-25 | Current stable | Nov 2025 |
| **2026-07-28 RC** | **Release candidate (final RC)** | **May 21, 2026** |
| **2026-07-28** | **Final (pending)** | **Jul 28, 2026** |

**Source:** https://blog.modelcontextprotocol.io/posts/2026-07-28-release-candidate/

---

## 2. What Happened June–July 2026

### 2.1. No New Spec Revision Since May 21

No new official specification release has shipped between June 2026 and late July 2026. The 2026-07-28 RC locked on May 21 remains the latest published version. The final spec publishes July 28.

### 2.2. Late Draft Amendments Merged (June–July 2026)

Several changes were merged into the **draft specification** during June and July 2026 (between RC lock and final):

| Merge Window | Change | Description |
|-------------|--------|-------------|
| ~July 16, 2026 | `tools/get_schema` | Optional method for fetching the full JSON Schema for a specific tool on demand (~400 tokens per tool). Clients send the minimal list to the LLM, resolving the "schema bloat in tools/list" issue. |
| June–July 2026 | SEP-2484 conformance requirement | Standards Track SEPs now require a matching conformance test before reaching Final status |
| June 2026 | Minor spec text clarifications | Various edge-case clarifications in the draft (no functional changes) |

**Source:** https://github.com/modelcontextprotocol/modelcontextprotocol/pulls (merged PRs in June–July 2026); `sudoall.com/category/mcp-protocol/` reporting on `tools/get_schema`

### 2.3. SDK Release Status

| SDK | 2025-11-25 Support | 2026-07-28 Beta Support | Status as of July 26 |
|-----|:---:|:---:|------|
| Python SDK | ✅ | ✅ v2 beta 2 (Jul 17) | Tier 1 — second beta available |
| TypeScript SDK | ✅ | ✅ v2 beta | Tier 1 — beta available |
| Go SDK | ✅ | ✅ v2 beta | Tier 1 — beta available |
| C# SDK | ✅ | ✅ v2 beta | Tier 1 — beta available |
| **Java SDK** | **✅ v2.0.0 GA** | **❌ No beta** | **Tier 2 — no 2026-07-28 beta published** |
| Rust SDK | ✅ | In progress | Tier 2 |
| Kotlin SDK | In progress | TBD | Tier 3 |
| Swift SDK | Early | TBD | Tier 3 |

**Critical finding:** The Java SDK 2.0.0 (released Jun 12, 2026) tracks the **2025-11-25** specification. **No 2026-07-28 beta has been published for the Java SDK** as of July 26, 2026. This is significant because the project's ROADMAP marks P0 as adapting to the 2026-07-28 spec — this cannot begin until the Java SDK ships 2026-07-28 support.

**Sources:**
- https://github.com/modelcontextprotocol/java-sdk/releases (v2.0.0, Jun 12)
- https://blog.modelcontextprotocol.io/posts/sdk-betas-2026-07-28/ (beta SDKs announcement)
- https://library.mikesailab.com/news/ai-news/mcp-news/july-2026/2026-07-17/ (MCP Protocol News, Jul 17)

---

## 3. Spring AI 2.0.0 GA — Impact on Project

**Spring AI 2.0.0 GA was released on June 12, 2026.** This project currently pins `2.0.0-RC2` (released June 10 — 2 days before GA).

| Artifact | Version | Date | Notes |
|----------|---------|------|-------|
| Spring AI 2.0.0-RC2 | 2.0.0-RC2 | Jun 10, 2026 | Currently pinned by project |
| **Spring AI 2.0.0 GA** | **2.0.0** | **Jun 12, 2026** | **Available — upgrade path exists** |
| MCP Java SDK (via Spring AI) | Bundled in Spring AI 2.0.0 | Jun 12 | Tracks 2025-11-25 spec |

**Implications:**
- The project should upgrade from `2.0.0-RC2` to `2.0.0-GA` — this is a stable release, not a pre-release
- Spring AI 2.0.0 GA bundles the MCP Java SDK v2.0.0 which tracks 2025-11-25
- The Spring AI team is a co-maintainer of the Java MCP SDK; Spring AI 2.1.x will likely track 2026-07-28

**Source:** https://github.com/spring-projects/spring-ai/releases (Spring AI 2.0.0, Jun 13 per release date)

### Spring AI Version Release Timeline

```
Jun 10: 2.0.0-RC2   ← project currently on this
Jun 12: 1.0.9        ← 1.x patch
Jun 12: 1.1.8        ← 1.x patch
Jun 12/13: 2.0.0 GA  ← SHOULD UPGRADE TO THIS
```

---

## 4. Summary of 2026-07-28 Spec Changes (Reproduced from RC)

For context, the 2026-07-28 spec contains these breaking changes from 2025-11-25:

| Change | SEPs | Impact |
|--------|------|--------|
| Stateless core — no more `initialize`/`initialized` handshake | SEP-2575, SEP-2567 | Removes session state; `_meta` carries version/capabilities per request |
| `server/discover` method (required) | SEP-2575 | New required endpoint for version negotiation |
| `Mcp-Method` and `Mcp-Name` headers required | SEP-2243 | Gateways route on headers instead of body inspection |
| Cacheable results (`ttlMs`, `cacheScope`) | SEP-2549 | `tools/list` responses cachable |
| Multi Round-Trip Requests (MRTR) | SEP-2322 | Replaces SSE-based elicitation with `InputRequiredResult` |
| Server-initiated requests constrained | SEP-2260 | Only while actively processing a client request |
| **Deprecated**: Roots, Sampling, Logging | SEP-2577 | Removal clock starts |
| Tasks → Extension (breaking from experimental) | SEP-2663 | New lifecycle: no `tasks/list`; server-directed creation |
| MCP Apps → Extension | SEP-1865 | Server-rendered UI (optional) |
| Full JSON Schema 2020-12 for tools | SEP-2106 | Stricter validation |
| Trace context (`traceparent`, `tracestate`) | SEP-414 | Standardized OpenTelemetry keys |
| Error code change (-32002 → -32602) for missing resource | SEP-2164 | Client update needed |
| Authorization hardening (6 SEPs) | SEP-2468, SEP-837, SEP-2352, SEP-2207, SEP-2350, SEP-2351 | OAuth/OpenID alignment |
| Extensions framework formalized | SEP-2133 | Reverse-DNS extension IDs, independent versioning |
| Feature lifecycle policy | SEP-2577 | 12-month min between deprecation and removal |
| Conformance tests required for SEP Final | SEP-2484 | Every Standards Track SEP needs conformance test |

---

## 5. Action Items for the Project

### P0 — Blocked: Await Java SDK 2026-07-28 Support

The ROADMAP marks MCP 2026-07-28 protocol upgrade as P0 (existential, HIGH effort). **As of July 26, 2026, the Java SDK still has no 2026-07-28 beta.** This blocks P0 work.

**Recommended actions:**
1. Monitor https://github.com/modelcontextprotocol/java-sdk/releases for a 2026-07-28 beta
2. Track the [java-sdk issue tracker](https://github.com/modelcontextprotocol/java-sdk/issues) for 2026-07-28 migration discussions
3. Consider contributing to the Java SDK's 2026-07-28 migration if the release timing is critical
4. Once the Java SDK ships support, proceed with the full migration per the ROADMAP P0 scope

### Immediate — Upgrade Spring AI from 2.0.0-RC2 to 2.0.0 GA

**This is not blocked and should be done now.**
1. Update `pom.xml` / `build.gradle` from `2.0.0-RC2` to `2.0.0`
2. Review the [Spring AI 2.0.0 upgrade notes](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0)
3. Verify all 39 tools still function correctly
4. Run the full test suite

### Watch: `tools/get_schema` (Merged Late July in Draft)

This optional method allows servers to serve tool schemas on-demand rather than embedding them all in `tools/list`. If this survives to final, the project should consider implementing it for LLM token efficiency — especially as the tool count grows.

**Source URL:** https://github.com/modelcontextprotocol/modelcontextprotocol/pulls (PRs merged ~Jul 16)
