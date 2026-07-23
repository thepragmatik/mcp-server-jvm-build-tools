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
package com.pragmatik.buildtools.tool;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link PromptService}.
 */
class PromptServiceTest {

    private PromptService service;

    @BeforeEach
    void setUp() {
        service = new PromptService();
    }

    // ─── promptBuildAndTest ───────────────────────────────────────────────

    @Test
    void testPromptBuildAndTestNullArgs() {
        String result = service.promptBuildAndTest(null, null);

        assertNotNull(result);
        assertTrue(result.contains("\"promptName\":\"build_and_test\""));
        assertTrue(result.contains("\"version\":\"1.0\""));
        assertTrue(result.contains("Follow these steps"));
        assertTrue(result.contains("detect_build_tool"));
        assertTrue(result.contains("execute_build_command"));
        assertTrue(result.contains("\"template\""));
        assertTrue(result.contains("\"requiredTools\""));
        // Should NOT contain projectDir or buildTool inserts
        assertFalse(result.contains("Project directory:"));
    }

    @Test
    void testPromptBuildAndTestWithProjectDir() {
        String result = service.promptBuildAndTest("/home/user/myproject", null);

        assertNotNull(result);
        assertTrue(result.contains("Project directory: /home/user/myproject"));
        assertTrue(result.contains("\"promptName\":\"build_and_test\""));
        assertFalse(result.contains("Build tool:"));
    }

    @Test
    void testPromptBuildAndTestWithBuildTool() {
        String result = service.promptBuildAndTest(null, "gradle");

        assertNotNull(result);
        assertTrue(result.contains("Build tool: gradle"));
        assertTrue(result.contains("\"promptName\":\"build_and_test\""));
        assertFalse(result.contains("Project directory:"));
    }

    @Test
    void testPromptBuildAndTestWithBothArgs(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<project><modelVersion>4.0.0</modelVersion></project>");

        String result = service.promptBuildAndTest(tempDir.toString(), "maven");

        assertNotNull(result);
        assertTrue(result.contains("Build tool: maven"));
        assertTrue(result.contains("Project directory: " + tempDir.toString()));
        assertTrue(result.contains("build_and_test"));
    }

    @Test
    void testPromptBuildAndTestWithBlankArgs() {
        String result = service.promptBuildAndTest("  ", "");

        assertNotNull(result);
        // Blank strings treated as not-provided
        assertFalse(result.contains("Project directory:"));
        assertFalse(result.contains("Build tool:"));
    }

    // ─── promptDependencyAudit ────────────────────────────────────────────

    @Test
    void testPromptDependencyAuditNullArg() {
        String result = service.promptDependencyAudit(null);

        assertNotNull(result);
        assertTrue(result.contains("\"promptName\":\"dependency_audit\""));
        assertTrue(result.contains("detect_build_tool"));
        assertTrue(result.contains("check_dependency_version"));
        assertTrue(result.contains("\"requiredTools\""));
        assertFalse(result.contains("Project directory:"));
    }

    @Test
    void testPromptDependencyAuditWithProjectDir() {
        String result = service.promptDependencyAudit("/path/to/project");

        assertNotNull(result);
        assertTrue(result.contains("Project directory: /path/to/project"));
        assertTrue(result.contains("dependency_audit"));
        assertTrue(result.contains("MAJOR"));
        assertTrue(result.contains("MINOR"));
        assertTrue(result.contains("PATCH"));
    }

    @Test
    void testPromptDependencyAuditBlankDir() {
        String result = service.promptDependencyAudit("   ");

        assertNotNull(result);
        // Blank should be treated as null (no projectDir insertion)
        assertFalse(result.contains("Project directory:"));
    }

    // ─── promptBuildDiagnosis ────────────────────────────────────────────

    @Test
    void testPromptBuildDiagnosisWithFailedCommand(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<project><modelVersion>4.0.0</modelVersion></project>");

        String result = service.promptBuildDiagnosis(tempDir.toString(), "mvn compile");

        assertNotNull(result);
        assertTrue(result.contains("\"promptName\":\"build_diagnosis\""));
        assertTrue(result.contains(tempDir.toString()));
        assertTrue(result.contains("The failing command was: mvn compile"));
        assertTrue(result.contains("validate_build_configuration"));
        assertTrue(result.contains("detect_build_tool"));
        assertTrue(result.contains("\"requiredTools\""));
    }

    @Test
    void testPromptBuildDiagnosisNullFailedCommand(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<project><modelVersion>4.0.0</modelVersion></project>");

        String result = service.promptBuildDiagnosis(tempDir.toString(), null);

        assertNotNull(result);
        assertTrue(result.contains("build_diagnosis"));
        // Should not contain the "failing command was" line when null
        assertFalse(result.contains("The failing command was"));
    }

    @Test
    void testPromptBuildDiagnosisBlankFailedCommand(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir);
        Files.writeString(
                tempDir.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion></project>");

        String result = service.promptBuildDiagnosis(tempDir.toString(), "  ");

        assertNotNull(result);
        assertFalse(result.contains("The failing command was:"));
    }

    // ─── Edge cases ──────────────────────────────────────────────────────

    @Test
    void testAllPromptsReturnValidJson() {
        String buildAndTest = service.promptBuildAndTest(null, null);
        String depAudit = service.promptDependencyAudit(null);

        // Verify JSON starts with { and ends with }
        assertTrue(buildAndTest.startsWith("{"));
        assertTrue(buildAndTest.endsWith("}"));
        assertTrue(depAudit.startsWith("{"));
        assertTrue(depAudit.endsWith("}"));

        // Verify all contain estimated tokens (non-zero since templates are long)
        assertTrue(buildAndTest.contains("\"estimatedTokens\""));
        assertTrue(depAudit.contains("\"estimatedTokens\""));
    }

    @Test
    void testPromptBuildAndTestIncludesRequiredTools() {
        String result = service.promptBuildAndTest(null, null);

        assertTrue(result.contains("detect_build_tool"));
        assertTrue(result.contains("validate_build_configuration"));
        assertTrue(result.contains("execute_build_command"));
        assertTrue(result.contains("analyze_build_output"));
    }

    @Test
    void testEstimatedTokensPositive() {
        String result = service.promptBuildAndTest(null, null);

        // Estimated tokens should be > 0 (template length / 4)
        assertTrue(result.contains("\"estimatedTokens\":"));

        // Extract the number — it should be > 0
        String tokenStr = result.replaceAll(".*\"estimatedTokens\":([0-9]+).*", "$1");
        if (!tokenStr.equals(result)) {
            assertTrue(Integer.parseInt(tokenStr) > 0, "estimatedTokens should be positive");
        }
    }
}
