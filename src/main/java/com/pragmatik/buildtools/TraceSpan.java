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

import java.util.Objects;

/**
 * An immutable description of a single span started by the server for a tool
 * call (for example {@code execute_build_command}).
 *
 * <p>A span belongs to a trace ({@link #traceId()}) and has its own freshly
 * generated {@link #spanId()}. When the span continues an inbound
 * {@link W3CTraceContext}, {@link #parentSpanId()} is the caller's span id and
 * {@link #traceId()} equals the inbound trace id, so the build subprocess and any
 * downstream tooling appear under the originating trace as a single span tree.
 *
 * <p>{@link #toTraceparent()} renders the W3C {@code traceparent} that downstream
 * processes should continue from — it always uses version {@code 00} (the only
 * version this server emits) with <em>this</em> span as the parent.
 */
public final class TraceSpan {

    private final String name;
    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final String traceFlags;
    private final String traceState;
    private final String baggage;
    private final boolean remoteParent;
    private final long startNanos;

    TraceSpan(
            String name,
            String traceId,
            String spanId,
            String parentSpanId,
            String traceFlags,
            String traceState,
            String baggage,
            boolean remoteParent,
            long startNanos) {
        this.name = Objects.requireNonNull(name, "name");
        this.traceId = Objects.requireNonNull(traceId, "traceId");
        this.spanId = Objects.requireNonNull(spanId, "spanId");
        this.parentSpanId = parentSpanId;
        this.traceFlags = Objects.requireNonNull(traceFlags, "traceFlags");
        this.traceState = traceState;
        this.baggage = baggage;
        this.remoteParent = remoteParent;
        this.startNanos = startNanos;
    }

    /** @return the span name (the tool/operation that opened it) */
    public String name() {
        return name;
    }

    /** @return the 32-hex-character trace id shared by every span in the trace */
    public String traceId() {
        return traceId;
    }

    /** @return this span's freshly generated 16-hex-character span id */
    public String spanId() {
        return spanId;
    }

    /** @return the parent span id, or {@code null} for a root span */
    public String parentSpanId() {
        return parentSpanId;
    }

    /** @return the 2-hex-character {@code trace-flags} field */
    public String traceFlags() {
        return traceFlags;
    }

    /** @return the opaque {@code tracestate} to propagate, or {@code null} */
    public String traceState() {
        return traceState;
    }

    /** @return the opaque {@code baggage} to propagate, or {@code null} */
    public String baggage() {
        return baggage;
    }

    /** @return {@code true} when this span continues a remote (inbound) parent */
    public boolean hasRemoteParent() {
        return remoteParent;
    }

    /** @return the monotonic start time (from {@link System#nanoTime()}) */
    long startNanos() {
        return startNanos;
    }

    /**
     * Renders the W3C {@code traceparent} that downstream processes should
     * continue from, using version {@code 00} with this span as the parent.
     *
     * @return a {@code traceparent} header value
     */
    public String toTraceparent() {
        return W3CTraceContext.SUPPORTED_VERSION + "-" + traceId + "-" + spanId + "-" + traceFlags;
    }
}
