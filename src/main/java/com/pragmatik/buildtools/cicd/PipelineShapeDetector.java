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

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Detects pipeline shape from a natural-language description.
 *
 * <p>Uses keyword matching to determine the intended CI/CD pipeline shape,
 * which drives what triggers and jobs are generated.
 */
public final class PipelineShapeDetector {

    /** Predefined pipeline shapes. */
    public enum Shape {
        /** Push to main — Build + Test */
        CI_PUSH,
        /** Pull request — Build + Test on PR */
        CI_PR,
        /** Release — Build + Test + Deploy on tag/release */
        CI_RELEASE,
        /** Full — Push + PR + Release with all jobs */
        CI_FULL,
        /** Custom/unknown — generic push trigger with build + test */
        CUSTOM
    }

    private PipelineShapeDetector() {
        // utility class
    }

    /**
     * Detect pipeline shape from a natural-language description.
     *
     * @param description  Natural-language pipeline description
     * @return Detected shape; never null
     */
    public static Shape detect(String description) {
        if (description == null || description.isBlank()) {
            return Shape.CI_PUSH;
        }

        String lower = description.toLowerCase(Locale.ROOT);

        boolean hasPush = containsAny(lower, "push", "commit");
        boolean hasPr = containsWord(lower, "pr") || containsAny(lower, "pull request", "pull_request", "review");
        boolean hasRelease = containsAny(lower, "deploy", "release", "publish", "tag");

        if (hasPush && hasPr && hasRelease) {
            return Shape.CI_FULL;
        }
        if (hasRelease) {
            return Shape.CI_RELEASE;
        }
        if (hasPr) {
            return Shape.CI_PR;
        }
        if (hasPush) {
            return Shape.CI_PUSH;
        }

        return Shape.CI_PUSH;
    }

    /**
     * Detect build tool from a project directory by scanning for marker files.
     *
     * @param projectDir  Project directory path
     * @return Build tool name ("maven", "gradle", "sbt"), or null if unknown
     */
    public static String detectBuildTool(String projectDir) {
        if (projectDir == null || projectDir.isBlank()) {
            return null;
        }
        java.nio.file.Path dir = java.nio.file.Path.of(projectDir);
        if (!java.nio.file.Files.isDirectory(dir)) {
            return null;
        }
        if (java.nio.file.Files.exists(dir.resolve("pom.xml"))) {
            return "maven";
        }
        if (java.nio.file.Files.exists(dir.resolve("build.gradle"))
                || java.nio.file.Files.exists(dir.resolve("build.gradle.kts"))
                || java.nio.file.Files.exists(dir.resolve("settings.gradle"))
                || java.nio.file.Files.exists(dir.resolve("settings.gradle.kts"))) {
            return "gradle";
        }
        if (java.nio.file.Files.exists(dir.resolve("build.sbt"))) {
            return "sbt";
        }
        return null;
    }

    /**
     * Get build-tool-specific build command.
     *
     * @param tool  Build tool name
     * @return Shell command for building
     */
    public static String buildCommand(String tool) {
        return switch (tool != null ? tool : "") {
            case "maven" -> "mvn -B verify";
            case "gradle" -> "./gradlew build";
            case "sbt" -> "sbt test";
            default -> "mvn -B verify";
        };
    }

    /**
     * Get build-tool-specific test command.
     *
     * @param tool  Build tool name
     * @return Shell command for testing
     */
    public static String testCommand(String tool) {
        return switch (tool != null ? tool : "") {
            case "maven" -> "mvn -B test";
            case "gradle" -> "./gradlew test";
            case "sbt" -> "sbt test";
            default -> "mvn -B test";
        };
    }

    /**
     * Get build-tool-specific cache configuration parameters.
     */
    public static BuildToolCacheConfig cacheConfig(String tool) {
        return switch (tool != null ? tool : "") {
            case "maven" ->
                new BuildToolCacheConfig("~/.m2/repository", "maven-${{ hashFiles('**/pom.xml') }}", "maven-");
            case "gradle" ->
                new BuildToolCacheConfig(
                        "~/.gradle/caches\n          ~/.gradle/wrapper",
                        "gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}",
                        "gradle-");
            case "sbt" ->
                new BuildToolCacheConfig(
                        "~/.cache/coursier\n          ~/.sbt",
                        "sbt-${{ hashFiles('build.sbt', 'project/**') }}",
                        "sbt-");
            default -> new BuildToolCacheConfig("~/.m2/repository", "maven-${{ hashFiles('**/pom.xml') }}", "maven-");
        };
    }

    /** Cache configuration for a build tool. */
    public record BuildToolCacheConfig(String path, String key, String restoreKeys) {}

    /**
     * Build a pipeline from a description and detected shape.
     *
     * @param description  Natural-language description
     * @param shape        Detected pipeline shape
     * @param tool         Detected or specified build tool
     * @return A pipeline model ready for generation
     */
    public static CiCdPipeline buildPipeline(String description, Shape shape, String tool) {
        return buildPipeline(description, shape, tool, null, null);
    }

    /**
     * Build a pipeline from a description, detected shape, and optional overrides.
     *
     * @param description      Natural-language description
     * @param shape            Detected pipeline shape
     * @param tool             Detected or specified build tool
     * @param javaVersions     Optional JDK versions (e.g., ["21", "23"]); null defaults to ["21"]
     * @param configOverrides  Optional JSON-style overrides for branches, timeout, runners
     * @return A pipeline model ready for generation
     */
    @SuppressWarnings("unchecked")
    public static CiCdPipeline buildPipeline(
            String description,
            Shape shape,
            String tool,
            List<String> javaVersions,
            Map<String, Object> configOverrides) {

        String toolName = (tool != null) ? tool : "maven";
        String pipelineName =
                switch (shape) {
                    case CI_PUSH -> "CI - Push";
                    case CI_PR -> "CI - Pull Request";
                    case CI_RELEASE -> "CI - Release";
                    case CI_FULL -> "CI - Full Pipeline";
                    case CUSTOM -> "CI";
                };

        // Apply config overrides
        List<String> overrideBranches = null;
        Integer overrideTimeout = null;
        if (configOverrides != null) {
            Object branches = configOverrides.get("branches");
            if (branches instanceof List) {
                overrideBranches =
                        ((List<Object>) branches).stream().map(Object::toString).toList();
            }
            Object timeout = configOverrides.get("timeout");
            if (timeout instanceof Number) {
                overrideTimeout = ((Number) timeout).intValue();
            }
        }

        CiCdTrigger trigger = buildTrigger(shape, overrideBranches);

        // JDK versions: use provided, or default to ["21"]
        List<String> jdkVersions = (javaVersions != null && !javaVersions.isEmpty()) ? javaVersions : List.of("21");
        CiCdMatrixStrategy matrix = new CiCdMatrixStrategy(jdkVersions, List.of(), true);

        List<CiCdStep> buildSteps = buildToolSteps(toolName);
        List<CiCdStep> testSteps = buildTestSteps(toolName);

        CiCdPipeline.Builder pipelineBuilder = CiCdPipeline.builder()
                .name(pipelineName)
                .target("github-actions")
                .description(description)
                .on(trigger)
                .detectedBuildTool(toolName);

        List<CiCdJob> jobs;

        switch (shape) {
            case CI_RELEASE, CI_FULL -> {
                CiCdJob.Builder buildJobBuilder = CiCdJob.builder()
                        .name("Build")
                        .id("build")
                        .runsOn("ubuntu-latest")
                        .strategy(matrix)
                        .steps(buildSteps);
                if (overrideTimeout != null) {
                    buildJobBuilder.timeoutMinutes(overrideTimeout);
                }
                CiCdJob buildJob = buildJobBuilder.build();

                CiCdJob.Builder testJobBuilder = CiCdJob.builder()
                        .name("Test")
                        .id("test")
                        .runsOn("ubuntu-latest")
                        .needs(List.of("build"))
                        .strategy(matrix)
                        .steps(testSteps);
                if (overrideTimeout != null) {
                    testJobBuilder.timeoutMinutes(overrideTimeout);
                }
                CiCdJob testJob = testJobBuilder.build();

                CiCdJob.Builder deployJobBuilder = CiCdJob.builder()
                        .name("Deploy")
                        .id("deploy")
                        .runsOn("ubuntu-latest")
                        .needs(List.of("test"))
                        .condition("startsWith(github.ref, 'refs/tags/v')")
                        .steps(List.of(CiCdStep.checkout(), CiCdStep.run("Deploy release", "./gradlew publish")));
                if (overrideTimeout != null) {
                    deployJobBuilder.timeoutMinutes(overrideTimeout);
                }
                CiCdJob deployJob = deployJobBuilder.build();

                jobs = List.of(buildJob, testJob, deployJob);
                pipelineBuilder.requiredSecrets(
                        List.of(new CiCdSecret("DEPLOY_KEY", "SSH key or token for deployment")));
            }
            default -> {
                CiCdJob.Builder buildJobBuilder = CiCdJob.builder()
                        .name("Build")
                        .id("build")
                        .runsOn("ubuntu-latest")
                        .strategy(matrix)
                        .steps(buildSteps);
                if (overrideTimeout != null) {
                    buildJobBuilder.timeoutMinutes(overrideTimeout);
                }
                jobs = List.of(buildJobBuilder.build());
            }
        }

        return pipelineBuilder.jobs(jobs).build();
    }

    private static CiCdTrigger buildTrigger(Shape shape) {
        return buildTrigger(shape, null);
    }

    private static CiCdTrigger buildTrigger(Shape shape, List<String> overrideBranches) {
        List<String> defaultBranches =
                (overrideBranches != null && !overrideBranches.isEmpty()) ? overrideBranches : null;
        return switch (shape) {
            case CI_PUSH ->
                CiCdTrigger.builder()
                        .push(CiCdPushTrigger.of(defaultBranches != null ? defaultBranches : List.of("main")))
                        .build();
            case CI_PR ->
                CiCdTrigger.builder()
                        .push(CiCdPushTrigger.of(defaultBranches != null ? defaultBranches : List.of("main")))
                        .pullRequest(CiCdPrTrigger.of(defaultBranches != null ? defaultBranches : List.of("main")))
                        .build();
            case CI_RELEASE ->
                CiCdTrigger.builder()
                        .push(CiCdPushTrigger.of(defaultBranches != null ? defaultBranches : List.of("main")))
                        .pullRequest(CiCdPrTrigger.of(defaultBranches != null ? defaultBranches : List.of("main")))
                        .release(CiCdReleaseTrigger.published())
                        .build();
            case CI_FULL ->
                CiCdTrigger.builder()
                        .push(CiCdPushTrigger.of(defaultBranches != null ? defaultBranches : List.of("main")))
                        .pullRequest(CiCdPrTrigger.of(defaultBranches != null ? defaultBranches : List.of("**")))
                        .release(CiCdReleaseTrigger.published())
                        .build();
            case CUSTOM ->
                CiCdTrigger.builder()
                        .push(CiCdPushTrigger.of(defaultBranches != null ? defaultBranches : List.of("main")))
                        .build();
        };
    }

    private static List<CiCdStep> buildToolSteps(String tool) {
        BuildToolCacheConfig cache = cacheConfig(tool);
        return List.of(
                CiCdStep.checkout(),
                CiCdStep.setupJava("21"),
                CiCdStep.cache(
                        "Cache " + toolName(tool) + " dependencies", cache.path(), cache.key(), cache.restoreKeys()),
                CiCdStep.run("Build with " + toolName(tool), buildCommand(tool)));
    }

    private static List<CiCdStep> buildTestSteps(String tool) {
        BuildToolCacheConfig cache = cacheConfig(tool);
        return List.of(
                CiCdStep.checkout(),
                CiCdStep.setupJava("21"),
                CiCdStep.cache(
                        "Cache " + toolName(tool) + " dependencies", cache.path(), cache.key(), cache.restoreKeys()),
                CiCdStep.run("Run tests", testCommand(tool)));
    }

    private static String toolName(String tool) {
        return switch (tool != null ? tool : "") {
            case "maven" -> "Maven";
            case "gradle" -> "Gradle";
            case "sbt" -> "SBT";
            default -> "Maven";
        };
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if text contains a keyword as a whole word (boundary-matched).
     */
    private static boolean containsWord(String text, String word) {
        if (text == null || word == null) return false;
        int idx = text.indexOf(word);
        while (idx >= 0) {
            // Check that the character before and after are non-alphanumeric
            boolean beforeOk = idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1));
            int end = idx + word.length();
            boolean afterOk = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (beforeOk && afterOk) {
                return true;
            }
            idx = text.indexOf(word, idx + 1);
        }
        return false;
    }
}
