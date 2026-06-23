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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for SEP-414 W3C Trace Context propagation: reading the
 * {@code _meta} keys, continuing the inbound trace in a server span, and stamping
 * the active span onto a child-process environment.
 */
@DisplayName("MCP trace context propagation (SEP-414)")
class TraceContextPropagationTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT_ID = "00f067aa0ba902b7";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + PARENT_ID + "-01";

    @AfterEach
    void noThreadLocalLeak() {
        // Every scope opened in a test must be closed, so the holder is clean afterwards.
        assertThat(TraceContextHolder.currentSpan()).isEmpty();
        assertThat(TraceContextHolder.inbound()).isEmpty();
    }

    private static Map<String, Object> meta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(McpTraceContext.TRACEPARENT_META_KEY, TRACEPARENT);
        meta.put(McpTraceContext.TRACESTATE_META_KEY, "vendor=opaque");
        meta.put(McpTraceContext.BAGGAGE_META_KEY, "tenant=acme");
        return meta;
    }

    @Test
    @DisplayName("reads the exact SEP-414 _meta keys into a trace context")
    void readsExactMetaKeys() {
        Optional<W3CTraceContext> ctx = McpTraceContext.fromMeta(meta());

        assertThat(ctx).isPresent();
        assertThat(ctx.get().traceId()).isEqualTo(TRACE_ID);
        assertThat(ctx.get().parentSpanId()).isEqualTo(PARENT_ID);
        assertThat(ctx.get().traceState()).isEqualTo("vendor=opaque");
        assertThat(ctx.get().baggage()).isEqualTo("tenant=acme");
    }

    @Test
    @DisplayName("absent / null / empty / malformed _meta yields no context (no regression)")
    void absentMetaYieldsEmpty() {
        assertThat(McpTraceContext.fromMeta(null)).isEmpty();
        assertThat(McpTraceContext.fromMeta(Map.of())).isEmpty();
        assertThat(McpTraceContext.fromMeta(Map.of("unrelated", "x"))).isEmpty();
        assertThat(McpTraceContext.fromMeta(Map.of(McpTraceContext.TRACEPARENT_META_KEY, "not-a-traceparent")))
                .isEmpty();
        // Non-string traceparent value is ignored.
        assertThat(McpTraceContext.fromMeta(Map.of(McpTraceContext.TRACEPARENT_META_KEY, 42)))
                .isEmpty();
    }

    @Test
    @DisplayName("an inbound traceparent is continued: the emitted span carries the same trace id")
    void inboundTraceparentContinuedInSpan() {
        try (TraceScope inbound = McpTraceContext.activateFromMeta(meta());
                TraceScope span = BuildTracer.startSpan("execute_build_command")) {

            TraceSpan active = TraceContextHolder.currentSpan().orElseThrow();

            // The emitted span is in the SAME trace as the inbound traceparent.
            assertThat(active.traceId()).isEqualTo(TRACE_ID);
            // ... parented by the caller's span ...
            assertThat(active.parentSpanId()).isEqualTo(PARENT_ID);
            assertThat(active.hasRemoteParent()).isTrue();
            // ... with its own freshly minted 16-hex span id.
            assertThat(active.spanId()).hasSize(16).matches("[0-9a-f]{16}").isNotEqualTo(PARENT_ID);
            // Opaque state/baggage are carried through for downstream propagation.
            assertThat(active.traceState()).isEqualTo("vendor=opaque");
            assertThat(active.baggage()).isEqualTo("tenant=acme");

            // The downstream traceparent keeps the inbound trace id (single span tree).
            assertThat(active.toTraceparent())
                    .isEqualTo("00-" + TRACE_ID + "-" + active.spanId() + "-01")
                    .contains(TRACE_ID);
        }
    }

    @Test
    @DisplayName("the active span is stamped onto a child-process environment")
    void activeSpanStampedOntoEnvironment() {
        Map<String, String> env = new HashMap<>();
        // Stale values that must be overwritten / cleared by the active span.
        env.put(TraceContextHolder.TRACEPARENT_ENV, "00-stale-stale-00");

        try (TraceScope inbound = McpTraceContext.activateFromMeta(meta());
                TraceScope span = BuildTracer.startSpan("execute_build_command")) {

            TraceSpan active = TraceContextHolder.currentSpan().orElseThrow();
            TraceContextHolder.applyToEnvironment(env);

            assertThat(env.get(TraceContextHolder.TRACEPARENT_ENV))
                    .isEqualTo(active.toTraceparent())
                    .contains(TRACE_ID);
            assertThat(env.get(TraceContextHolder.TRACESTATE_ENV)).isEqualTo("vendor=opaque");
            assertThat(env.get(TraceContextHolder.BAGGAGE_ENV)).isEqualTo("tenant=acme");
        }
    }

    @Test
    @DisplayName("stamping clears stale opaque vars when the span carries none")
    void stampingClearsStaleOpaqueVars() {
        Map<String, String> env = new HashMap<>();
        env.put(TraceContextHolder.TRACESTATE_ENV, "stale=state");
        env.put(TraceContextHolder.BAGGAGE_ENV, "stale=bag");

        // Inbound with traceparent only (no tracestate/baggage).
        try (TraceScope inbound = McpTraceContext.activate(
                        W3CTraceContext.parse(TRACEPARENT).orElseThrow());
                TraceScope span = BuildTracer.startSpan("execute_build_command")) {

            TraceContextHolder.applyToEnvironment(env);

            assertThat(env).containsKey(TraceContextHolder.TRACEPARENT_ENV);
            assertThat(env).doesNotContainKey(TraceContextHolder.TRACESTATE_ENV);
            assertThat(env).doesNotContainKey(TraceContextHolder.BAGGAGE_ENV);
        }
    }

    @Test
    @DisplayName("with no inbound context a fresh sampled root span is started (no regression)")
    void noInboundStartsRootSpan() {
        try (TraceScope span = BuildTracer.startSpan("execute_build_command")) {
            TraceSpan active = TraceContextHolder.currentSpan().orElseThrow();

            assertThat(active.traceId()).hasSize(32).matches("[0-9a-f]{32}");
            assertThat(active.parentSpanId()).isNull();
            assertThat(active.hasRemoteParent()).isFalse();
            assertThat(active.traceFlags()).isEqualTo(BuildTracer.SAMPLED_FLAGS);

            Map<String, String> env = new HashMap<>();
            TraceContextHolder.applyToEnvironment(env);
            assertThat(env.get(TraceContextHolder.TRACEPARENT_ENV)).isEqualTo(active.toTraceparent());
        }
    }

    @Test
    @DisplayName("with no active span the environment is left unchanged (no regression)")
    void noActiveSpanLeavesEnvironmentUnchanged() {
        Map<String, String> env = new HashMap<>();
        env.put("UNRELATED", "value");

        TraceContextHolder.applyToEnvironment(env);

        assertThat(env).containsOnlyKeys("UNRELATED");
    }

    @Test
    @DisplayName("nested spans share the trace and chain parent/child span ids")
    void nestedSpansChainParentage() {
        try (TraceScope outer = BuildTracer.startSpan("execute_build_command")) {
            TraceSpan outerSpan = TraceContextHolder.currentSpan().orElseThrow();

            try (TraceScope inner = BuildTracer.startSpan("nested")) {
                TraceSpan innerSpan = TraceContextHolder.currentSpan().orElseThrow();

                assertThat(innerSpan.traceId()).isEqualTo(outerSpan.traceId());
                assertThat(innerSpan.parentSpanId()).isEqualTo(outerSpan.spanId());
                assertThat(innerSpan.spanId()).isNotEqualTo(outerSpan.spanId());
            }

            // Closing the inner scope restores the outer span as the active context.
            assertThat(TraceContextHolder.currentSpan()).hasValue(outerSpan);
        }
    }

    @Test
    @DisplayName("closing scopes restores the previous context")
    void closingScopesRestoresContext() {
        assertThat(TraceContextHolder.inbound()).isEmpty();

        try (TraceScope inbound = McpTraceContext.activateFromMeta(meta())) {
            assertThat(TraceContextHolder.inbound()).isPresent();
            try (TraceScope span = BuildTracer.startSpan("execute_build_command")) {
                assertThat(TraceContextHolder.currentSpan()).isPresent();
            }
            assertThat(TraceContextHolder.currentSpan()).isEmpty();
        }
        assertThat(TraceContextHolder.inbound()).isEmpty();
    }

    @Test
    @DisplayName("activate(null) and activateFromMeta(absent) are inert no-op scopes")
    void inertScopes() {
        try (TraceScope s1 = McpTraceContext.activate(null);
                TraceScope s2 = McpTraceContext.activateFromMeta(Map.of())) {
            assertThat(s1).isSameAs(TraceScope.NOOP);
            assertThat(s2).isSameAs(TraceScope.NOOP);
            assertThat(TraceContextHolder.inbound()).isEmpty();
        }
    }
}
