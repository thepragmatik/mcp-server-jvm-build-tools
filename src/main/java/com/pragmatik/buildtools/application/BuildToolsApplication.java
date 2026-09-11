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
package com.pragmatik.buildtools.application;

import com.pragmatik.buildtools.build.BuildPerformanceService;
import com.pragmatik.buildtools.build.BuildResourceService;
import com.pragmatik.buildtools.build.BuildToolsService;
import com.pragmatik.buildtools.cicd.CiCdFlowService;
import com.pragmatik.buildtools.dependency.DependencyConflictService;
import com.pragmatik.buildtools.dependency.DependencyResourceService;
import com.pragmatik.buildtools.dependency.DependencyService;
import com.pragmatik.buildtools.plan.BuildPlanService;
import com.pragmatik.buildtools.sbt.SbtProjectService;
import com.pragmatik.buildtools.security.BuildAuthService;
import com.pragmatik.buildtools.security.ToolAuthorizationService;
import com.pragmatik.buildtools.tool.DeterministicToolCallbackProvider;
import com.pragmatik.buildtools.tool.JavaVersionService;
import com.pragmatik.buildtools.tool.PromptService;
import com.pragmatik.buildtools.tool.ResourceTemplateService;
import com.pragmatik.buildtools.tool.ToolCatalogueSummary;
import java.util.List;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = "com.pragmatik.buildtools")
public class BuildToolsApplication {

    public static void main(String[] args) {
        SpringApplication.run(BuildToolsApplication.class, args);
        // Block main thread to keep JVM alive for stdio MCP transport
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Exposes every {@code @Tool} method as a single {@link ToolCallbackProvider}.
     *
     * <p>The underlying {@link MethodToolCallbackProvider} discovers tools via reflection, which has
     * no defined ordering across JVMs/restarts. We therefore wrap it in a
     * {@link DeterministicToolCallbackProvider} so {@code tools/list} returns the static catalogue in
     * a stable, name-sorted order — enabling client/gateway caching and improving LLM prompt cache
     * hit rates (MCP RC, SEP-2549).
     */
    @Bean
    public ToolCallbackProvider buildTools(
            BuildToolsService buildToolsService,
            DependencyService dependencyService,
            PromptService promptService,
            BuildResourceService buildResourceService,
            DependencyResourceService dependencyResourceService,
            ResourceTemplateService resourceTemplateService,
            SbtProjectService sbtProjectService,
            BuildAuthService buildAuthService,
            DependencyConflictService dependencyConflictService,
            BuildPerformanceService buildPerformanceService,
            JavaVersionService javaVersionService,
            ToolAuthorizationService toolAuthorizationService,
            BuildPlanService buildPlanService,
            CiCdFlowService ciCdFlowService) {
        ToolCallbackProvider methodProvider = MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects(
                        buildToolsService,
                        dependencyService,
                        promptService,
                        buildResourceService,
                        dependencyResourceService,
                        resourceTemplateService,
                        sbtProjectService,
                        buildAuthService,
                        dependencyConflictService,
                        buildPerformanceService,
                        javaVersionService,
                        toolAuthorizationService,
                        buildPlanService,
                        ciCdFlowService))
                .build();
        return new DeterministicToolCallbackProvider(methodProvider);
    }

    /**
     * The single source of truth for the ordered list of {@code @Tool}-annotated service beans.
     * Both {@link #buildTools(...)} (tool registration) and {@link #toolCatalogueSummary(...)}
     * (service grouping, issue #189) consume this list, so the two wirings cannot drift out of
     * sync — a drift would silently regroup unknown tools under {@code ungrouped}.
     *
     * @param toolObjects the registered tool service beans, in registration order
     * @return the same beans as an array, spreadable into varargs consumers
     */
    private static Object[] toolObjects(Object... toolObjects) {
        return toolObjects;
    }

    /**
     * The single shared source for the discover result's additive {@code tools} summary
     * (mcp-005 slice 2, issue #177): driven by the same deterministic provider bean the MCP
     * runtime serves {@code tools/list} from, so the summary can never drift from the real
     * catalogue. The summary level binds from {@code buildtools.discover.tools-summary}
     * ({@code none | count | full}, default {@code full}); the literal fallback means the
     * default also holds where the property is absent.
     */
    @Bean
    public ToolCatalogueSummary toolCatalogueSummary(
            ToolCallbackProvider buildTools,
            BuildToolsService buildToolsService,
            DependencyService dependencyService,
            PromptService promptService,
            BuildResourceService buildResourceService,
            DependencyResourceService dependencyResourceService,
            ResourceTemplateService resourceTemplateService,
            SbtProjectService sbtProjectService,
            BuildAuthService buildAuthService,
            DependencyConflictService dependencyConflictService,
            BuildPerformanceService buildPerformanceService,
            JavaVersionService javaVersionService,
            ToolAuthorizationService toolAuthorizationService,
            BuildPlanService buildPlanService,
            CiCdFlowService ciCdFlowService,
            @Value("${buildtools.discover.tools-summary:full}") String toolsSummary) {
        List<Object> toolObjects = List.of(toolObjects(
                buildToolsService,
                dependencyService,
                promptService,
                buildResourceService,
                dependencyResourceService,
                resourceTemplateService,
                sbtProjectService,
                buildAuthService,
                dependencyConflictService,
                buildPerformanceService,
                javaVersionService,
                toolAuthorizationService,
                buildPlanService,
                ciCdFlowService));
        return new ToolCatalogueSummary(buildTools, toolObjects, ToolCatalogueSummary.Mode.fromConfig(toolsSummary));
    }
}
