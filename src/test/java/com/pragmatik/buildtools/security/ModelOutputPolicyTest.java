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
package com.pragmatik.buildtools.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModelOutputPolicyTest {
    private final ModelOutputPolicy policy = new ModelOutputPolicy();

    @Test
    void suppressesPrivateDataInPlainBuildOutput() {
        String output =
                "ERROR /home/private-user/App.java user=test.user@example.invalid token=secret-value phone=+61 400 123 456";
        String safe = policy.protect("execute_build_command", output);
        assertFalse(safe.contains("test.user"));
        assertFalse(safe.contains("/home/private-user"));
        assertFalse(safe.contains("secret-value"));
        assertFalse(safe.contains("400 123 456"));
        assertTrue(safe.contains("[redacted-path]"));
        assertTrue(safe.contains("[redacted-email]"));
        assertTrue(safe.contains("[redacted-secret]"));
    }

    @Test
    void preservesOnlyApprovedAggregateFields() {
        String output = """
                {"success":false,"errorCount":2,"testSummary":{"total":4,"failed":1},
                 "errors":[{"file":"/home/private-user/App.java","message":"test.user@example.invalid"}],
                 "token":"secret-value","accountNumber":123456789}
                """;
        String safe = policy.protect("analyze_build_output", output);
        assertTrue(safe.contains("\"errorCount\":2"));
        assertTrue(safe.contains("\"failed\":1"));
        assertFalse(safe.contains("private-user"));
        assertFalse(safe.contains("example.invalid"));
        assertFalse(safe.contains("secret-value"));
        assertFalse(safe.contains("123456789"));
        assertTrue(safe.contains("[redacted-email]"));
    }

    @Test
    void preservesSafeBuildToolDetectionWithoutProjectPath() {
        String output = """
                {"projectDir":"/home/private-user/work","detectedTools":["maven","unknown-private"],
                 "toolCount":1,"status":"success"}
                """;
        String safe = policy.protect("detect_build_tool", output);
        assertTrue(safe.contains("\"detectedTools\":[\"maven\"]"));
        assertFalse(safe.contains("private-user"));
        assertFalse(safe.contains("unknown-private"));
    }

    @Test
    void extractsVersionWithoutRuntimeEnvironmentDetails() {
        String output = "Apache Maven 3.9.16\nJava home: /home/private-user/jdk";
        String safe = policy.protect("get_build_tool_version", output);
        assertTrue(safe.contains("\"version\":\"3.9.16\""));
        assertFalse(safe.contains("private-user"));
    }

    @Test
    void malformedOutputDoesNotEscape() {
        String safe = policy.protect("execute_build_command", "{secret-value");
        assertFalse(safe.contains("secret-value"));
    }

    @Test
    void boundsLargeResultsAndKeepsFinalDiagnostic() {
        String output = "x".repeat(300_000) + "\nERROR /home/private-user/File.java";
        String safe = policy.protect("execute_build_command", output);
        assertTrue(safe.contains("\"truncated\":true"));
        assertTrue(safe.contains("[redacted-path]"));
        assertFalse(safe.contains("private-user"));
        assertTrue(safe.length() < 1_000);
    }
}
