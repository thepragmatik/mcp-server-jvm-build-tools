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
 * Thread-scoped storage for the active trace context.
 *
 * <p>Two pieces of state are tracked per thread:
 *
 * <ul>
 *   <li>the <b>inbound</b> {@link W3CTraceContext} extracted from a request's
 *       {@code _meta} (set via {@link McpTraceContext#activate(W3CTraceContext)}); and
 *   <li>the <b>active span</b> ({@link TraceSpan}) opened by {@link BuildTracer} for
 *       the current tool call.
 * </ul>
 *
 * <p>{@link #applyToEnvironment(Map)} stamps the active span's W3C trace context
 * onto a child-process environment via the conventional {@code TRACEPARENT},
 * {@code TRACESTATE}, and {@code BAGGAGE} variables, which OpenTelemetry-aware
 * build tooling reads to continue the trace into the subprocess.
 */
public final class TraceContextHolder {

    /** Environment variable used to propagate {@code traceparent} to a subprocess. */
    public static final String TRACEPARENT_ENV = "TRACEPARENT";

    /** Environment variable used to propagate {@code tracestate} to a subprocess. */
    public static final String TRACESTATE_ENV = "TRACESTATE";

    /** Environment variable used to propagate {@code baggage} to a subprocess. */
    public static final String BAGGAGE_ENV = "BAGGAGE";

    private static final ThreadLocal<W3CTraceContext> INBOUND = new ThreadLocal<>();
    private static final ThreadLocal<TraceSpan> ACTIVE_SPAN = new ThreadLocal<>();

    private TraceContextHolder() {
        // Static holder.
    }

    /** @return the inbound trace context for this thread, if any */
    public static Optional<W3CTraceContext> inbound() {
        return Optional.ofNullable(INBOUND.get());
    }

    /**
     * Sets the inbound context for this thread.
     *
     * @param context the inbound context (may be {@code null} to clear)
     * @return the previously-set inbound context, or {@code null} if none
     */
    static W3CTraceContext setInbound(W3CTraceContext context) {
        W3CTraceContext previous = INBOUND.get();
        if (context == null) {
            INBOUND.remove();
        } else {
            INBOUND.set(context);
        }
        return previous;
    }

    /**
     * Restores a previously-saved inbound context (used when a scope closes).
     *
     * @param previous the value to restore (may be {@code null} to clear)
     */
    static void restoreInbound(W3CTraceContext previous) {
        if (previous == null) {
            INBOUND.remove();
        } else {
            INBOUND.set(previous);
        }
    }

    /** @return the active span for this thread, if any */
    public static Optional<TraceSpan> currentSpan() {
        return Optional.ofNullable(ACTIVE_SPAN.get());
    }

    /**
     * Sets the active span for this thread.
     *
     * @param span the span to make active (may be {@code null} to clear)
     * @return the previously-active span, or {@code null} if none
     */
    static TraceSpan setCurrentSpan(TraceSpan span) {
        TraceSpan previous = ACTIVE_SPAN.get();
        if (span == null) {
            ACTIVE_SPAN.remove();
        } else {
            ACTIVE_SPAN.set(span);
        }
        return previous;
    }

    /**
     * Restores a previously-active span (used when a span scope closes).
     *
     * @param previous the span to restore (may be {@code null} to clear)
     */
    static void restoreSpan(TraceSpan previous) {
        if (previous == null) {
            ACTIVE_SPAN.remove();
        } else {
            ACTIVE_SPAN.set(previous);
        }
    }

    /**
     * Stamps the active span's W3C trace context onto the given (mutable) process
     * environment so a child build process can continue the trace.
     *
     * <p>When no span is active this is a no-op, so a build started without any
     * inbound trace context inherits the parent environment unchanged (no
     * regression). Stale {@code TRACEPARENT}/{@code TRACESTATE}/{@code BAGGAGE}
     * inherited from the server's own environment are overwritten when a span is
     * active so the child cannot continue an unrelated trace.
     *
     * @param environment a mutable environment map (e.g. {@code ProcessBuilder.environment()})
     */
    public static void applyToEnvironment(Map<String, String> environment) {
        if (environment == null) {
            return;
        }
        TraceSpan span = ACTIVE_SPAN.get();
        if (span == null) {
            return;
        }
        environment.put(TRACEPARENT_ENV, span.toTraceparent());
        putOrRemove(environment, TRACESTATE_ENV, span.traceState());
        putOrRemove(environment, BAGGAGE_ENV, span.baggage());
    }

    private static void putOrRemove(Map<String, String> environment, String key, String value) {
        if (value == null || value.isBlank()) {
            environment.remove(key);
        } else {
            environment.put(key, value);
        }
    }
}
