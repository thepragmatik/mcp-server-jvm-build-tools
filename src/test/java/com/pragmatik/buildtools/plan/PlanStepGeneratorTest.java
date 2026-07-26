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
package com.pragmatik.buildtools.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanStepGeneratorTest {

    @Test
    void testBasic3StepPlanFromDescription() throws IOException {
        PlanStepGenerator generator = new PlanStepGenerator();
        // "Build and test this project, then package it as a JAR"
        // Extracts: build, test, package → 3 steps
        String description = "Build and test this project, then package it";

        List<PlanStep> steps = generator.generateSteps(description, "maven");

        assertEquals(3, steps.size());
        assertEquals("step-1", steps.get(0).id());
        assertEquals("compile", steps.get(0).command());
        assertEquals("step-2", steps.get(1).id());
        assertEquals("test", steps.get(1).command());
        assertTrue(steps.get(1).dependsOn().contains("step-1"));
        assertEquals("step-3", steps.get(2).id());
        assertEquals("package", steps.get(2).command());
        assertTrue(steps.get(2).dependsOn().contains("step-2"));
    }

    @Test
    void testExplicitStepDefinitions() throws IOException {
        PlanStepGenerator generator = new PlanStepGenerator();
        String stepsJson = "[{\"id\":\"clean\",\"label\":\"Clean\",\"command\":\"clean\"},"
                + "{\"id\":\"compile\",\"label\":\"Compile\",\"command\":\"compile\",\"dependsOn\":[\"clean\"]}]";

        List<PlanStep> steps = generator.parseStepsJson(stepsJson);

        assertEquals(2, steps.size());
        assertEquals("clean", steps.get(0).id());
        assertEquals("compile", steps.get(1).id());
        assertTrue(steps.get(1).dependsOn().contains("clean"));
    }

    @Test
    void testCommandValidationRejectsUnknownCommands() {
        PlanStepGenerator generator = new PlanStepGenerator();
        String description = "delete run";

        assertThrows(IllegalArgumentException.class, () -> {
            generator.generateSteps(description, "maven");
        });
    }

    @Test
    void testAutoDetectFromProjectDir(@TempDir Path tempDir) throws IOException {
        // Create a Maven project marker
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");

        PlanStepGenerator generator = new PlanStepGenerator();
        String description = "Build and test";

        List<PlanStep> steps = generator.generateSteps(description, tempDir.toString(), null);

        assertNotNull(steps);
        assertFalse(steps.isEmpty());
    }

    @Test
    void testEmptyDescriptionThrows() {
        PlanStepGenerator generator = new PlanStepGenerator();
        assertThrows(IllegalArgumentException.class, () -> {
            generator.generateSteps("", "maven");
        });
    }

    @Test
    void testDescriptionWithAllPhases() throws IOException {
        PlanStepGenerator generator = new PlanStepGenerator();
        String description = "clean, validate, compile, test, package, install, deploy";

        List<PlanStep> steps = generator.generateSteps(description, "maven");

        assertEquals(7, steps.size());
        assertEquals("clean", steps.get(0).command());
    }

    @Test
    void testPlanStepRecordDefaults() {
        PlanStep step = new PlanStep("step-1", "Compile", "compile", List.of(), 300, "stop", true, 0);
        assertEquals("step-1", step.id());
        assertEquals("Compile", step.label());
        assertEquals("compile", step.command());
        assertTrue(step.dependsOn().isEmpty());
        assertEquals(300, step.timeoutSeconds());
        assertEquals("stop", step.onFailure());
        assertTrue(step.captureOutput());
        assertEquals(0, step.retryCount());
    }

    @Test
    void testBuildPlanRecord() {
        PlanStep step = new PlanStep("step-1", "Compile", "compile", List.of(), 300, "stop", true, 0);
        BuildPlan plan = new BuildPlan(
                "plan-123",
                "Test plan",
                "/tmp/project",
                "maven",
                "/opt/maven",
                List.of(step),
                "stop",
                java.time.Instant.now(),
                3600);

        assertEquals("plan-123", plan.planId());
        assertEquals("Test plan", plan.description());
        assertEquals("/tmp/project", plan.projectDir());
        assertEquals("maven", plan.buildToolName());
        assertEquals("/opt/maven", plan.buildToolHome());
        assertEquals(1, plan.steps().size());
        assertEquals("stop", plan.errorHandling());
        assertEquals(3600, plan.ttlSeconds());
    }

    @Test
    void testStepResultRecord() {
        StepResult result = new StepResult(
                "step-1",
                "Compile",
                "completed",
                12.5,
                true,
                "output text",
                null,
                List.of(),
                0,
                java.time.Instant.now(),
                java.time.Instant.now());

        assertEquals("step-1", result.id());
        assertEquals("completed", result.status());
        assertTrue(result.success());
        assertEquals(12.5, result.durationSeconds());
    }

    @Test
    void testPlanResultRecord() {
        StepResult stepResult = new StepResult(
                "step-1",
                "Compile",
                "completed",
                10.0,
                true,
                "ok",
                null,
                List.of(),
                0,
                java.time.Instant.now(),
                java.time.Instant.now());

        PlanSummary summary = new PlanSummary(3, 3, 0, 0, 0, 0);
        PlanResult result = new PlanResult(
                "plan-123",
                "Test",
                "completed",
                "/tmp/project",
                "maven",
                30.0,
                "30.0s",
                List.of(stepResult),
                summary,
                List.of(),
                java.time.Instant.now());

        assertEquals("plan-123", result.planId());
        assertEquals("completed", result.status());
        assertEquals(3, result.summary().total());
        assertEquals(3, result.summary().completed());
    }

    @Test
    void testPlanSummaryRecord() {
        PlanSummary summary = new PlanSummary(5, 3, 1, 1, 2, 1);
        assertEquals(5, summary.total());
        assertEquals(3, summary.completed());
        assertEquals(1, summary.failed());
        assertEquals(1, summary.skipped());
        assertEquals(2, summary.errorCount());
        assertEquals(1, summary.warningCount());
    }

    @Test
    void testInvalidStepsJsonThrows() {
        PlanStepGenerator generator = new PlanStepGenerator();
        assertThrows(IllegalArgumentException.class, () -> {
            generator.parseStepsJson("not valid json");
        });
    }
}
