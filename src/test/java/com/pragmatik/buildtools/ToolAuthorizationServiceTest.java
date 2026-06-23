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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for ToolPermission, ToolAuditLogger, and ToolAuthorizationService.
 */
class ToolAuthorizationServiceTest {

    private ToolAuthorizationService service;

    @BeforeEach
    void setUp() {
        // Clear any existing API key properties to ensure clean state
        System.clearProperty("buildtools.auth.enabled");
        System.clearProperty("buildtools.auth.mode");
        System.clearProperty("buildtools.audit.enabled");
        service = new ToolAuthorizationService();
    }

    // === ToolPermission enum tests ===

    @Test
    void testAllScopesHaveToolCoverage() {
        List<String> allScopes = ToolPermission.allScopes();
        assertEquals(12, allScopes.size(), "Should have 12 scopes");
        assertTrue(allScopes.contains("build:read"));
        assertTrue(allScopes.contains("build:execute"));
        assertTrue(allScopes.contains("build:profile"));
        assertTrue(allScopes.contains("dependency:read"));
        assertTrue(allScopes.contains("dependency:manage"));
    }

    @Test
    void testFromScopeValidAndInvalid() {
        assertEquals(ToolPermission.BUILD_READ, ToolPermission.fromScope("build:read"));
        assertEquals(ToolPermission.BUILD_EXECUTE, ToolPermission.fromScope("build:execute"));
        assertNull(ToolPermission.fromScope("nonexistent:scope"));
        assertNull(ToolPermission.fromScope(null));
        assertNull(ToolPermission.fromScope(""));
    }

    @Test
    void testWildcardAuthorizesEverything() {
        assertTrue(ToolPermission.isToolAuthorized("execute_build_command", List.of("*")));
        assertTrue(ToolPermission.isToolAuthorized("nonexistent_tool", List.of("*")));
        assertTrue(ToolPermission.isToolAuthorized("check_dependency_version", List.of("*")));
    }

    @Test
    void testEmptyScopesDenyEverything() {
        assertFalse(ToolPermission.isToolAuthorized("execute_build_command", List.of()));
        assertFalse(ToolPermission.isToolAuthorized("get_build_tool_version", null));
    }

    @Test
    void testSpecificScopeAuthorization() {
        // build:read covers detection tools
        assertTrue(ToolPermission.isToolAuthorized("detect_build_tool", List.of("build:read")));
        assertTrue(ToolPermission.isToolAuthorized("get_build_tool_version", List.of("build:read")));
        assertTrue(ToolPermission.isToolAuthorized("list_build_tools", List.of("build:read")));
        assertTrue(ToolPermission.isToolAuthorized("validate_build_configuration", List.of("build:read")));

        // build:read does NOT cover execute
        assertFalse(ToolPermission.isToolAuthorized("execute_build_command", List.of("build:read")));
    }

    @Test
    void testBuildExecuteScope() {
        assertTrue(ToolPermission.isToolAuthorized("execute_build_command", List.of("build:execute")));
        assertFalse(ToolPermission.isToolAuthorized("get_build_tool_version", List.of("build:execute")));
    }

    @Test
    void testMultipleScopesGrantAccess() {
        // With build:read + dependency:read, both should work
        assertTrue(ToolPermission.isToolAuthorized("detect_build_tool", List.of("build:read", "dependency:read")));
        assertTrue(
                ToolPermission.isToolAuthorized("check_dependency_version", List.of("build:read", "dependency:read")));
        assertFalse(ToolPermission.isToolAuthorized("execute_build_command", List.of("build:read", "dependency:read")));
    }

    @Test
    void testBuildProfileScope() {
        assertTrue(ToolPermission.isToolAuthorized("profile_build", List.of("build:profile")));
        assertTrue(ToolPermission.isToolAuthorized("analyze_build_performance", List.of("build:profile")));
    }

    @Test
    void testCredentialReadScope() {
        assertTrue(ToolPermission.isToolAuthorized("check_credential_status", List.of("credential:read")));
    }

    @Test
    void testJavaReadScope() {
        assertTrue(ToolPermission.isToolAuthorized("check_java_compatibility", List.of("java:read")));
    }

    @Test
    void testResourceReadScope() {
        assertTrue(ToolPermission.isToolAuthorized("list_build_resources", List.of("resource:read")));
        assertTrue(ToolPermission.isToolAuthorized("read_build_resource", List.of("resource:read")));
    }

    @Test
    void testPromptReadScope() {
        assertTrue(ToolPermission.isToolAuthorized("get_build_tool_prompt", List.of("prompt:read")));
    }

    // === ToolAuthorizationService tests ===

    @Test
    void testCheckToolAuthorizationWithWildcard() {
        String result = service.checkToolAuthorization("execute_build_command", "*");
        assertTrue(result.contains("\"authorized\":true"));
        assertTrue(result.contains("\"explanation\""));
        assertTrue(result.contains("Wildcard"));
    }

    @Test
    void testCheckToolAuthorizationDenied() {
        String result = service.checkToolAuthorization("execute_build_command", "dependency:read");
        assertTrue(result.contains("\"authorized\":false"));
    }

    @Test
    void testCheckToolAuthorizationSpecificScope() {
        String result = service.checkToolAuthorization("detect_build_tool", "build:read");
        assertTrue(result.contains("\"authorized\":true"));
        assertTrue(result.contains("build:read"));
    }

    @Test
    void testCheckToolAuthorizationEmptyScopes() {
        String result = service.checkToolAuthorization("get_build_tool_version", "");
        assertTrue(result.contains("\"authorized\":false"));
        assertTrue(result.contains("No scopes granted"));
    }

    @Test
    void testListAvailableScopes() {
        String result = service.listAvailableScopes();
        assertTrue(result.contains("\"totalScopes\""));
        assertTrue(result.contains("build:read"));
        assertTrue(result.contains("build:execute"));
        assertTrue(result.contains("dependency:read"));
        assertTrue(result.contains("\"recommendations\""));
    }

    @Test
    void testValidateAccessTokenDefaultKey() {
        // The default dev key (if no env vars set) should validate
        String result = service.validateAccessToken("dev-key-unsafe-do-not-use-in-production");
        assertTrue(result.contains("\"valid\":true"));
        assertTrue(result.contains("\"identity\":\"default\""));
        assertTrue(result.contains("\"hasWildcard\":true"));
    }

    @Test
    void testValidateAccessTokenInvalid() {
        String result = service.validateAccessToken("nonexistent-key-12345");
        assertTrue(result.contains("\"valid\":false"));
        assertTrue(result.contains("Token not recognized"));
    }

    @Test
    void testValidateAccessTokenEmpty() {
        String result = service.validateAccessToken("");
        assertTrue(result.contains("\"valid\":false"));
        assertTrue(result.contains("empty"));
    }

    @Test
    void testAuditToolAccessWhenEnabled(@TempDir Path tempDir) throws Exception {
        // Set up audit logging to a temp file
        Path auditFile = tempDir.resolve("audit.log");
        System.setProperty("buildtools.audit.path", auditFile.toString());
        System.setProperty("buildtools.audit.enabled", "true");

        // Create a fresh service with temp audit path
        ToolAuthorizationService testService = new ToolAuthorizationService();

        // Make a few tool calls to generate audit entries
        testService.checkToolAuthorization("detect_build_tool", "build:read");
        testService.checkToolAuthorization("execute_build_command", "dependency:read");
        testService.listAvailableScopes();

        // Read audit entries
        String result = testService.auditToolAccess(10, "all");
        assertTrue(result.contains("\"entryCount\""));
        // Should have at least 3 entries
        assertTrue(result.contains("\"check_tool_authorization\"") || result.contains("\"list_available_scopes\""));
    }

    @Test
    void testAuditToolAccessDisabled() {
        System.setProperty("buildtools.audit.enabled", "false");
        ToolAuthorizationService disabledService = new ToolAuthorizationService();
        String result = disabledService.auditToolAccess(10, "all");
        assertTrue(result.contains("\"auditEnabled\":false"));
        assertTrue(result.contains("disabled"));
    }

    // === ToolAuditLogger direct tests ===

    @Test
    void testAuditLoggerRecordAndTail(@TempDir Path tempDir) throws Exception {
        Path logPath = tempDir.resolve("test-audit.log");
        ToolAuditLogger logger = new ToolAuditLogger(logPath.toString(), true);

        logger.record("tool_a", "agent-1", List.of("build:read"), true, 42);
        logger.record("tool_b", "agent-1", List.of("build:execute"), true, 150);
        logger.record("tool_c", "agent-2", List.of("dependency:read"), false, 5);

        List<String> entries = logger.tail(10);
        assertEquals(3, entries.size());
        assertTrue(entries.get(0).contains("tool_a"));
        assertTrue(entries.get(1).contains("tool_b"));
        assertTrue(entries.get(2).contains("tool_c"));
    }

    @Test
    void testAuditLoggerDisabled() {
        ToolAuditLogger logger = new ToolAuditLogger("/tmp/nonexistent/audit.log", false);
        assertFalse(logger.isEnabled());
        logger.record("test_tool", "test", List.of("*"), true, 0);
        assertTrue(logger.tail(10).isEmpty());
    }

    @Test
    void testAuditLoggerTailEmpty() {
        ToolAuditLogger logger = new ToolAuditLogger("/tmp/nonexistent-dir/audit.log", true);
        assertTrue(logger.tail(10).isEmpty());
    }
}
