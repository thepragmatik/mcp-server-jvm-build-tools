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
package com.pragmatik.buildtools.cicd;

import com.pragmatik.buildtools.cicd.CiCdTarget.ValidationResult;
import com.pragmatik.buildtools.cicd.PipelineShapeDetector.Shape;
import com.pragmatik.buildtools.tool.JsonUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP service exposing CI/CD flow interpretation and validation tools.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@code interpret_ci_flow} — natural-language CI/CD description → GitHub Actions YAML</li>
 *   <li>{@code validate_ci_flow} — validate a workflow YAML for correctness</li>
 * </ul>
 */
@Service
public class CiCdFlowService {

    private final GitHubActionsGenerator generator;

    public CiCdFlowService(GitHubActionsGenerator generator) {
        this.generator = generator;
    }

    /**
     * Interpret a natural-language CI/CD pipeline description and generate a GitHub Actions
     * workflow YAML configuration.
     *
     * <p>Automatically detects the build tool (Maven, Gradle, SBT) from the project directory
     * and generates build-tool-specific commands and caching configuration.
     */
    @Tool(
            name = "interpret_ci_flow",
            description = "Interpret a natural-language CI/CD pipeline description and generate a "
                    + "GitHub Actions workflow YAML. Detects the build tool (Maven, Gradle, SBT) "
                    + "from the project directory. Returns JSON with the generated YAML, validation "
                    + "status, detected build tool, and pipeline summary.")
    public String interpretCiFlow(
            @ToolParam(
                            required = true,
                            description = "Natural language description of the CI/CD pipeline. "
                                    + "Examples: 'Run tests on every push to main', "
                                    + "'Build on PR, deploy on tag', "
                                    + "'Set up CI that runs on push and PR'")
                    String description,
            @ToolParam(
                            required = false,
                            description = "Project directory path for build-tool auto-detection. "
                                    + "Scans for pom.xml (Maven), build.gradle/build.gradle.kts (Gradle), "
                                    + "or build.sbt (SBT).")
                    String projectDir,
            @ToolParam(
                            required = false,
                            description = "CI/CD target platform. Currently only 'github-actions' is supported. "
                                    + "Default: 'github-actions'.")
                    String target,
            @ToolParam(
                            required = false,
                            description = "Build tool name override. One of: 'maven', 'gradle', 'sbt'. "
                                    + "When set, skips auto-detection from projectDir.")
                    String buildToolName,
            @ToolParam(
                            required = false,
                            description = "Pipeline shape hint. One of: 'ci-push', 'ci-pr', "
                                    + "'ci-release', 'ci-full', 'custom'. "
                                    + "When set, overrides automatic shape detection from the description.")
                    String pipelineShape,
            @ToolParam(
                            required = false,
                            description = "JDK versions to test against, comma-separated. "
                                    + "Examples: '21', '21,23', '17,21,23'. "
                                    + "Default: '21'.")
                    String javaVersions,
            @ToolParam(
                            required = false,
                            description = "Additional configuration overrides as JSON. "
                                    + "Examples: {\"branches\":[\"main\",\"develop\"],\"timeout\":45}")
                    String configOverrides) {

        // Validate input
        if (description == null || description.isBlank()) {
            return JsonUtils.errorJson("CI description is required");
        }

        // Validate target
        String resolvedTarget = (target != null && !target.isBlank()) ? target : "github-actions";
        if (!"github-actions".equals(resolvedTarget)) {
            return JsonUtils.errorJson("Unknown target '" + resolvedTarget + "'. Supported: github-actions");
        }

        // Detect or use explicit build tool
        String buildTool = buildToolName;
        if (buildTool == null && projectDir != null && !projectDir.isBlank()) {
            buildTool = PipelineShapeDetector.detectBuildTool(projectDir);
        }

        // Detect or use explicit pipeline shape
        Shape shape;
        if (pipelineShape != null && !pipelineShape.isBlank()) {
            shape = switch (pipelineShape.toLowerCase()) {
                case "ci-push" -> Shape.CI_PUSH;
                case "ci-pr" -> Shape.CI_PR;
                case "ci-release" -> Shape.CI_RELEASE;
                case "ci-full" -> Shape.CI_FULL;
                case "custom" -> Shape.CUSTOM;
                default -> PipelineShapeDetector.detect(description);
            };
        } else {
            shape = PipelineShapeDetector.detect(description);
        }

        // Parse JDK versions
        List<String> jdkList = null;
        if (javaVersions != null && !javaVersions.isBlank()) {
            jdkList = Arrays.stream(javaVersions.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }

        // Parse config overrides
        Map<String, Object> overrides = null;
        if (configOverrides != null && !configOverrides.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = JsonUtils.parseJson(configOverrides);
                overrides = parsed;
            } catch (Exception e) {
                return JsonUtils.errorJson("Invalid configOverrides JSON: " + e.getMessage());
            }
        }

        // Build pipeline model
        CiCdPipeline pipeline = PipelineShapeDetector.buildPipeline(description, shape, buildTool, jdkList, overrides);

        // Generate YAML
        String yaml;
        try {
            yaml = generator.generate(pipeline);
        } catch (Exception e) {
            return JsonUtils.errorJson("Failed to generate CI/CD configuration: " + e.getMessage());
        }

        // Validate generated YAML
        ValidationResult validation = generator.validate(yaml);

        // Build result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pipelineName", pipeline.name());
        result.put("target", generator.name());
        result.put("detectedBuildTool", buildTool != null ? buildTool : "unknown");
        result.put(
                "validation",
                Map.of(
                        "valid",
                        validation.valid(),
                        "warnings",
                        validation.errorMessage() != null ? List.of(validation.errorMessage()) : List.of()));

        // Pipeline summary
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("triggers", describeTriggers(pipeline.on()));
        summary.put(
                "jobs",
                pipeline.jobs().stream()
                        .map(j -> {
                            Map<String, Object> jm = new LinkedHashMap<>();
                            jm.put("name", j.name());
                            jm.put("runsOn", j.runsOn());
                            if (j.strategy() != null && j.strategy().java() != null) {
                                jm.put("javaVersions", j.strategy().java());
                            }
                            if (!j.needs().isEmpty()) {
                                jm.put("needs", j.needs());
                            }
                            return jm;
                        })
                        .toList());
        summary.put(
                "totalSteps",
                pipeline.jobs().stream().mapToInt(j -> j.steps().size()).sum());
        result.put("pipelineSummary", summary);

        result.put("config", yaml);
        result.put("suggestedPath", ".github/workflows/ci.yml");

        return JsonUtils.toJson(result);
    }

    /**
     * Validate a GitHub Actions workflow YAML for syntax correctness and required field presence.
     *
     * <p>Checks YAML validity, required fields (name, on, runs-on, steps), trigger validity,
     * step name uniqueness, and action reference format. Does NOT execute the workflow —
     * read-only static analysis.
     */
    @Tool(
            name = "validate_ci_flow",
            description = "Validate a GitHub Actions workflow YAML for syntax correctness and required "
                    + "field presence. Checks that the YAML has valid structure with name, trigger (on), "
                    + "runs-on, and steps. Returns JSON with valid flag and error details.")
    public String validateCiFlow(
            @ToolParam(
                            required = true,
                            description = "GitHub Actions workflow YAML content to validate. "
                                    + "Must be valid YAML with required fields: name, on, jobs with runs-on and steps.")
                    String yaml) {

        CiCdValidator validator = new CiCdValidator();
        CiCdValidator.ValidationResult validation = validator.validate(yaml);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", validation.valid());
        if (!validation.valid()) {
            Map<String, Object> errors = new LinkedHashMap<>();
            errors.put("message", validation.errorMessage());
            result.put("errors", List.of(errors));
        } else {
            result.put("errors", List.of());
            result.put("warnings", List.of());
        }
        return JsonUtils.toJson(result);
    }

    // ─── Private helpers ──────────────────────────────────────────────

    private List<String> describeTriggers(CiCdTrigger trigger) {
        List<String> descriptions = new ArrayList<>();
        if (trigger.push() != null) {
            String branches = trigger.push().branches() != null
                    ? String.join(", ", trigger.push().branches())
                    : "any";
            descriptions.add("push to " + branches);
        }
        if (trigger.pullRequest() != null) {
            String branches = trigger.pullRequest().branches() != null
                    ? String.join(", ", trigger.pullRequest().branches())
                    : "any";
            descriptions.add("pull_request to " + branches);
        }
        if (trigger.release() != null) {
            descriptions.add("release published");
        }
        if (trigger.schedule() != null && !trigger.schedule().isEmpty()) {
            descriptions.add("scheduled (" + trigger.schedule().size() + " cron entries)");
        }
        if (trigger.workflowDispatch()) {
            descriptions.add("workflow_dispatch (manual)");
        }
        return descriptions;
    }
}
