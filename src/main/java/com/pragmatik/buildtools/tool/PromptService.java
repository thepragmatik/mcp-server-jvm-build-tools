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
package com.pragmatik.buildtools.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP prompt templates for common build and dependency workflows.
 * <p>
 * Each method returns a structured prompt template that LLM clients
 * can use to guide users through common JVM build scenarios. Templates
 * include parameter descriptions and step-by-step instructions.
 */
@Service
public class PromptService {

    /**
     * Generate a prompt template for building and testing a JVM project.
     */
    @Tool(
            name = "prompt_build_and_test",
            description = "Get a prompt template to help an LLM guide a user through building "
                    + "and testing a JVM project. Returns a structured prompt with step-by-step "
                    + "instructions for compile, test, and analyze workflows.")
    public String promptBuildAndTest(
            @ToolParam(
                            required = false,
                            description = "Project directory path. If omitted, the prompt will ask the user for it.")
                    String projectDir,
            @ToolParam(
                            required = false,
                            description =
                                    "Build tool name (maven, gradle, or sbt). If omitted, auto-detection will be used.")
                    String buildTool) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("promptName", "build_and_test");
        result.put("description", "Build and test a JVM project");
        result.put("version", "1.0");

        StringBuilder template = new StringBuilder();
        template.append("You are helping a developer build and test their JVM project. ");
        template.append("Follow these steps:\n\n");
        template.append("1. **Detect the build tool**: Use detect_build_tool on the project directory ");
        template.append("to understand which build system is in use.\n\n");
        template.append("2. **Validate configuration**: Use validate_build_configuration to check ");
        template.append("for syntax errors in build files (pom.xml, build.gradle, etc.).\n\n");
        template.append("3. **Compile the project**: Use execute_build_command with the appropriate ");
        template.append("command ('compile' for Maven, 'compileJava' for Gradle, 'compile' for SBT).\n\n");
        template.append("4. **Run tests**: Use execute_build_command with the test command ");
        template.append("('test' for Maven, 'test' for Gradle, 'test' for SBT).\n\n");
        template.append("5. **Analyze results**: Use analyze_build_output with the same test command ");
        template.append("to get structured JSON results with test counts, errors, and warnings.\n\n");
        template.append("6. **Report findings**: Summarize test results, highlight any failures, ");
        template.append("and suggest fixes for compilation errors.");

        if (projectDir != null && !projectDir.isBlank()) {
            template.insert(0, "Project directory: " + projectDir + "\n\n");
        }
        if (buildTool != null && !buildTool.isBlank()) {
            template.insert(0, "Build tool: " + buildTool + "\n");
        }

        result.put("template", template.toString());
        result.put("estimatedTokens", template.length() / 4);
        result.put("requiredTools", new String[] {
            "detect_build_tool", "validate_build_configuration", "execute_build_command", "analyze_build_output"
        });

        return JsonUtils.toJson(result);
    }

    /**
     * Generate a prompt template for auditing and upgrading dependencies.
     */
    @Tool(
            name = "prompt_dependency_audit",
            description = "Get a prompt template to help an LLM audit and upgrade project dependencies. "
                    + "Returns step-by-step instructions for dependency checking and upgrade recommendations.")
    public String promptDependencyAudit(
            @ToolParam(
                            required = false,
                            description = "Project directory path. If omitted, the prompt will ask the user for it.")
                    String projectDir) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("promptName", "dependency_audit");
        result.put("description", "Audit and upgrade project dependencies");
        result.put("version", "1.0");

        StringBuilder template = new StringBuilder();
        template.append("You are helping a developer audit and upgrade their project dependencies. ");
        template.append("Follow these steps:\n\n");
        template.append("1. **Detect the build tool**: Use detect_build_tool to understand ");
        template.append("the build system and get wrapper availability.\n\n");
        template.append("2. **Extract dependencies**: Read the build file to identify all dependencies.\n\n");
        template.append("3. **Check each dependency**: For each dependency, use check_dependency_version ");
        template.append("to get the latest available version from Maven Central.\n\n");
        template.append("4. **Classify upgrades**: Categorize upgrades as MAJOR, MINOR, or PATCH. ");
        template.append("Flag breaking changes (MAJOR) for careful review.\n\n");
        template.append("5. **Prioritize**: Start with PATCH upgrades (lowest risk), then MINOR, ");
        template.append("then MAJOR (may require code changes).\n\n");
        template.append("6. **Update build file**: Apply the upgrades one at a time, recompiling ");
        template.append("after each change to catch incompatibilities.");

        if (projectDir != null && !projectDir.isBlank()) {
            template.insert(0, "Project directory: " + projectDir + "\n\n");
        }

        result.put("template", template.toString());
        result.put("estimatedTokens", template.length() / 4);
        result.put(
                "requiredTools",
                new String[] {"detect_build_tool", "check_dependency_version", "execute_build_command"});

        return JsonUtils.toJson(result);
    }

    /**
     * Generate a prompt template for diagnosing build failures.
     */
    @Tool(
            name = "prompt_build_diagnosis",
            description = "Get a prompt template to help an LLM diagnose and fix build failures. "
                    + "Returns a structured diagnostic workflow for investigating compilation errors, "
                    + "test failures, and build configuration issues.")
    public String promptBuildDiagnosis(
            @ToolParam(required = true, description = "Project directory path") String projectDir,
            @ToolParam(required = false, description = "The failing command that produced the error")
                    String failedCommand) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("promptName", "build_diagnosis");
        result.put("description", "Diagnose and fix build failures");
        result.put("version", "1.0");

        StringBuilder template = new StringBuilder();
        template.append("You are diagnosing a build failure. Project directory: ")
                .append(projectDir)
                .append("\n\n");
        if (failedCommand != null && !failedCommand.isBlank()) {
            template.append("The failing command was: ").append(failedCommand).append("\n\n");
        }
        template.append("Follow this diagnostic workflow:\n\n");
        template.append("1. **Validate configuration**: Use validate_build_configuration to check ");
        template.append("for syntax errors in build files.\n\n");
        template.append("2. **Detect build tool**: Use detect_build_tool to confirm the build system ");
        template.append("and check wrapper availability.\n\n");
        template.append("3. **Run the build with analysis**: Use analyze_build_output to execute ");
        template.append("the build and get structured error output.\n\n");
        template.append("4. **Categorize errors**: Group errors by type:\n");
        template.append("   - **Compilation errors**: File, line number, message. Fix the syntax.\n");
        template.append(
                "   - **Dependency errors**: Missing or incompatible versions. Check with check_dependency_version.\n");
        template.append("   - **Test failures**: Which tests failed and why. Look at the test output.\n");
        template.append("   - **Configuration errors**: Plugin issues, missing tools on PATH.\n\n");
        template.append("5. **Fix the highest-priority error first**: Address one error and rebuild. ");
        template.append("Compilation errors can cascade — fixing one may resolve many.\n\n");
        template.append("6. **Iterate**: Rebuild after each fix until the build passes.");

        result.put("template", template.toString());
        result.put("estimatedTokens", template.length() / 4);
        result.put("requiredTools", new String[] {
            "validate_build_configuration",
            "detect_build_tool",
            "analyze_build_output",
            "check_dependency_version",
            "execute_build_command"
        });

        return JsonUtils.toJson(result);
    }
}
