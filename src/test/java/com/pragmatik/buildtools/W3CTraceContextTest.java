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

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link W3CTraceContext} W3C {@code traceparent} parsing and rendering (SEP-414). */
@DisplayName("W3CTraceContext parsing (W3C Trace Context / SEP-414)")
class W3CTraceContextTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT_ID = "00f067aa0ba902b7";

    @Test
    @DisplayName("parses a well-formed sampled traceparent")
    void parsesWellFormedTraceparent() {
        Optional<W3CTraceContext> parsed = W3CTraceContext.parse("00-" + TRACE_ID + "-" + PARENT_ID + "-01");

        assertThat(parsed).isPresent();
        W3CTraceContext ctx = parsed.get();
        assertThat(ctx.version()).isEqualTo("00");
        assertThat(ctx.traceId()).isEqualTo(TRACE_ID);
        assertThat(ctx.parentSpanId()).isEqualTo(PARENT_ID);
        assertThat(ctx.traceFlags()).isEqualTo("01");
        assertThat(ctx.sampled()).isTrue();
        assertThat(ctx.traceState()).isNull();
        assertThat(ctx.baggage()).isNull();
    }

    @Test
    @DisplayName("recognises the not-sampled flag")
    void recognisesNotSampledFlag() {
        W3CTraceContext ctx = W3CTraceContext.parse("00-" + TRACE_ID + "-" + PARENT_ID + "-00")
                .orElseThrow();
        assertThat(ctx.sampled()).isFalse();
    }

    @Test
    @DisplayName("trims surrounding whitespace before parsing")
    void trimsWhitespace() {
        assertThat(W3CTraceContext.parse("  00-" + TRACE_ID + "-" + PARENT_ID + "-01  "))
                .isPresent();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "   ",
                "00-" + TRACE_ID + "-" + PARENT_ID, // too few fields
                "ff-" + TRACE_ID + "-" + PARENT_ID + "-01", // reserved/invalid version
                "0-" + TRACE_ID + "-" + PARENT_ID + "-01", // version wrong length
                "00-" + TRACE_ID + "-" + PARENT_ID + "-01-extra", // trailing data for v00
                "00-00000000000000000000000000000000-" + PARENT_ID + "-01", // all-zero trace id
                "00-" + TRACE_ID + "-0000000000000000-01", // all-zero span id
                "00-XYZ92f3577b34da6a3ce929d0e0e4736-" + PARENT_ID + "-01", // non-hex trace id
                "00-4BF92F3577B34DA6A3CE929D0E0E4736-" + PARENT_ID + "-01", // uppercase rejected
                "00-4bf92f3577b34da6-" + PARENT_ID + "-01", // trace id wrong length
                "00-" + TRACE_ID + "-00f067aa0b-01", // span id wrong length
            })
    @DisplayName("rejects absent or malformed traceparent values")
    void rejectsMalformed(String traceparent) {
        assertThat(W3CTraceContext.parse(traceparent)).isEmpty();
    }

    @Test
    @DisplayName("parses a future version, ignoring trailing fields")
    void parsesFutureVersionIgnoringTrailingFields() {
        Optional<W3CTraceContext> parsed =
                W3CTraceContext.parse("cc-" + TRACE_ID + "-" + PARENT_ID + "-01-future-field");
        assertThat(parsed).isPresent();
        assertThat(parsed.get().traceId()).isEqualTo(TRACE_ID);
        assertThat(parsed.get().version()).isEqualTo("cc");
    }

    @Test
    @DisplayName("formatTraceparent keeps the trace id and flags for a new child span")
    void formatTraceparentForChild() {
        W3CTraceContext ctx = W3CTraceContext.parse("00-" + TRACE_ID + "-" + PARENT_ID + "-01")
                .orElseThrow();
        String child = "0123456789abcdef";

        assertThat(ctx.formatTraceparent(child)).isEqualTo("00-" + TRACE_ID + "-" + child + "-01");
    }

    @Test
    @DisplayName("carries opaque tracestate and baggage, trimming surrounding whitespace")
    void carriesOpaqueValues() {
        W3CTraceContext ctx = W3CTraceContext.parse("00-" + TRACE_ID + "-" + PARENT_ID + "-01")
                .orElseThrow()
                .withTraceState("  vendor=value  ")
                .withBaggage("  key=val  ");

        assertThat(ctx.traceState()).isEqualTo("vendor=value");
        assertThat(ctx.baggage()).isEqualTo("key=val");
    }

    @Test
    @DisplayName("drops blank or over-long opaque values")
    void dropsBlankOrOverlongOpaqueValues() {
        W3CTraceContext base = W3CTraceContext.parse("00-" + TRACE_ID + "-" + PARENT_ID + "-01")
                .orElseThrow();
        String overLong = "x".repeat(W3CTraceContext.MAX_OPAQUE_LENGTH + 1);

        assertThat(base.withTraceState("   ").traceState()).isNull();
        assertThat(base.withTraceState(null).traceState()).isNull();
        assertThat(base.withTraceState(overLong).traceState()).isNull();
        assertThat(base.withBaggage("   ").baggage()).isNull();
        assertThat(base.withBaggage(overLong).baggage()).isNull();
    }
}
