/*
 *
 *  Copyright 2025 Rahul Thakur
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.pragmatik.buildtools;

import java.util.Map;
import java.util.Optional;

/**
 * Bridges MCP request {@code _meta} to the server's trace context.
 *
 * <p>The MCP draft (SEP-414) standardizes three {@code _meta} keys for W3C Trace
 * Context propagation, and this class reads <em>exactly</em> those key names:
 *
 * <ul>
 *   <li>{@value #TRACEPARENT_META_KEY}
 *   <li>{@value #TRACESTATE_META_KEY}
 *   <li>{@value #BAGGAGE_META_KEY}
 * </ul>
 *
 * <p>{@link #fromMeta(Map)} parses these into a {@link W3CTraceContext}; when the
 * keys are absent or the {@code traceparent} is malformed it returns
 * {@link Optional#empty()} so the server "behaves as today" — a build started
 * without a usable inbound context simply gets a fresh root span.
 *
 * <p>{@link #activate(W3CTraceContext)} (and {@link #activateFromMeta(Map)}) make a
 * context the inbound context for the current thread for the duration of the
 * returned {@link TraceScope}, so a span subsequently started by {@link BuildTracer}
 * continues the caller's trace.
 *
 * <p><b>SDK note.</b> The keys, parsing, formats, and propagation here are locked
 * to the SEP-414 conventions. The bundled MCP Java SDK (2.0.0-RC2) does not yet
 * surface request {@code _meta} to {@code @Tool} methods, so the server currently
 * emits a root span per build; once a host forwards {@code _meta} (or a future SDK
 * version exposes it) the same {@link #activateFromMeta(Map)} call continues the
 * inbound trace with no client-visible behaviour change. See
 * {@code docs/mcp-trace-context-propagation.md}.
 */
public final class McpTraceContext {

    /** {@code _meta} key carrying the W3C {@code traceparent} (SEP-414). */
    public static final String TRACEPARENT_META_KEY = "traceparent";

    /** {@code _meta} key carrying the W3C {@code tracestate} (SEP-414). */
    public static final String TRACESTATE_META_KEY = "tracestate";

    /** {@code _meta} key carrying W3C {@code baggage} (SEP-414). */
    public static final String BAGGAGE_META_KEY = "baggage";

    private McpTraceContext() {
        // Static utility.
    }

    /**
     * Extracts a trace context from a request's {@code _meta} map.
     *
     * @param meta the request {@code _meta} (may be {@code null} or empty)
     * @return the parsed context, or {@link Optional#empty()} when no valid
     *     {@code traceparent} is present
     */
    public static Optional<W3CTraceContext> fromMeta(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return Optional.empty();
        }
        Optional<W3CTraceContext> parsed = W3CTraceContext.parse(stringValue(meta.get(TRACEPARENT_META_KEY)));
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        W3CTraceContext context = parsed.get()
                .withTraceState(stringValue(meta.get(TRACESTATE_META_KEY)))
                .withBaggage(stringValue(meta.get(BAGGAGE_META_KEY)));
        return Optional.of(context);
    }

    /**
     * Makes the given context the inbound context for the current thread until the
     * returned scope is closed.
     *
     * @param context the context to activate (may be {@code null})
     * @return a scope restoring the previous inbound context on close, or
     *     {@link TraceScope#NOOP} when {@code context} is {@code null}
     */
    public static TraceScope activate(W3CTraceContext context) {
        if (context == null) {
            return TraceScope.NOOP;
        }
        W3CTraceContext previous = TraceContextHolder.setInbound(context);
        return new InboundScope(previous);
    }

    /**
     * Convenience: extracts a context from {@code _meta} and activates it.
     *
     * @param meta the request {@code _meta} (may be {@code null} or empty)
     * @return a scope restoring the previous inbound context on close, or
     *     {@link TraceScope#NOOP} when no valid context is present
     */
    public static TraceScope activateFromMeta(Map<String, Object> meta) {
        return fromMeta(meta).map(McpTraceContext::activate).orElse(TraceScope.NOOP);
    }

    private static String stringValue(Object value) {
        return value instanceof String s ? s : null;
    }

    /** Restores the previous inbound context when an activation scope closes. */
    private static final class InboundScope implements TraceScope {
        private final W3CTraceContext previous;
        private boolean closed;

        private InboundScope(W3CTraceContext previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            TraceContextHolder.restoreInbound(previous);
        }
    }
}
