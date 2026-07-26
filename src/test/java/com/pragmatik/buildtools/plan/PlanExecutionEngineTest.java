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

import com.pragmatik.buildtools.build.BuildToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanExecutionEngineTest {

    private PlanExecutionEngine engine;
    private BuildToolProvider provider;
    private static final String MAVEN_WRAPPER = "mvn";

    /**
     * Resolve the Maven home directory, preferring environment variables
     * (set by CI runners like GitHub Actions' setup-java) over the
     * local SDKMAN path used on the developer machine.
     */
    private static String resolveMavenHome() {
        String envHome = System.getenv("M2_HOME");
        if (envHome != null && !envHome.isEmpty()) return envHome;
        envHome = System.getenv("MAVEN_HOME");
        if (envHome != null && !envHome.isEmpty()) return envHome;
        return "/Users/rath/.sdkman/candidates/maven/current";
    }

    private static final String MAVEN_HOME = resolveMavenHome();

    @BeforeEach
    void setUp() {
        provider = new BuildToolProvider();
        engine = new PlanExecutionEngine(provider);
    }

    @Test
    void testExecutePlanBasicSteps(@TempDir Path tempDir) throws IOException {
        // Create a minimal Maven project so build tool detection works
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>test-project</artifactId>
                  <version>1.0</version>
                </project>
                """);

        List<PlanStep> steps = List.of(
                new PlanStep("step-1", "Compile", "clean compile", List.of(), 120, "continue", true, 0),
                new PlanStep("step-2", "Test", "test", List.of("step-1"), 300, "continue", true, 0));

        BuildPlan plan = new BuildPlan(
                "plan-1",
                "Build and test",
                tempDir.toString(),
                null,
                MAVEN_HOME,
                steps,
                "continue",
                java.time.Instant.now(),
                3600);

        PlanResult result = engine.executePlan(plan);

        assertNotNull(result);
        assertEquals(2, result.steps().size());
    }

    @Test
    void testPlanCancellation(@TempDir Path tempDir) throws IOException {
        // Create a minimal Maven project for build tool detection
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>test-project</artifactId>
                  <version>1.0</version>
                </project>
                """);

        List<PlanStep> steps = List.of(
                new PlanStep("step-1", "Compile", "clean compile", List.of(), 300, "stop", true, 0),
                new PlanStep("step-2", "Test", "test", List.of("step-1"), 300, "stop", true, 0));

        BuildPlan plan = new BuildPlan(
                "plan-cancel",
                "Cancel test",
                tempDir.toString(),
                null,
                MAVEN_HOME,
                steps,
                "stop",
                java.time.Instant.now(),
                3600);

        // Start execution in background
        Thread executor = new Thread(() -> engine.executePlan(plan));
        executor.start();

        // Poll until execution is actively running, then cancel.
        // This avoids timing races: regardless of Maven startup time,
        // we cancel as soon as the execution is registered.
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        boolean cancelled = false;
        while (System.nanoTime() < deadline) {
            PlanResult status = engine.getPlanStatus("plan-cancel");
            if (status != null && "running".equals(status.status())) {
                engine.cancelPlan("plan-cancel");
                cancelled = true;
                break;
            }
            // If execution already finished before we could cancel, bail
            if (!executor.isAlive()) {
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Wait for execution to finish
        try {
            executor.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        PlanResult result = engine.getPlanStatus("plan-cancel");
        assertNotNull(result);
        assertTrue(
                cancelled,
                "Cancellation should have been delivered mid-execution. "
                        + "If Maven is not available at " + MAVEN_HOME
                        + ", the step may have failed before cancellation took effect. "
                        + "Actual status: " + result.status());
        assertEquals("cancelled", result.status());
    }

    @Test
    void testGetPlanStatusReturnsNotFound() {
        PlanResult result = engine.getPlanStatus("nonexistent-plan");
        assertNull(result);
    }

    @Test
    void testCancelAlreadyCompletedPlan() {
        // Should be a no-op
        engine.cancelPlan("nonexistent-plan");
        // No exception should be thrown
    }

    @Test
    void testGetPlanStatusReturnsLatestResult() {
        List<PlanStep> steps =
                List.of(new PlanStep("step-1", "Compile", "clean compile", List.of(), 120, "stop", true, 0));

        BuildPlan plan = new BuildPlan(
                "plan-status", "Status test", "/tmp/test", null, null, steps, "stop", java.time.Instant.now(), 3600);

        PlanResult executed = engine.executePlan(plan);
        PlanResult status = engine.getPlanStatus("plan-status");

        assertNotNull(status);
        assertEquals(executed.planId(), status.planId());
        assertEquals(executed.status(), status.status());
    }
}
