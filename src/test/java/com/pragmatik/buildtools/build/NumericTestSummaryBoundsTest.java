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
package com.pragmatik.buildtools.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmatik.buildtools.gradle.GradleOutputParser;
import com.pragmatik.buildtools.sbt.SbtOutputParser;
import com.pragmatik.buildtools.security.ModelOutputPolicy;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NumericTestSummaryBoundsTest {
    private static final String HUGE = "9".repeat(128);

    private final GradleOutputParser gradle = new GradleOutputParser();
    private final SbtOutputParser sbt = new SbtOutputParser();
    private final JsonMapper json = new JsonMapper();

    @Test
    void gradleHugeCountsRemainVisibleAndConsistent() {
        Map<String, Object> parsed =
                gradle.parse(HUGE + " tests completed, " + HUGE + " failed\nBUILD FAILED in 1s", 1, "test");

        assertSummary(parsed, 1_000_000, 0, 1_000_000, 0, 0, true);
        assertModelSummary(parsed, 1_000_000, 1_000_000, true);
        assertThat(parsed.get("errorCount")).isEqualTo(1);
    }

    @Test
    void gradlePreservesBoundaryAndDoesNotReportNegativePassedCount() {
        assertSummary(gradle.parse("1000000 tests completed, 1 failed", 1, "test"), 1_000_000, 999_999, 1, 0, 0, false);
        assertSummary(gradle.parse("1 test completed, 2 failed", 1, "test"), 2, 0, 2, 0, 0, true);
    }

    @Test
    void sbtHugeScalaTestCountsRemainVisibleAndConsistent() {
        Map<String, Object> parsed =
                sbt.parse("[info] Passed: Total " + HUGE + ", Failed " + HUGE + ", Errors 0, Passed 0\n", 1, "test");

        assertSummary(parsed, 1_000_000, 0, 1_000_000, 0, 0, true);
        assertModelSummary(parsed, 1_000_000, 1_000_000, true);
    }

    @Test
    void sbtHugeJUnitAndSpecs2CountsDoNotThrowOrWrap() {
        Map<String, Object> junit =
                sbt.parse("[info] Test run finished: " + HUGE + " failed, 0 ignored, " + HUGE + " total\n", 1, "test");
        assertSummary(junit, 1_000_000, 0, 1_000_000, 0, 0, true);

        Map<String, Object> specs2 = sbt.parse(
                "[info] Total for specification Example: " + HUGE + " examples, " + HUGE + " failures\n", 1, "test");
        assertSummary(specs2, 1_000_000, 0, 1_000_000, 0, 0, true);
    }

    @Test
    void sbtSaturatesRepeatedModulesAndPreservesOrdinaryTotals() {
        String line = "[info] Test run finished: 1 failed, 0 ignored, 700000 total\n";
        assertSummary(sbt.parse(line + line, 1, "test"), 1_000_000, 999_998, 2, 0, 0, true);

        assertSummary(
                sbt.parse("[info] Test run finished: 1 failed, 2 ignored, 10 total\n", 1, "test"),
                10,
                7,
                1,
                0,
                2,
                false);
    }

    @Test
    void sbtMalformedComponentCountsCannotMakePassedNegative() {
        assertSummary(
                sbt.parse("[info] Test run finished: 3 failed, 4 ignored, 2 total\n", 1, "test"), 7, 0, 3, 0, 4, true);
    }

    @SuppressWarnings("unchecked")
    private static void assertSummary(
            Map<String, Object> parsed, int total, int passed, int failed, int errors, int skipped, boolean capped) {
        Map<String, Object> summary = (Map<String, Object>) parsed.get("testSummary");
        assertThat(summary)
                .containsEntry("total", total)
                .containsEntry("passed", passed)
                .containsEntry("failed", failed)
                .containsEntry("errors", errors)
                .containsEntry("skipped", skipped);
        assertThat(summary.containsKey("countsCapped")).isEqualTo(capped);
    }

    private void assertModelSummary(Map<String, Object> parsed, int total, int failed, boolean capped) {
        var visible =
                json.readTree(new ModelOutputPolicy().protect("analyze_build_output", json.writeValueAsString(parsed)));
        assertThat(visible.get("testSummary").get("total").asInt()).isEqualTo(total);
        assertThat(visible.get("testSummary").get("failed").asInt()).isEqualTo(failed);
        assertThat(visible.get("testSummary").get("countsCapped").asBoolean()).isEqualTo(capped);
    }
}
