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

import com.pragmatik.buildtools.tool.PromptService;
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
        assertTrue(safe.contains("\"category\":\"other\""));
        assertTrue(safe.contains("\"severity\":\"error\""));
        assertTrue(safe.contains("\"diagnostics\":[{"));
    }

    @Test
    void projectsKotlinErrorPrefixWithoutLeakingPrivateText() {
        String output = "{\"exitCode\":1,\"rawOutput\":\"e: unresolved reference: "
                + "/synthetic/private/Sample.kt:42 alice@example.invalid token=SYNTHETIC_SECRET\"}";
        String safe = policy.protect("execute_build_command", output);
        assertTrue(safe.contains("\"category\":\"compilation\""));
        assertTrue(safe.contains("\"severity\":\"error\""));
        assertFalse(safe.contains("/synthetic/private"));
        assertFalse(safe.contains("alice@example.invalid"));
        assertFalse(safe.contains("SYNTHETIC_SECRET"));
    }

    @Test
    void executeStatusUsesAuthoritativeExitCodeOverMisleadingOutput() {
        String raw =
                "{\"exitCode\":1,\"success\":false,\"rawOutput\":\"BUILD SUCCESS /synthetic/private alice@example.invalid\"}";

        String safe = policy.protect("execute_build_command", raw);

        assertTrue(safe.contains("\"success\":false"));
        assertTrue(safe.contains("\"exitCode\":1"));
        assertTrue(safe.contains("\"isError\":true"));
        assertFalse(safe.contains("/synthetic/private"));
        assertFalse(safe.contains("alice@example.invalid"));
    }

    @Test
    void signedExitCodeIsAuthoritativeAndUnknownStatusIsNotInferred() {
        String failed = policy.protect("execute_build_command", "{\"exitCode\":-9,\"rawOutput\":\"BUILD SUCCESS\"}");
        assertTrue(failed.contains("\"exitCode\":-9"));
        assertTrue(failed.contains("\"success\":false"));

        String unknown = policy.protect("execute_build_command", "{\"rawOutput\":\"BUILD FAILURE\"}");
        assertFalse(unknown.contains("\"exitCode\""));
        assertFalse(unknown.contains("\"success\""));
        assertFalse(unknown.contains("\"isError\""));
    }

    @Test
    void unknownPluginExitStatusPreservesExplicitToolError() {
        String safe = policy.protect(
                "execute_build_command", "{\"error\":\"failed at /synthetic/private alice@example.invalid\"}");

        assertTrue(safe.contains("\"isError\":true"));
        assertFalse(safe.contains("\"success\""));
        assertFalse(safe.contains("\"exitCode\""));
        assertFalse(safe.contains("/synthetic/private"));
        assertFalse(safe.contains("alice@example.invalid"));
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
        assertTrue(safe.contains("\"category\":\"other\""));
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
    void decodesJsonQuotedToolListingIntoAllowlistedNamesOnly() {
        String callback =
                "\"maven: validate, install\\ngradle: build\\nsbt: compile\\nprivate-tool: SYNTHETIC SECRET\"";
        String safe = policy.protect("list_build_tools", callback);
        assertTrue(safe.contains("\"tools\":[\"maven\",\"gradle\",\"sbt\"]"));
        assertFalse(safe.contains("install"));
        assertFalse(safe.contains("SYNTHETIC SECRET"));
        assertFalse(safe.contains("private-tool"));
    }

    @Test
    void acceptsDirectAndMalformedListingsWithoutExposingPrivateText() {
        String direct = policy.protect(
                "list_build_tools",
                "sbt: test\nmaven: install\ngradle: build\nprivate-tool: synthetic@example.invalid");
        assertTrue(direct.contains("\"tools\":[\"maven\",\"gradle\",\"sbt\"]"));
        assertFalse(direct.contains("synthetic@example.invalid"));
        assertFalse(direct.contains("install"));

        String malformed =
                policy.protect("list_build_tools", "maven: validate\n{broken-json\ntoken=SYNTHETIC_PRIVATE_CANARY");
        assertTrue(malformed.contains("\"tools\":[\"maven\"]"));
        assertFalse(malformed.contains("SYNTHETIC_PRIVATE_CANARY"));
    }

    @Test
    void objectListingDoesNotInterpretPrivateFieldsAsToolNames() {
        String safe = policy.protect(
                "list_build_tools", "{\"description\":\"maven: private\",\"email\":\"synthetic@example.invalid\"}");
        assertTrue(safe.contains("\"tools\":[]"));
        assertFalse(safe.contains("synthetic@example.invalid"));
        assertFalse(safe.contains("private"));
    }

    @Test
    void listingUsesBoundedTailAndReportsTruncation() {
        String output = "maven: hidden\n" + "x".repeat(260_000) + "\nsbt: test";
        String safe = policy.protect("list_build_tools", output);
        assertTrue(safe.contains("\"truncated\":true"));
        assertTrue(safe.contains("\"tools\":[\"sbt\"]"));
        assertFalse(safe.contains("hidden"));
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
    void redactsQuotedSecretsAndPathsWithSpaces() {
        String output = "ERROR password = \"SYNTHETIC SECRET WITH SPACES\" at "
                + "/tmp/SYNTHETIC PRIVATE NAME/project/src/Main.java";
        String safe = policy.protect("execute_build_command", output);
        assertFalse(safe.contains("SECRET WITH SPACES"));
        assertFalse(safe.contains("PRIVATE NAME"));
        assertTrue(safe.contains("\"diagnostics\":[{"));
    }

    @Test
    void redactsQuotedPathWithoutKnownExtension() {
        String safe = policy.protect("execute_build_command", "ERROR at '/tmp/SYNTHETIC PRIVATE NAME/project'");
        assertFalse(safe.contains("PRIVATE NAME"));
    }

    @Test
    void redactsUnquotedSecretAndExtensionlessPathWithSpaces() {
        String output =
                "ERROR password = SYNTHETIC SECRET WITH SPACES\n" + "ERROR /tmp/SYNTHETIC PRIVATE NAME/cache/output";
        String safe = policy.protect("execute_build_command", output);
        assertFalse(safe.contains("SECRET WITH SPACES"));
        assertFalse(safe.contains("PRIVATE NAME"));
        assertTrue(safe.contains("\"diagnostics\":[{"));
    }

    @Test
    void preservesServerAuthoredPromptWithoutEchoingUserInput() {
        String raw = new PromptService()
                .promptBuildDiagnosis("/tmp/SYNTHETIC PRIVATE NAME/project", "SECRET SYNTHETIC COMMAND");
        String safe = policy.protect("prompt_build_diagnosis", raw);
        assertTrue(safe.contains("Follow this diagnostic workflow"));
        assertFalse(safe.contains("PRIVATE NAME"));
        assertFalse(safe.contains("SYNTHETIC COMMAND"));
    }

    @Test
    void preservesResourceSummaryWithoutProjectIdentity() {
        String raw = """
                {"project":"SYNTHETIC PRIVATE NAME","projectDir":"/tmp/SYNTHETIC PRIVATE NAME",
                 "detectedTool":"mixed","resourceCount":2,
                 "resources":[{"uri":"build://SYNTHETIC PRIVATE NAME/dependencies/maven","buildTool":"maven"},
                              {"uri":"build://SYNTHETIC PRIVATE NAME/dependencies/gradle","buildTool":"gradle"}]}
                """;
        String safe = policy.protect("list_dependency_resources", raw);
        assertTrue(safe.contains("\"availableBuildTools\":[\"maven\",\"gradle\"]"));
        assertTrue(safe.contains("\"resourceCount\":2"));
        assertFalse(safe.contains("PRIVATE NAME"));
    }

    @Test
    void preservesFailureStateWithoutPrivateErrorText() {
        String safe = policy.protect(
                "list_dependency_resources",
                "{\"success\":false,\"error\":\"failed at /tmp/SYNTHETIC PRIVATE NAME/project\"}");
        assertTrue(safe.contains("\"isError\":true"));
        assertFalse(safe.contains("PRIVATE NAME"));
    }

    @Test
    void projectsDependencyAndPerformanceResultsToUsefulCounts() {
        String dependencies = policy.protect(
                "analyze_pom_dependencies",
                "{\"project\":{\"artifactId\":\"SYNTHETIC PRIVATE NAME\"},\"dependencies\":[{},{}],\"managedDependencies\":[{}]}");
        assertTrue(dependencies.contains("\"dependencyCount\":2"));
        assertTrue(dependencies.contains("\"managedDependencyCount\":1"));
        assertFalse(dependencies.contains("PRIVATE NAME"));

        String profile = policy.protect(
                "profile_build",
                "{\"success\":true,\"durationSeconds\":1.25,\"phaseCount\":3,\"projectDir\":\"/tmp/SYNTHETIC PRIVATE NAME\"}");
        assertTrue(profile.contains("\"durationSeconds\":1.25"));
        assertTrue(profile.contains("\"phaseCount\":3"));
        assertFalse(profile.contains("PRIVATE NAME"));
    }

    @Test
    void boundsLargeResultsAndKeepsFinalDiagnostic() {
        String output = "x".repeat(300_000) + "\nERROR /home/private-user/File.java";
        String safe = policy.protect("execute_build_command", output);
        assertTrue(safe.contains("\"truncated\":true"));
        assertTrue(safe.contains("\"category\":\"other\""));
        assertFalse(safe.contains("private-user"));
        assertTrue(safe.length() < 1_000);
    }
}
