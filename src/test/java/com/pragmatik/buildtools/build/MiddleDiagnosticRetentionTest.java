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

import com.pragmatik.buildtools.gradle.GradleBuildTool;
import com.pragmatik.buildtools.sbt.SbtBuildTool;
import com.pragmatik.buildtools.security.ModelOutputPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class MiddleDiagnosticRetentionTest {
    @TempDir
    Path project;

    @Test
    @Timeout(30)
    void gradleFailureKeepsSoleMiddleDiagnostic() throws Exception {
        Path binary = executable(
                "gradle",
                "error: cannot find symbol /private/synthetic/Sample.java:42 email=ava@example.invalid token=synthetic-token-value");

        BuildExecutionResult result =
                new GradleBuildTool().executeForMcp(project.toString(), project.toString(), "build");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.output()).contains("cannot find symbol");
        assertThat(result.output().length()).isLessThan(160_000);
        assertModelProjections("gradle", project.toString(), "build", result);
    }

    @Test
    @Timeout(30)
    void sbtFailureKeepsSoleMiddleDiagnostic() throws Exception {
        Path binary = executable(
                "sbt",
                "[error] /private/synthetic/Sample.scala:42: cannot find symbol email=ava@example.invalid token=synthetic-token-value");

        BuildExecutionResult result =
                new SbtBuildTool().executeForMcp(binary.getParent().toString(), project.toString(), "compile");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.output()).contains("cannot find symbol");
        assertThat(result.output().length()).isLessThan(160_000);
        assertModelProjections("sbt", binary.getParent().toString(), "compile", result);
    }

    @Test
    void escapedOutputCannotDisplaceFrontDiagnosticAtEitherPrivateBound() {
        String diagnostic = "error: cannot find symbol\n";
        String output = diagnostic + "\\\"".repeat(250_000) + "\ntail";
        BuildExecutionResult result = new BuildExecutionResult(output, 1, true, false);
        String privateJson = BuildToolsService.boundedMcpExecutionResult(result);
        String visible = new ModelOutputPolicy().protect("execute_build_command", privateJson);
        assertThat(privateJson.length()).isLessThanOrEqualTo(BuildResultLimits.MAX_PRIVATE_EXECUTION_ENVELOPE_CHARS);
        assertThat(privateJson).contains("cannot find symbol");
        assertThat(visible).contains("\"category\":\"compilation\"");
        assertThat(visible).doesNotContain("tail");
    }

    @Test
    void privateProjectionBoundaryMatrixRetainsFrontCandidate() {
        ModelOutputPolicy policy = new ModelOutputPolicy();
        for (int size : new int[] {239_000, 240_500, 255_999, 256_001}) {
            String raw = "error: cannot find symbol\n" + "x".repeat(size);
            String privateResult = BuildToolsService.boundedMcpExecutionResult(new BuildExecutionResult(raw, 1, false));
            assertThat(privateResult.length())
                    .isLessThanOrEqualTo(BuildResultLimits.MAX_PRIVATE_EXECUTION_ENVELOPE_CHARS);
            var privateJson = new JsonMapper().readTree(privateResult);
            assertThat(privateJson.path("diagnosticsTruncated").asBoolean()).isEqualTo(size >= 240_500);
            String visible = policy.protect("execute_build_command", privateResult);
            assertThat(visible).contains("\"category\":\"compilation\"");
            assertThat(visible).doesNotContain("x".repeat(1_000));
        }
    }

    @Test
    void escapeHeavyCandidatesCanBeClippedButSignalIncompleteDiagnosis() {
        String first = "error: cannot find symbol\n";
        String expansion = "error: " + String.valueOf((char) 0).repeat(2_000) + "\n";
        String raw = first + expansion.repeat(12) + "x".repeat(200_000);
        String privateResult = BuildToolsService.boundedMcpExecutionResult(new BuildExecutionResult(raw, 1, false));
        var privateJson = new JsonMapper().readTree(privateResult);
        assertThat(privateJson.path("outputTruncated").asBoolean()).isTrue();
        assertThat(privateJson.path("diagnosticsTruncated").asBoolean()).isTrue();
        assertThat(privateResult.length()).isLessThanOrEqualTo(BuildResultLimits.MAX_PRIVATE_EXECUTION_ENVELOPE_CHARS);
        String visible = new ModelOutputPolicy().protect("execute_build_command", privateResult);
        assertThat(visible).contains("\"category\":\"compilation\"");
        assertThat(visible).contains("\"diagnosticsTruncated\":true");
    }

    private void assertModelProjections(String tool, String home, String command, BuildExecutionResult result) {
        ModelOutputPolicy policy = new ModelOutputPolicy();
        String visibleExecution =
                policy.protect("execute_build_command", BuildToolsService.boundedMcpExecutionResult(result));
        var executed = new JsonMapper().readTree(visibleExecution);
        assertThat(executed.path("exitCode").asInt()).isEqualTo(1);
        assertThat(executed.path("outputTruncated").asBoolean()).isTrue();
        assertThat(executed.path("diagnostics").get(0).path("category").asText())
                .isEqualTo("compilation");
        assertThat(visibleExecution)
                .doesNotContain("ava@example.invalid", "synthetic-token-value", "/private/synthetic");

        String visibleAnalysis = policy.protect(
                "analyze_build_output",
                new BuildToolsService(new BuildToolProvider())
                        .analyzeBuildOutput(tool, home, project.toString(), command));
        var analyzed = new JsonMapper().readTree(visibleAnalysis);
        assertThat(analyzed.path("success").asBoolean()).isFalse();
        assertThat(analyzed.path("outputTruncated").asBoolean()).isTrue();
        assertThat(analyzed.path("diagnostics").get(0).path("category").asText())
                .isEqualTo("compilation");
        assertThat(visibleAnalysis)
                .doesNotContain("ava@example.invalid", "synthetic-token-value", "/private/synthetic");
    }

    private Path executable(String name, String diagnostic) throws Exception {
        Path bin = Files.createDirectories(project.resolve("bin"));
        Path executable = bin.resolve(name);
        String filler = "dd if=/dev/zero bs=1048576 count=14 2>/dev/null | tr '\\000' x\n";
        Files.writeString(
                executable, "#!/bin/sh\n" + filler + "printf '\\n" + diagnostic + "\\n'\n" + filler + "exit 1\n");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return executable;
    }
}
