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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pragmatik.buildtools.build.BuildToolProvider;
import com.pragmatik.buildtools.build.BuildToolsService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link BuildPlanService}.
 */
class BuildPlanServiceTest {

    private BuildToolsService buildToolsService;
    private BuildToolProvider buildToolProvider;
    private BuildPlanService service;

    @BeforeEach
    void setUp() {
        buildToolsService = mock(BuildToolsService.class);
        buildToolProvider = new BuildToolProvider();
        service = new BuildPlanService(buildToolsService, buildToolProvider);
    }

    // ─── parseDescription tests ────────────────────────────────────

    @Test
    void testParseCleanCompileTest() {
        List<PlanStep> steps = service.parseDescription("clean compile test");
        assertEquals(3, steps.size());
        assertEquals("step-1", steps.get(0).id());
        assertEquals("clean", steps.get(0).command());
        assertEquals("Clean build artifacts", steps.get(0).label());
        assertEquals("step-2", steps.get(1).id());
        assertEquals("compile", steps.get(1).command());
        assertEquals("Compile source code", steps.get(1).label());
        assertEquals("step-3", steps.get(2).id());
        assertEquals("test", steps.get(2).command());
        assertEquals("Run tests", steps.get(2).label());
    }

    @Test
    void testParseBuildAndPackage() {
        List<PlanStep> steps = service.parseDescription("build and package");
        assertEquals(2, steps.size());
        assertEquals("step-1", steps.get(0).id());
        assertEquals("build", steps.get(0).command());
        assertEquals("Build the project", steps.get(0).label());
        assertEquals("step-2", steps.get(1).id());
        assertEquals("package", steps.get(1).command());
        assertEquals("Package artifacts", steps.get(1).label());
    }

    @Test
    void testParseCleanCompileTestPackageInstall() {
        List<PlanStep> steps = service.parseDescription("clean compile test package install");
        assertEquals(5, steps.size());
        assertEquals("clean", steps.get(0).command());
        assertEquals("compile", steps.get(1).command());
        assertEquals("test", steps.get(2).command());
        assertEquals("package", steps.get(3).command());
        assertEquals("install", steps.get(4).command());
    }

    @Test
    void testParseEmptyDescriptionReturnsEmptyList() {
        List<PlanStep> steps = service.parseDescription("");
        assertTrue(steps.isEmpty());
    }

    @Test
    void testParseNoKeywordsReturnsEmpty() {
        List<PlanStep> steps = service.parseDescription("run the application and do everything");
        assertTrue(steps.isEmpty());
    }

    @Test
    void testParseDeduplicatesSteps() {
        List<PlanStep> steps = service.parseDescription("clean compile clean test compile");
        assertEquals(3, steps.size());
        assertEquals("clean", steps.get(0).command());
        assertEquals("compile", steps.get(1).command());
        assertEquals("test", steps.get(2).command());
    }

    @Test
    void testParseRespectsLifecycleOrder() {
        List<PlanStep> steps = service.parseDescription("test clean package compile");
        assertEquals(4, steps.size());
        assertEquals("clean", steps.get(0).command());
        assertEquals("compile", steps.get(1).command());
        assertEquals("test", steps.get(2).command());
        assertEquals("package", steps.get(3).command());
    }

    @Test
    void testParseAllKeywords() {
        List<PlanStep> steps = service.parseDescription("clean compile build test package install deploy validate");
        assertEquals(8, steps.size());
    }

    // ─── createBuildPlan tests ─────────────────────────────────────

    @Test
    void testCreateBuildPlanBasic(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                "<?xml version=\"1.0\"?><project><modelVersion>4.0.0</modelVersion>"
                        + "<groupId>test</groupId><artifactId>test</artifactId>"
                        + "<version>1.0</version></project>");

        String result = service.createBuildPlan("clean compile test", tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"planId\""));
        assertTrue(result.contains("\"clean\""));
        assertTrue(result.contains("\"compile\""));
        assertTrue(result.contains("\"test\""));
        assertTrue(result.contains("\"stepCount\":3"));
    }

    @Test
    void testCreateBuildPlanWithBuildAndPackage(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                "<?xml version=\"1.0\"?><project><modelVersion>4.0.0</modelVersion>"
                        + "<groupId>test</groupId><artifactId>test</artifactId>"
                        + "<version>1.0</version></project>");

        String result = service.createBuildPlan("build and package", tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"build\""));
        assertTrue(result.contains("\"package\""));
        assertTrue(result.contains("\"stepCount\":2"));
    }

    @Test
    void testCreateBuildPlanEmptyDescription() {
        String result = service.createBuildPlan("", "/tmp/test");
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("Description cannot be null or empty"));
    }

    @Test
    void testCreateBuildPlanNullDescription() {
        String result = service.createBuildPlan(null, "/tmp/test");
        assertTrue(result.contains("\"success\":false"));
    }

    @Test
    void testCreateBuildPlanNoKeywords() {
        String result = service.createBuildPlan("do everything automatically", "/tmp/test");
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("No recognizable build steps found"));
    }

    @Test
    void testCreateBuildPlanReturnsPlanWithId(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                "<?xml version=\"1.0\"?><project><modelVersion>4.0.0</modelVersion>"
                        + "<groupId>test</groupId><artifactId>test</artifactId>"
                        + "<version>1.0</version></project>");

        String result = service.createBuildPlan("compile test", tempDir.toString());
        assertTrue(result.contains("\"planId\""));
        assertTrue(result.contains("\"projectDir\""));
        assertTrue(result.contains(tempDir.toString()));
    }

    @Test
    void testCreateBuildPlanInvalidProjectDir() {
        String result = service.createBuildPlan("test", "/nonexistent/path");
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("Project directory not found"));
    }

    // ─── executePlan tests ─────────────────────────────────────────

    @Test
    void testExecutePlanAllStepsSucceed(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                "<?xml version=\"1.0\"?><project><modelVersion>4.0.0</modelVersion>"
                        + "<groupId>test</groupId><artifactId>test</artifactId>"
                        + "<version>1.0</version></project>");

        when(buildToolsService.executeBuildCommand(isNull(), isNull(), eq(tempDir.toString()), anyString()))
                .thenReturn("BUILD SUCCESS");

        String createResult = service.createBuildPlan("clean compile", tempDir.toString());
        String planId = extractPlanId(createResult);

        String result = service.executePlan(planId);
        assertNotNull(result);
        assertTrue(result.contains("\"status\":\"completed\""));
        assertTrue(result.contains("\"completed\""));
    }

    @Test
    void testExecutePlanReturnsAllStepResults(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                "<?xml version=\"1.0\"?><project><modelVersion>4.0.0</modelVersion>"
                        + "<groupId>test</groupId><artifactId>test</artifactId>"
                        + "<version>1.0</version></project>");

        when(buildToolsService.executeBuildCommand(isNull(), isNull(), eq(tempDir.toString()), anyString()))
                .thenReturn("BUILD SUCCESS");

        String createResult = service.createBuildPlan("clean compile test", tempDir.toString());
        String planId = extractPlanId(createResult);

        String result = service.executePlan(planId);
        assertTrue(result.contains("\"total\":3"));
        assertTrue(result.contains("\"completed\":3"));
    }

    @Test
    void testExecutePlanWithFailureStops(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                "<?xml version=\"1.0\"?><project><modelVersion>4.0.0</modelVersion>"
                        + "<groupId>test</groupId><artifactId>test</artifactId>"
                        + "<version>1.0</version></project>");

        when(buildToolsService.executeBuildCommand(isNull(), isNull(), eq(tempDir.toString()), eq("clean")))
                .thenReturn("BUILD SUCCESS");
        when(buildToolsService.executeBuildCommand(isNull(), isNull(), eq(tempDir.toString()), eq("compile")))
                .thenThrow(new RuntimeException("Compilation error: cannot find symbol"));
        when(buildToolsService.executeBuildCommand(isNull(), isNull(), eq(tempDir.toString()), eq("test")))
                .thenReturn("BUILD SUCCESS");

        String createResult = service.createBuildPlan("clean compile test", tempDir.toString());
        String planId = extractPlanId(createResult);

        String result = service.executePlan(planId);
        assertTrue(result.contains("\"status\":\"failed\""));
        assertTrue(result.contains("\"completed\":1"));
        assertTrue(result.contains("\"failed\":1"));
        assertTrue(result.contains("\"skipped\":1"));
    }

    @Test
    void testExecutePlanNotFound() {
        String result = service.executePlan("nonexistent-id");
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("Plan not found"));
    }

    // ─── getPlan tests ─────────────────────────────────────────────

    @Test
    void testGetPlanAfterCreate(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                "<?xml version=\"1.0\"?><project><modelVersion>4.0.0</modelVersion>"
                        + "<groupId>test</groupId><artifactId>test</artifactId>"
                        + "<version>1.0</version></project>");

        String createResult = service.createBuildPlan("compile test", tempDir.toString());
        String planId = extractPlanId(createResult);

        BuildPlan plan = service.getPlan(planId);
        assertNotNull(plan);
        assertEquals(planId, plan.planId());
        assertEquals("compile test", plan.description());
        assertEquals(2, plan.steps().size());
    }

    @Test
    void testGetPlanNotFound() {
        assertNull(service.getPlan("nonexistent"));
    }

    // ─── Full lifecycle test ───────────────────────────────────────

    @Test
    void testFullPlanLifecycle(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                "<?xml version=\"1.0\"?><project><modelVersion>4.0.0</modelVersion>"
                        + "<groupId>test</groupId><artifactId>test</artifactId>"
                        + "<version>1.0</version></project>");

        when(buildToolsService.executeBuildCommand(isNull(), isNull(), eq(tempDir.toString()), anyString()))
                .thenReturn("BUILD SUCCESS");

        // Create plan
        String createResult = service.createBuildPlan("compile test package", tempDir.toString());
        assertTrue(createResult.contains("\"stepCount\":3"));

        String planId = extractPlanId(createResult);
        assertNotNull(planId);

        // Verify stored plan
        BuildPlan stored = service.getPlan(planId);
        assertNotNull(stored);
        assertEquals(planId, stored.planId());
        assertEquals(3, stored.steps().size());

        // Execute plan
        String executeResult = service.executePlan(planId);
        assertTrue(executeResult.contains("\"status\":\"completed\""));
        assertTrue(executeResult.contains("\"total\":3"));
        assertTrue(executeResult.contains("\"completed\":3"));
        assertTrue(executeResult.contains("\"failed\":0"));
        assertTrue(executeResult.contains("\"skipped\":0"));
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private String extractPlanId(String json) {
        String marker = "\"planId\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = json.indexOf('"', start);
        return (end < 0) ? null : json.substring(start, end);
    }
}
