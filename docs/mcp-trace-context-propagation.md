# W3C Trace Context propagation via `_meta` — implementation status & upstream gap

**Spec:** MCP upcoming-spec changelog, _Minor changes_ (SEP-414).
**Issue:** #88. **Upstream pin:** Spring AI RC (#78).

## Spec requirement (quoted)

> "Document OpenTelemetry trace context propagation conventions for `_meta` keys
> (`traceparent`, `tracestate`, `baggage`) (SEP-414)."

> "W3C Trace Context propagation in `_meta` is now documented (SEP-414), locking down the
> `traceparent`, `tracestate`, and `baggage` key names so distributed traces correlate across SDKs
> and gateways ... a trace that starts in a host application can follow a tool call through the
> client SDK, the MCP server, and whatever the server calls downstream, and show up as a single
> span tree in an OpenTelemetry-compatible backend."

## Summary

| Acceptance criterion | Status |
|----------------------|--------|
| Read `traceparent`/`tracestate`/`baggage` from request `_meta` (exact key names); absent ⇒ behave as today | **Implemented** — `McpTraceContext.fromMeta(...)` + `W3CTraceContext.parse(...)`; covered by `TraceContextPropagationTest` and `W3CTraceContextTest`. |
| Span created/continued for tool calls (esp. `execute_build_command` / `execute_build_async`) | **Implemented** — `BuildTracer` opens a span around the build in `BuildToolsService` and `AsyncBuildService`. |
| Active trace context propagated to downstream tooling (env) | **Implemented** — the active span (which continues an inbound `_meta` context, or failing that an inherited environment `TRACEPARENT`, or otherwise a fresh root) is stamped via `TRACEPARENT`/`TRACESTATE`/`BAGGAGE` on every build subprocess (Maven invoker + embedder path, Gradle, SBT; sync and async). A host/CI-propagated `TRACEPARENT` in the server's own environment is **preserved**, not clobbered. |
| Test: inbound `traceparent` parsed ⇒ emitted span carries the same trace id | **Implemented** — `TraceContextPropagationTest.inboundTraceparentContinuedInSpan`. |
| Document the supported `_meta` trace keys | **Implemented** — `MCP_INTEGRATION.md` → _Distributed Tracing_ + this document. |
| Automatic activation from real inbound `_meta` on every tool call | **Blocked upstream** — the bundled SDK does not surface `_meta` to `@Tool` methods (see below). The mechanism is ready and tested; activation is a single `McpTraceContext.activateFromMeta(meta)` call once `_meta` is available. |

## Verification — why automatic inbound activation is blocked

The bundled MCP SDK is `io.modelcontextprotocol.sdk:mcp-core:2.0.0-RC1` (transitive via
`org.springframework.ai:spring-ai-mcp:2.0.0-RC2`). The protocol record **does** carry `_meta`:

```
$ javap -p 'io.modelcontextprotocol.spec.McpSchema$CallToolRequest'
public final class ...McpSchema$CallToolRequest extends Record implements ...McpSchema$Request {
  private final java.lang.String name;
  private final java.util.Map<java.lang.String,java.lang.Object> arguments;
  private final java.util.Map<java.lang.String,java.lang.Object> meta;   // <-- _meta
  ...
  public java.util.Map<java.lang.String,java.lang.Object> meta();
}
```

…but Spring AI's bridge from a `ToolCallback` to an MCP tool specification builds the
`ToolContext` with **only** the exchange — the request `_meta` is dropped before the `@Tool`
method runs:

```
$ javap -p -c 'org.springframework.ai.mcp.McpToolUtils'
  ...
  15: ldc   #244   // String exchange
  18: invokestatic // java/util/Map.of:(Object;Object;)Map;
  21: invokespecial // org/springframework/ai/chat/model/ToolContext."<init>":(Map;)V
  ...
```

The server-side `McpSyncServerExchange` likewise exposes no accessor for the current
`CallToolRequest` or its `_meta`:

```
$ javap -p 'io.modelcontextprotocol.server.McpSyncServerExchange'
  public java.lang.String sessionId();
  public ...ClientCapabilities getClientCapabilities();
  public ...Implementation getClientInfo();
  public ...McpTransportContext transportContext();
  // ... no CallToolRequest / no _meta accessor ...
```

So with the bundled SDK there is no supported hook to read `_meta.traceparent` per tool call.
This confirms the **[UNCERTAIN]** note in issue #88: the SDK does **not** read `_meta.traceparent`
into the OpenTelemetry context, and does not expose it to the server either.

## What this server ships today (and why it is safe)

- A complete, dependency-free W3C Trace Context layer: parsing (`W3CTraceContext`), span
  lifecycle (`TraceSpan`, `BuildTracer`), thread-scoped context (`TraceContextHolder`), and a
  `_meta` bridge (`McpTraceContext`) reading the exact SEP-414 keys.
- A span is opened around every build, so builds are traceable **today**. Span parentage is
  resolved in order: an active (nested) span → the request `_meta` context → a `TRACEPARENT`
  already present in the server's own environment (e.g. a CI runner that propagates W3C context to
  build steps) → otherwise a fresh, sampled root span. The environment fallback means a host/CI
  trace is **preserved** rather than replaced by an unrelated root (no regression for builds that
  inherited `TRACEPARENT` before this change).
- The active span is propagated to the build subprocess via `TRACEPARENT`/`TRACESTATE`/`BAGGAGE`,
  the conventional environment variables OpenTelemetry tooling reads to continue a trace. The
  in-process Maven invoker and every `ProcessBuilder`-based path funnel through the one
  `TraceContextHolder.applyToEnvironment` mechanism, so stale/orphaned `TRACESTATE`/`BAGGAGE` are
  cleared symmetrically across all build paths.
- `McpTraceContext.activateFromMeta(meta)` is the single, tested entry point that continues an
  inbound trace. It is invoked wherever `_meta` is observable; once the SDK forwards request
  `_meta` (or a host populates the `ToolContext`), inbound traces are continued automatically
  with **no client-visible change**.

## Backward compatibility

Trace propagation is additive and opt-in at the protocol level: it only continues a *client*
trace when a client supplies a valid `traceparent` `_meta` key. Clients that send no trace context
— including existing clients unaware of `_meta` — see identical behaviour and responses.

For the build subprocess environment there is **no regression** either: a build that already
inherited a host/CI `TRACEPARENT` continues to join that trace (the server now inserts its own
span in between, in the same trace), and a build with no inherited context starts a fresh root
span exactly as a fresh server would. The server never reparents a host-propagated trace onto an
unrelated root.

## Upstream dependency

Closing the automatic-activation gap requires the MCP Java SDK / Spring AI MCP bridge to surface
request `_meta` to tool handlers (e.g. via `ToolContext`). Tracked under the Spring AI RC pin
(#78). No schema or wire change is needed in this server when that lands.
