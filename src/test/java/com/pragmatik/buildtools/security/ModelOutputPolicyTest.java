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
    void validationIssuesUseFiniteTemplatesWithoutPrivateText() {
        String output = """
                {"valid":false,"tool":"maven","issueCount":2,
                 "projectDir":"/synthetic/private/user@example.invalid",
                 "issues":[
                   {"severity":"ERROR","path":"/synthetic/private/pom.xml",
                    "message":"Missing required element: <artifactId>",
                    "suggestion":"token=SYNTHETIC_SECRET"},
                   {"severity":"WARNING","message":"user@example.invalid SYNTHETIC_SECRET"}
                 ]}
                """;

        String safe = policy.protect("validate_build_configuration", output);

        assertTrue(safe.contains("\"valid\":false"));
        assertTrue(safe.contains("\"issueCount\":2"));
        assertTrue(safe.contains("\"category\":\"configuration\""));
        assertTrue(safe.contains("Required POM artifactId is missing"));
        assertFalse(safe.contains("SYNTHETIC_SECRET"));
        assertFalse(safe.contains("user@example.invalid"));
        assertFalse(safe.contains("/synthetic/private"));
    }

    @Test
    void inaccessibleProjectUsesFixedValidationDiagnostic() {
        String output = """
                {"valid":false,"tool":null,"projectDir":"/synthetic/private/user@example.invalid",
                 "error":"Cannot access project directory","issueCount":1,
                 "issues":[{"severity":"ERROR","path":"projectDir",
                            "message":"Cannot access project directory"}]}
                """;

        String safe = policy.protect("validate_build_configuration", output);

        assertTrue(safe.contains("Project directory cannot be accessed locally."));
        assertTrue(safe.contains("\"issueCount\":1"));
        assertFalse(safe.contains("user@example.invalid"));
        assertFalse(safe.contains("/synthetic/private"));
    }

    @Test
    void versionSecurityProjectionIsAggregateAndFailClosed() {
        String complete = policy.protect("check_dependency_version", """
                {"latestVersion":"1.2.3","security":{"lookupStatus":"complete","cveCount":2,
                 "highestSeverity":"UNKNOWN","vulnerabilities":[{"id":"SYNTHETIC_SECRET",
                 "summary":"private.user@example.invalid"}]}}
                """);
        assertTrue(complete.contains("\"securityStatus\":\"complete\""));
        assertTrue(complete.contains("\"cveCount\":2"));
        assertTrue(complete.contains("\"highestSeverity\":\"UNKNOWN\""));
        assertFalse(complete.contains("SYNTHETIC_SECRET"));
        assertFalse(complete.contains("private.user@example.invalid"));

        for (String security : new String[] {
            "{\"lookupStatus\":\"incomplete\",\"cveCount\":0,\"highestSeverity\":\"NONE\"}",
            "{\"lookupStatus\":\"complete\",\"cveCount\":-1,\"highestSeverity\":\"HIGH\"}"
        }) {
            String safe = policy.protect("check_dependency_version", "{\"security\":" + security + "}");
            assertTrue(safe.contains("\"securityStatus\":\"incomplete\""));
            assertFalse(safe.contains("\"cveCount\""));
            assertFalse(safe.contains("\"highestSeverity\""));
        }
        assertFalse(policy.protect("check_dependency_version", "{\"latestVersion\":\"1.2.3\"}")
                .contains("securityStatus"));
        String oversized =
                policy.protect("check_dependency_version", "x".repeat(256_001) + "{\"security\":{\"cveCount\":0}}");
        assertTrue(oversized.contains("\"metadataStatus\":\"incomplete\""));
        assertTrue(oversized.contains("\"isError\":true"));
        assertFalse(oversized.contains("\"cveCount\""));
    }

    @Test
    void versionFieldsWithTokenShapedPrereleaseTextStayPrivate() {
        String token = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890";
        String raw = "{\"currentVersion\":\"1.2.3-" + token + "\",\"latestVersion\":\"2.0.0\"}";
        String safe = policy.protect("check_dependency_version", raw);
        assertFalse(safe.contains(token));
        assertFalse(safe.contains("currentVersion"));
        assertTrue(safe.contains("\"latestVersion\":\"2.0.0\""));
    }

    @Test
    void osvPresenceAndIncompleteStatusStayVisibleWithoutPrivateIdentity() {
        String raw = """
                {"scanStatus":"severity_unknown","severityUnknown":true,
                 "scanSummary":{"totalDeps":1,"vulnerableDeps":1},
                 "vulnerabilities":[{"dependency":"private.user@example.invalid:secret:1",
                 "cves":[{"id":"SYNTHETIC_SECRET","severity":"UNKNOWN"}]}]}
                """;
        String safe = policy.protect("scan_dependency_cves", raw);
        assertTrue(safe.contains("\"scanStatus\":\"severity_unknown\""));
        assertTrue(safe.contains("\"severityUnknown\":true"));
        assertTrue(safe.contains("\"vulnerableDeps\":1"));
        assertFalse(safe.contains("\"highCount\""));
        assertFalse(safe.contains("SYNTHETIC_SECRET"));
        assertFalse(safe.contains("private.user@example.invalid"));

        String incomplete =
                policy.protect("scan_dependency_cves", "{\"error\":\"Dependency vulnerability scan incomplete\"}");
        assertTrue(incomplete.contains("\"scanStatus\":\"incomplete\""));
        assertTrue(incomplete.contains("\"isError\":true"));
        assertFalse(incomplete.contains("\"vulnerableDeps\""));
    }

    @Test
    void oversizedOrMalformedCveScanCannotLookComplete() {
        String oversized = "x".repeat(256_001)
                + "{\"scanStatus\":\"complete\",\"scanSummary\":{\"totalDeps\":1,\"vulnerableDeps\":0}}";
        for (String raw : new String[] {oversized, "{malformed", "{}"}) {
            String safe = policy.protect("scan_dependency_cves", raw);
            assertTrue(safe.contains("\"scanStatus\":\"incomplete\""));
            assertTrue(safe.contains("\"isError\":true"));
            assertFalse(safe.contains("\"vulnerableDeps\""));
        }
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
