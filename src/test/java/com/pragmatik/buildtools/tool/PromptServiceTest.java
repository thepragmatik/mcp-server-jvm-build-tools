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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PromptService}.
 */
class PromptServiceTest {

    private PromptService service;

    @BeforeEach
    void setUp() {
        service = new PromptService();
    }

    // ─── promptBuildAndTest ─────────────────────────────────────────────

    @Test
    void testPromptBuildAndTestNoParams() {
        String result = service.promptBuildAndTest(null, null);
        assertNotNull(result);
        assertTrue(result.contains("\"promptName\":\"build_and_test\""));
        assertTrue(result.contains("\"version\":\"1.0\""));
        assertTrue(result.contains("\"template\""));
        assertTrue(result.contains("detect_build_tool"));
        assertTrue(result.contains("validate_build_configuration"));
        assertTrue(result.contains("execute_build_command"));
        assertTrue(result.contains("analyze_build_output"));
        assertTrue(result.contains("\"requiredTools\""));
    }

    @Test
    void testPromptBuildAndTestWithDir() {
        String result = service.promptBuildAndTest("/home/user/my-project", null);
        assertNotNull(result);
        assertTrue(result.contains("\"promptName\":\"build_and_test\""));
        assertTrue(result.contains("Project directory: /home/user/my-project"));
        assertTrue(result.contains("detect_build_tool"));
    }

    @Test
    void testPromptBuildAndTestWithAllParams() {
        String result = service.promptBuildAndTest("/home/user/my-project", "gradle");
        assertNotNull(result);
        assertTrue(result.contains("\"promptName\":\"build_and_test\""));
        assertTrue(result.contains("Build tool: gradle"));
        assertTrue(result.contains("Project directory: /home/user/my-project"));
    }

    @Test
    void testPromptBuildAndTestWithBlankDir() {
        String result = service.promptBuildAndTest("   ", null);
        assertNotNull(result);
        assertTrue(result.contains("\"promptName\":\"build_and_test\""));
        // Blank dir should be treated as absent — no "Project directory:" prepended
        assertFalse(result.contains("Project directory:"));
    }

    // ─── promptDependencyAudit ──────────────────────────────────────────

    @Test
    void testPromptDependencyAuditNoDir() {
        String result = service.promptDependencyAudit(null);
        assertNotNull(result);
        assertTrue(result.contains("\"promptName\":\"dependency_audit\""));
        assertTrue(result.contains("\"version\":\"1.0\""));
        assertTrue(result.contains("audit and upgrade their project dependencies"));
        assertTrue(result.contains("check_dependency_version"));
        assertTrue(result.contains("\"requiredTools\""));
    }

    @Test
    void testPromptDependencyAuditWithDir() {
        String result = service.promptDependencyAudit("/workspace/spring-app");
        assertNotNull(result);
        assertTrue(result.contains("\"promptName\":\"dependency_audit\""));
        assertTrue(result.contains("Project directory: /workspace/spring-app"));
        assertTrue(result.contains("Classify upgrades"));
    }

    @Test
    void testPromptDependencyAuditWithBlankDir() {
        String result = service.promptDependencyAudit("   ");
        assertNotNull(result);
        assertTrue(result.contains("\"promptName\":\"dependency_audit\""));
        // Blank dir treated as absent
        assertFalse(result.contains("Project directory:"));
    }

    // ─── promptBuildDiagnosis ───────────────────────────────────────────

    @Test
    void testPromptBuildDiagnosisRequiredOnly() {
        String result = service.promptBuildDiagnosis("/home/user/my-project", null);
        assertNotNull(result);
        assertTrue(result.contains("\"promptName\":\"build_diagnosis\""));
        assertTrue(result.contains("\"version\":\"1.0\""));
        assertTrue(result.contains("Project directory: /home/user/my-project"));
        assertTrue(result.contains("validate_build_configuration"));
        assertTrue(result.contains("analyze_build_output"));
    }

    @Test
    void testPromptBuildDiagnosisWithFailedCommand() {
        String result = service.promptBuildDiagnosis("/home/user/my-project", "mvn test");
        assertNotNull(result);
        assertTrue(result.contains("\"promptName\":\"build_diagnosis\""));
        assertTrue(result.contains("The failing command was: mvn test"));
        assertTrue(result.contains("diagnostic workflow"));
    }

    @Test
    void testPromptBuildDiagnosisWithBlankFailedCommand() {
        String result = service.promptBuildDiagnosis("/home/user/my-project", "   ");
        assertNotNull(result);
        assertTrue(result.contains("\"promptName\":\"build_diagnosis\""));
        assertTrue(result.contains("Project directory: /home/user/my-project"));
        // Blank failedCommand should be treated as absent — no "failing command" line
        assertFalse(result.contains("The failing command was:"));
    }

    @Test
    void testPromptBuildDiagnosisEmptyDirEdgeCase() {
        String result = service.promptBuildDiagnosis("", null);
        assertNotNull(result);
        assertTrue(result.contains("\"promptName\":\"build_diagnosis\""));
        // Empty string is still used in the template since projectDir is required
        assertTrue(result.contains("Project directory:"));
    }
}
