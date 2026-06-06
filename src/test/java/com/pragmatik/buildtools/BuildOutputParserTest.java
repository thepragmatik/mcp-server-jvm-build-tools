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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MavenOutputParser} and {@link GradleOutputParser}.
 */
@DisplayName("BuildOutputParser unit tests")
class BuildOutputParserTest {

    private final MavenOutputParser mavenParser = new MavenOutputParser();
    private final GradleOutputParser gradleParser = new GradleOutputParser();

    // ──────────────────────────────────────────────
    //  MavenOutputParser tests
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("MavenOutputParser")
    class MavenParserTests {

        @Test
        @DisplayName("parses successful Maven build output")
        void parsesSuccessfulBuild() {
            String output = """
                    [INFO] Scanning for projects...
                    [INFO] -------------------< com.example:my-app >-------------------
                    [INFO] Building my-app 1.0.0
                    [INFO]   from pom.xml
                    [INFO] --------------------------------[ jar ]---------------------------------
                    [INFO]
                    [INFO] --- maven-surefire-plugin:3.0.0:test (default-test) ---
                    [INFO] Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
                    [INFO]
                    [INFO] ------------------------------------------------------------------------
                    [INFO] BUILD SUCCESS
                    [INFO] ------------------------------------------------------------------------
                    [INFO] Total time:  12.345 s
                    [INFO] Finished at: 2026-06-05T15:24:14Z
                    [INFO] ------------------------------------------------------------------------
                    """;

            Map<String, Object> result = mavenParser.parse(output, 0, "clean test");

            assertThat(result.get("tool")).isEqualTo("maven");
            assertThat(result.get("command")).isEqualTo("clean test");
            assertThat(result.get("success")).isEqualTo(true);
            assertThat(result.get("duration")).asString().startsWith("12.");
            assertThat(result.get("errorCount")).isEqualTo(0);
            assertThat(result.get("warningCount")).isEqualTo(0);
            assertThat(result.get("rawOutput")).isNotNull();

            @SuppressWarnings("unchecked")
            Map<String, Object> testSummary = (Map<String, Object>) result.get("testSummary");
            assertThat(testSummary.get("total")).isEqualTo(42);
            assertThat(testSummary.get("passed")).isEqualTo(42);
            assertThat(testSummary.get("failed")).isEqualTo(0);
            assertThat(testSummary.get("skipped")).isEqualTo(0);
        }

        @Test
        @DisplayName("parses Maven build failure with test failures")
        void parsesFailedBuildWithTestFailures() {
            String output = """
                    [INFO] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
                    [INFO]
                    [INFO] ------------------------------------------------------------------------
                    [INFO] BUILD FAILURE
                    [INFO] ------------------------------------------------------------------------
                    """;

            Map<String, Object> result = mavenParser.parse(output, 1, "test");

            assertThat(result.get("tool")).isEqualTo("maven");
            assertThat(result.get("success")).isEqualTo(false);

            @SuppressWarnings("unchecked")
            Map<String, Object> testSummary = (Map<String, Object>) result.get("testSummary");
            assertThat(testSummary.get("total")).isEqualTo(3);
            assertThat(testSummary.get("failed")).isEqualTo(1);
            assertThat(testSummary.get("passed")).isEqualTo(2);
        }

        @Test
        @DisplayName("parses Maven compile errors with file:line")
        void parsesCompileErrorsWithFileLine() {
            String output = """
                    [INFO] Scanning for projects...
                    [INFO] -------------------------------------------------------
                    [INFO] Compiling 5 source files
                    [INFO] -------------------------------------------------------
                    [ERROR] /path/to/project/src/main/java/com/example/UserService.java:[42,12] cannot find symbol
                    [INFO] 1 error
                    [INFO] BUILD FAILURE
                    """;

            Map<String, Object> result = mavenParser.parse(output, 1, "compile");

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(result.get("errorCount")).isEqualTo(1);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
            assertThat(errors).hasSize(1);

            Map<String, Object> firstError = errors.get(0);
            assertThat(firstError.get("severity")).isEqualTo("ERROR");
            assertThat(firstError.get("file")).asString().contains("UserService.java");
            assertThat(firstError.get("line")).isEqualTo(42);
        }

        @Test
        @DisplayName("parses Maven warnings")
        void parsesWarnings() {
            String output = """
                    [WARNING] Using platform encoding (UTF-8 actually)
                    [WARNING] The artifact org.slf4j:slf4j-api has been relocated
                    [INFO] BUILD SUCCESS
                    """;

            Map<String, Object> result = mavenParser.parse(output, 0, "compile");

            assertThat(result.get("success")).isEqualTo(true);
            assertThat(result.get("warningCount")).isEqualTo(2);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> warnings = (List<Map<String, Object>>) result.get("warnings");
            assertThat(warnings).hasSize(2);
        }

        @Test
        @DisplayName("handles empty output gracefully")
        void handlesEmptyOutput() {
            Map<String, Object> result = mavenParser.parse("", 0, "test");

            assertThat(result.get("tool")).isEqualTo("maven");
            assertThat(result.get("errorCount")).isEqualTo(0);
            assertThat(result.get("warningCount")).isEqualTo(0);

            @SuppressWarnings("unchecked")
            Map<String, Object> testSummary = (Map<String, Object>) result.get("testSummary");
            assertThat(testSummary.get("total")).isEqualTo(0);
        }

        @Test
        @DisplayName("handles null output gracefully")
        void handlesNullOutput() {
            Map<String, Object> result = mavenParser.parse(null, 0, "test");

            assertThat(result.get("tool")).isEqualTo("maven");
            assertThat(result.get("errorCount")).isEqualTo(0);
        }

        @Test
        @DisplayName("aggregates multiple test module results")
        void aggregatesMultipleTestModules() {
            String output = """
                    [INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
                    [INFO] Tests run: 5, Failures: 1, Errors: 0, Skipped: 2
                    [INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
                    [INFO] BUILD SUCCESS
                    [INFO] Total time:  8.000 s
                    """;

            Map<String, Object> result = mavenParser.parse(output, 0, "test");

            @SuppressWarnings("unchecked")
            Map<String, Object> testSummary = (Map<String, Object>) result.get("testSummary");
            assertThat(testSummary.get("total")).isEqualTo(30);
            assertThat(testSummary.get("passed")).isEqualTo(27);
            assertThat(testSummary.get("failed")).isEqualTo(1);
            assertThat(testSummary.get("skipped")).isEqualTo(2);
        }
    }

    // ──────────────────────────────────────────────
    //  GradleOutputParser tests
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("GradleOutputParser")
    class GradleParserTests {

        @Test
        @DisplayName("parses successful Gradle build output")
        void parsesSuccessfulBuild() {
            String output = """
                    > Task :test
                                        
                    25 tests completed, 0 failed
                    
                    BUILD SUCCESSFUL in 3s
                    5 actionable tasks: 1 executed, 4 up-to-date
                    """;

            Map<String, Object> result = gradleParser.parse(output, 0, "test");

            assertThat(result.get("tool")).isEqualTo("gradle");
            assertThat(result.get("command")).isEqualTo("test");
            assertThat(result.get("success")).isEqualTo(true);
            assertThat(result.get("duration")).asString().startsWith("3");
            assertThat(result.get("rawOutput")).isNotNull();

            @SuppressWarnings("unchecked")
            Map<String, Integer> testSummary = (Map<String, Integer>) result.get("testSummary");
            // Pre-existing parser uses Map<String, Integer> for testSummary
            assertThat(testSummary.get("total")).isEqualTo(25);
            assertThat(testSummary.get("passed")).isEqualTo(25);
            assertThat(testSummary.get("failed")).isEqualTo(0);
        }

        @Test
        @DisplayName("parses Gradle build failure with task error")
        void parsesBuildFailure() {
            String output = """
                    > Task :compileJava FAILED
                    
                    FAILURE: Build failed with an exception.
                    
                    * What went wrong:
                    Execution failed for task ':compileJava'.
                    
                    BUILD FAILED in 2s
                    """;

            Map<String, Object> result = gradleParser.parse(output, 1, "build");

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(result.get("duration")).asString().startsWith("2");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
            assertThat(errors).isNotEmpty();
        }

        @Test
        @DisplayName("parses Gradle test failures")
        void parsesTestFailures() {
            String output = """
                    > Task :test
                    
                    com.example.UserServiceTest > testLogin() FAILED
                        java.lang.AssertionError at UserServiceTest.java:42
                    
                    10 tests completed, 2 failed
                    
                    BUILD FAILED in 5s
                    """;

            Map<String, Object> result = gradleParser.parse(output, 1, "test");

            assertThat(result.get("success")).isEqualTo(false);

            @SuppressWarnings("unchecked")
            Map<String, Integer> testSummary = (Map<String, Integer>) result.get("testSummary");
            assertThat(testSummary.get("total")).isEqualTo(10);
            assertThat(testSummary.get("failed")).isEqualTo(2);
            assertThat(testSummary.get("passed")).isEqualTo(8);
        }

        @Test
        @DisplayName("parses Gradle deprecation warnings")
        void parsesDeprecationWarnings() {
            String output = """
                    > Task :compileJava
                    warning: [options] source value 8 is obsolete
                                        
                    Deprecated Gradle features were used in this build
                    
                    BUILD SUCCESSFUL in 1s
                    """;

            Map<String, Object> result = gradleParser.parse(output, 0, "compileJava");

            assertThat(result.get("success")).isEqualTo(true);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> warnings = (List<Map<String, Object>>) result.get("warnings");
            assertThat(warnings).isNotEmpty();
        }

        @Test
        @DisplayName("handles empty Gradle output gracefully")
        void handlesEmptyOutput() {
            Map<String, Object> result = gradleParser.parse("", 0, "test");

            assertThat(result.get("tool")).isEqualTo("gradle");
            assertThat(result.get("success")).isEqualTo(false);
            assertThat(result.get("rawOutput")).isEqualTo("");

            @SuppressWarnings("unchecked")
            Map<String, Integer> testSummary = (Map<String, Integer>) result.get("testSummary");
            assertThat(testSummary.get("total")).isEqualTo(0);
        }

        @Test
        @DisplayName("handles null Gradle output gracefully")
        void handlesNullOutput() {
            Map<String, Object> result = gradleParser.parse(null, 0, "test");

            assertThat(result.get("tool")).isEqualTo("gradle");
            assertThat(result.get("success")).isEqualTo(false);
        }

        @Test
        @DisplayName("extracts file location from stack trace")
        void extractsFileLocationFromError() {
            String output = """
                    com.example.UserServiceTest > testLogin() FAILED
                        at com.example.UserServiceTest.testLogin(UserServiceTest.java:42)
                    
                    1 test completed, 1 failed
                    
                    BUILD FAILED in 0s
                    """;

            Map<String, Object> result = gradleParser.parse(output, 1, "test");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
            assertThat(errors).isNotEmpty();

            // The errors list should contain at least test failure information
            boolean hasTestInfo = errors.stream()
                    .anyMatch(e -> e.get("message") != null &&
                            e.get("message").toString().contains("FAILED"));
            assertThat(hasTestInfo).isTrue();
        }
    }
}
