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
package com.pragmatik.buildtools.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmatik.buildtools.security.ModelOutputPolicy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GradleTestFailureDiagnosticsTest {
    private final GradleOutputParser parser = new GradleOutputParser();
    private final JsonMapper json = new JsonMapper();

    @Test
    void summaryOnlyFailureProducesOnePrivateSafeTestDiagnostic() {
        Map<String, Object> parsed = parser.parse("1 test completed, 1 failed\nBUILD FAILED in 1s", 1, "test");

        assertThat(parsed.get("success")).isEqualTo(false);
        assertThat(parsed.get("testSummary"))
                .isEqualTo(Map.of("total", 1, "passed", 0, "failed", 1, "errors", 0, "skipped", 0));
        assertThat(parsed.get("errorCount")).isEqualTo(1);
        assertThat(messages(parsed)).containsExactly("Test assertion failed");

        var visible =
                json.readTree(new ModelOutputPolicy().protect("analyze_build_output", json.writeValueAsString(parsed)));
        assertThat(visible.get("diagnostics").get(0).get("category").asText()).isEqualTo("test");
    }

    @Test
    void detailedFailureDoesNotDuplicateTestDiagnosticOrExposeItsIdentity() {
        String output = """
                example.SyntheticPrivateTest > secretMethod() FAILED
                    org.opentest4j.AssertionFailedError: expected: <SYNTHETIC_SECRET>
                1 test completed, 1 failed
                BUILD FAILED in 1s
                """;
        Map<String, Object> parsed = parser.parse(output, 1, "test");

        assertThat(parsed.get("errorCount")).isEqualTo(1);
        assertThat(messages(parsed)).hasSize(1).noneMatch("Test assertion failed"::equals);
        String visible = new ModelOutputPolicy().protect("analyze_build_output", json.writeValueAsString(parsed));
        assertThat(visible).doesNotContain("SyntheticPrivateTest", "secretMethod", "SYNTHETIC_SECRET");
        assertThat(json.readTree(visible)
                        .get("diagnostics")
                        .get(0)
                        .get("category")
                        .asText())
                .isEqualTo("test");
    }

    @Test
    void testTaskFailureAlreadyProvidesAVisibleTestDiagnostic() {
        Map<String, Object> parsed =
                parser.parse("> Task :sample:test FAILED\n1 test completed, 1 failed\nBUILD FAILED in 1s", 1, "test");

        assertThat(parsed.get("errorCount")).isEqualTo(1);
        assertThat(messages(parsed)).containsExactly("Task :sample:test FAILED");
        var visible =
                json.readTree(new ModelOutputPolicy().protect("analyze_build_output", json.writeValueAsString(parsed)));
        assertThat(visible.get("diagnostics").get(0).get("category").asText()).isEqualTo("test");
    }

    @Test
    void moduleQualifiedWhatWentWrongGetsAUsefulTestDiagnostic() {
        String output = """
                1 test completed, 1 failed
                * What went wrong:
                Execution failed for task ':sample:test'.

                BUILD FAILED in 1s
                """;
        Map<String, Object> parsed = parser.parse(output, 1, "test");

        assertThat(parsed.get("errorCount")).isEqualTo(2);
        assertThat(messages(parsed))
                .containsExactly("Execution failed for task ':sample:test'.", "Test assertion failed");
        var visible =
                json.readTree(new ModelOutputPolicy().protect("analyze_build_output", json.writeValueAsString(parsed)));
        assertThat(visible.get("diagnostics").get(1).get("category").asText()).isEqualTo("test");
    }

    @Test
    void rootWhatWentWrongTestTaskNeedsNoExtraDiagnostic() {
        String output = """
                1 test completed, 1 failed
                * What went wrong:
                Execution failed for task ':test'.

                BUILD FAILED in 1s
                """;
        Map<String, Object> parsed = parser.parse(output, 1, "test");

        assertThat(parsed.get("errorCount")).isEqualTo(1);
        var visible =
                json.readTree(new ModelOutputPolicy().protect("analyze_build_output", json.writeValueAsString(parsed)));
        assertThat(visible.get("diagnostics").get(0).get("category").asText()).isEqualTo("test");
    }

    @Test
    void summaryDiagnosticSurvivesTwelveEarlierCompilerErrors() {
        StringBuilder output = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            output.append("error: cannot find symbol /synthetic/private/Sample.java:")
                    .append(i)
                    .append("\n");
        }
        output.append("1 test completed, 1 failed\nBUILD FAILED in 1s\n");

        Map<String, Object> parsed = parser.parse(output.toString(), 1, "test");
        var visible =
                json.readTree(new ModelOutputPolicy().protect("analyze_build_output", json.writeValueAsString(parsed)));

        assertThat(parsed.get("errorCount")).isEqualTo(13);
        assertThat(visible.get("diagnostics").size()).isEqualTo(12);
        assertThat(visible.get("diagnostics").get(0).get("category").asText()).isEqualTo("test");
        assertThat(visible.get("diagnosticsTruncated").booleanValue()).isTrue();
        assertThat(visible.toString()).doesNotContain("/synthetic/private");
    }

    @Test
    void detailedFailureSurvivesTwelveEarlierCompilerErrors() {
        StringBuilder output = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            output.append("error: cannot find symbol /synthetic/private/Sample.java:")
                    .append(i)
                    .append('\n');
        }
        output.append("example.SyntheticPrivateTest > secretMethod() FAILED\n")
                .append("1 test completed, 1 failed\nBUILD FAILED in 1s\n");

        Map<String, Object> parsed = parser.parse(output.toString(), 1, "test");
        var visible =
                json.readTree(new ModelOutputPolicy().protect("analyze_build_output", json.writeValueAsString(parsed)));

        assertThat(parsed.get("errorCount")).isEqualTo(13);
        assertThat(visible.get("diagnostics").size()).isEqualTo(12);
        assertThat(visible.get("diagnostics").get(0).get("category").asText()).isEqualTo("test");
        assertThat(visible.toString()).doesNotContain("SyntheticPrivateTest", "secretMethod", "/synthetic/private");
    }

    @Test
    void passingSummaryDoesNotInventFailureDiagnostic() {
        Map<String, Object> parsed = parser.parse("1 test completed, 0 failed\nBUILD SUCCESSFUL in 1s", 0, "test");
        assertThat(parsed.get("errorCount")).isEqualTo(0);
        assertThat(messages(parsed)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static List<String> messages(Map<String, Object> parsed) {
        return ((List<Map<String, Object>>) parsed.get("errors"))
                .stream().map(error -> error.get("message").toString()).toList();
    }
}
