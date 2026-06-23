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

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts and ends {@link TraceSpan spans} for tool calls, honouring any inbound
 * W3C trace context so the server's work joins a distributed trace started by the
 * host application (SEP-414).
 *
 * <p>Span parentage is resolved at {@link #startSpan(String)} time, in order:
 *
 * <ol>
 *   <li>If a span is already active on this thread, the new span is its child
 *       within the same trace (nested tool work).</li>
 *   <li>Otherwise, if an inbound {@link W3CTraceContext} has been activated for
 *       this thread (from request {@code _meta}), the new span continues that
 *       remote trace — same {@code traceId}, with the caller's span as parent.</li>
 *   <li>Otherwise, if the server's own environment carries a valid
 *       {@code TRACEPARENT} (e.g. a CI runner that propagates W3C context to build
 *       steps via {@code TRACEPARENT}/{@code TRACESTATE}/{@code BAGGAGE}), the new
 *       span continues <em>that</em> trace, so host-propagated correlation is
 *       preserved and the build subprocess joins the existing trace rather than an
 *       unrelated fresh one.</li>
 *   <li>Otherwise a fresh, sampled <em>root</em> span is started, so every tool
 *       call is still traceable even without an inbound context (no regression).</li>
 * </ol>
 *
 * <p>The returned {@link TraceScope} is the active context for the calling thread
 * until it is {@link TraceScope#close() closed}, at which point the previous span
 * is restored. While the scope is open, {@link TraceContextHolder#currentSpan()}
 * returns the new span and {@link TraceContextHolder#applyToEnvironment} stamps it
 * onto child build processes.
 */
public final class BuildTracer {

    /** {@code trace-flags} value marking a span as sampled (recorded). */
    static final String SAMPLED_FLAGS = "01";

    private static final Logger log = LoggerFactory.getLogger(BuildTracer.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private BuildTracer() {
        // Static factory.
    }

    /**
     * Starts a span for the given operation name and makes it the active context
     * on the current thread.
     *
     * @param name the span/operation name (e.g. {@code "execute_build_command"})
     * @return a scope that ends the span and restores the previous context on close
     */
    public static TraceScope startSpan(String name) {
        return startSpan(name, System.getenv());
    }

    /**
     * Starts a span resolving the ambient (host/CI) fallback context from the given
     * environment instead of {@link System#getenv()}.
     *
     * <p>Package-private so tests can exercise the inherited-{@code TRACEPARENT}
     * fallback deterministically without depending on the JVM's real environment.
     *
     * @param name the span/operation name
     * @param environment the ambient process environment consulted only when there
     *     is no active span and no inbound {@code _meta} context (may be {@code null})
     * @return a scope that ends the span and restores the previous context on close
     */
    static TraceScope startSpan(String name, Map<String, String> environment) {
        TraceSpan parentSpan = TraceContextHolder.currentSpan().orElse(null);

        String traceId;
        String parentSpanId;
        String traceFlags;
        String traceState;
        String baggage;
        boolean remoteParent;

        if (parentSpan != null) {
            // Nested span within an already-active trace.
            traceId = parentSpan.traceId();
            parentSpanId = parentSpan.spanId();
            traceFlags = parentSpan.traceFlags();
            traceState = parentSpan.traceState();
            baggage = parentSpan.baggage();
            remoteParent = false;
        } else {
            // Prefer a per-request _meta context; otherwise fall back to an ambient
            // TRACEPARENT propagated into the server's own environment by a host/CI.
            W3CTraceContext inbound = TraceContextHolder.inbound()
                    .or(() -> TraceContextHolder.fromEnvironment(environment))
                    .orElse(null);
            if (inbound != null) {
                // Continue the inbound remote trace (request _meta or host/CI env).
                traceId = inbound.traceId();
                parentSpanId = inbound.parentSpanId();
                traceFlags = inbound.traceFlags();
                traceState = inbound.traceState();
                baggage = inbound.baggage();
                remoteParent = true;
            } else {
                // No inbound context: start a fresh, sampled root span.
                traceId = randomId(16);
                parentSpanId = null;
                traceFlags = SAMPLED_FLAGS;
                traceState = null;
                baggage = null;
                remoteParent = false;
            }
        }

        TraceSpan span = new TraceSpan(
                name,
                traceId,
                randomId(8),
                parentSpanId,
                traceFlags,
                traceState,
                baggage,
                remoteParent,
                System.nanoTime());

        TraceSpan previous = TraceContextHolder.setCurrentSpan(span);
        if (log.isDebugEnabled()) {
            log.debug(
                    "span start name={} traceId={} spanId={} parentSpanId={} remoteParent={}",
                    span.name(),
                    span.traceId(),
                    span.spanId(),
                    span.parentSpanId(),
                    span.hasRemoteParent());
        }
        return new SpanScope(span, previous);
    }

    private static String randomId(int byteCount) {
        byte[] bytes = new byte[byteCount];
        do {
            RANDOM.nextBytes(bytes);
        } while (isAllZero(bytes));
        return HEX.formatHex(bytes);
    }

    private static boolean isAllZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /** Ends a span and restores the previously-active span on close. */
    private static final class SpanScope implements TraceScope {
        private final TraceSpan span;
        private final TraceSpan previous;
        private boolean closed;

        private SpanScope(TraceSpan span, TraceSpan previous) {
            this.span = span;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            TraceContextHolder.restoreSpan(previous);
            if (log.isDebugEnabled()) {
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - span.startNanos());
                log.debug(
                        "span end name={} traceId={} spanId={} durationMs={}",
                        span.name(),
                        span.traceId(),
                        span.spanId(),
                        durationMs);
            }
        }
    }
}
