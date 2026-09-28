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
package com.pragmatik.buildtools.sbt;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmatik.buildtools.security.ModelOutputPolicy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SbtTestSummaryDiagnosticTest {
    private final SbtOutputParser parser = new SbtOutputParser();
    private final JsonMapper json = new JsonMapper();

    @Test
    void scalaTestSummaryWithoutDetailsGetsGenericAssertionDiagnostic() {
        Map<String, Object> result = parser.parse(
                "[info] Passed: Total 3, Failed 1, Errors 0, Passed 2\n[error] Total time: 1 s\n", 1, "test");
        assertThat(summary(result).get("failed")).isEqualTo(1);
        assertThat(result.get("errorCount")).isEqualTo(1);
        assertThat(errors(result).getFirst().get("message")).isEqualTo("Test assertion failed");
    }

    @Test
    void scalaTestExecutionErrorGetsDistinctGenericDiagnostic() {
        Map<String, Object> result = parser.parse(
                "[info] Passed: Total 2, Failed 0, Errors 1, Passed 1\n[error] Total time: 1 s\n", 1, "test");
        assertThat(summary(result).get("errors")).isEqualTo(1);
        assertThat(result.get("errorCount")).isEqualTo(1);
        assertThat(errors(result).getFirst().get("message")).isEqualTo("Test failed during execution");
    }

    @Test
    void existingAssertionDiagnosticIsNotDuplicated() {
        String output = "[error] AssertionError: expected ready but was stale\n"
                + "[info] Passed: Total 1, Failed 1, Errors 0, Passed 0\n";
        Map<String, Object> result = parser.parse(output, 1, "test");
        assertThat(result.get("errorCount")).isEqualTo(1);
        assertThat(errors(result).getFirst().get("message")).isEqualTo("AssertionError: expected ready but was stale");
    }

    @Test
    void genericDiagnosticPrecedesUnrelatedErrorsAtVisibleLimit() {
        StringBuilder output = new StringBuilder("[info] Test run finished: 1 failed, 0 ignored, 3 total\n");
        for (int i = 0; i < 12; i++) {
            output.append("[error] unrelated execution detail ").append(i).append('\n');
        }
        Map<String, Object> result = parser.parse(output.toString(), 1, "test");
        assertThat(summary(result).get("failed")).isEqualTo(1);
        assertThat(result.get("errorCount")).isEqualTo(13);
        assertThat(errors(result).getFirst().get("message")).isEqualTo("Test assertion failed");
    }

    @Test
    void detailedAssertionSurvivesTwelveEarlierErrors() {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            output.append("[error] unrelated execution detail ").append(i).append('\n');
        }
        output.append("[error] AssertionError: expected SYNTHETIC_SECRET\n")
                .append("[info] Passed: Total 1, Failed 1, Errors 0, Passed 0\n");

        Map<String, Object> parsed = parser.parse(output.toString(), 1, "test");
        var visible =
                json.readTree(new ModelOutputPolicy().protect("analyze_build_output", json.writeValueAsString(parsed)));

        assertThat(parsed.get("errorCount")).isEqualTo(13);
        assertThat(visible.get("diagnostics").size()).isEqualTo(12);
        assertThat(visible.get("diagnostics").get(0).get("category").asText()).isEqualTo("test");
        assertThat(visible.toString()).doesNotContain("SYNTHETIC_SECRET");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summary(Map<String, Object> result) {
        return (Map<String, Object>) result.get("testSummary");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errors(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("errors");
    }
}
