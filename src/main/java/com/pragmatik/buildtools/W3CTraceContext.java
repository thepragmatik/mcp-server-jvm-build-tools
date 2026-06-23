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

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An immutable, parsed <a href="https://www.w3.org/TR/trace-context/">W3C Trace
 * Context</a> as carried by the MCP {@code _meta} keys standardized by SEP-414:
 * {@code traceparent}, {@code tracestate}, and {@code baggage}.
 *
 * <p>This is the <em>remote</em> (incoming) context: the {@link #traceId()} ties
 * the current operation into a distributed trace, and {@link #parentSpanId()} is
 * the caller's span id, which becomes the parent of any span the server starts.
 *
 * <p>Parsing is strict per the W3C specification — an invalid {@code traceparent}
 * yields {@link Optional#empty()} from {@link #parse(String)} so the caller can
 * safely "behave as today" (no trace) rather than corrupt a trace with malformed
 * input. {@code tracestate} and {@code baggage} are carried opaquely (vendor- and
 * key/value-defined) and are only length-bounded for safety.
 */
public final class W3CTraceContext {

    /** The only {@code traceparent} version this server emits. */
    static final String SUPPORTED_VERSION = "00";

    /** Reserved/invalid {@code traceparent} version per the W3C spec. */
    private static final String INVALID_VERSION = "ff";

    /**
     * Defensive upper bound (characters) for the opaque {@code tracestate} /
     * {@code baggage} values. The W3C spec recommends keeping each at or below
     * 8192 bytes; values beyond this are dropped rather than propagated.
     */
    static final int MAX_OPAQUE_LENGTH = 8192;

    private static final Pattern VERSION = Pattern.compile("[0-9a-f]{2}");
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern TRACE_FLAGS = Pattern.compile("[0-9a-f]{2}");

    private static final String ALL_ZERO_TRACE_ID = "0".repeat(32);
    private static final String ALL_ZERO_SPAN_ID = "0".repeat(16);

    private final String version;
    private final String traceId;
    private final String parentSpanId;
    private final String traceFlags;
    private final String traceState;
    private final String baggage;

    private W3CTraceContext(
            String version, String traceId, String parentSpanId, String traceFlags, String traceState, String baggage) {
        this.version = version;
        this.traceId = traceId;
        this.parentSpanId = parentSpanId;
        this.traceFlags = traceFlags;
        this.traceState = traceState;
        this.baggage = baggage;
    }

    /**
     * Parses a {@code traceparent} header value into a trace context.
     *
     * @param traceparent the raw {@code traceparent} value (may be {@code null})
     * @return the parsed context, or {@link Optional#empty()} when the value is
     *     absent or malformed
     */
    public static Optional<W3CTraceContext> parse(String traceparent) {
        if (traceparent == null) {
            return Optional.empty();
        }
        String value = traceparent.trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }

        String[] parts = value.split("-", -1);
        if (parts.length < 4) {
            return Optional.empty();
        }

        String version = parts[0];
        String traceId = parts[1];
        String spanId = parts[2];
        String flags = parts[3];

        if (!VERSION.matcher(version).matches() || INVALID_VERSION.equals(version)) {
            return Optional.empty();
        }
        // For the only currently-defined version (00), trailing data is invalid.
        // Future versions may append fields, which a "00" parser ignores.
        if (SUPPORTED_VERSION.equals(version) && parts.length != 4) {
            return Optional.empty();
        }
        if (!TRACE_ID.matcher(traceId).matches() || ALL_ZERO_TRACE_ID.equals(traceId)) {
            return Optional.empty();
        }
        if (!SPAN_ID.matcher(spanId).matches() || ALL_ZERO_SPAN_ID.equals(spanId)) {
            return Optional.empty();
        }
        if (!TRACE_FLAGS.matcher(flags).matches()) {
            return Optional.empty();
        }

        return Optional.of(new W3CTraceContext(version, traceId, spanId, flags, null, null));
    }

    /**
     * Returns a copy carrying the given opaque {@code tracestate} value. Blank or
     * over-long values are dropped (treated as absent).
     */
    public W3CTraceContext withTraceState(String traceState) {
        return new W3CTraceContext(version, traceId, parentSpanId, traceFlags, sanitizeOpaque(traceState), baggage);
    }

    /**
     * Returns a copy carrying the given opaque {@code baggage} value. Blank or
     * over-long values are dropped (treated as absent).
     */
    public W3CTraceContext withBaggage(String baggage) {
        return new W3CTraceContext(version, traceId, parentSpanId, traceFlags, traceState, sanitizeOpaque(baggage));
    }

    private static String sanitizeOpaque(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_OPAQUE_LENGTH) {
            return null;
        }
        return trimmed;
    }

    /** @return the 32-hex-character trace id shared by every span in the trace */
    public String traceId() {
        return traceId;
    }

    /** @return the caller's 16-hex-character span id (the parent of server spans) */
    public String parentSpanId() {
        return parentSpanId;
    }

    /** @return the 2-hex-character {@code trace-flags} field */
    public String traceFlags() {
        return traceFlags;
    }

    /** @return the {@code traceparent} version field */
    public String version() {
        return version;
    }

    /** @return the opaque {@code tracestate}, or {@code null} if none */
    public String traceState() {
        return traceState;
    }

    /** @return the opaque {@code baggage}, or {@code null} if none */
    public String baggage() {
        return baggage;
    }
}
