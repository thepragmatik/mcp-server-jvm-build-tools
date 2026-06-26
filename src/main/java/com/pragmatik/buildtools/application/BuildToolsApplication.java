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

import com.pragmatik.buildtools.build.AsyncBuildService;
import com.pragmatik.buildtools.build.BuildCacheService;
import com.pragmatik.buildtools.build.BuildPerformanceService;
import com.pragmatik.buildtools.build.BuildResourceService;
import com.pragmatik.buildtools.build.BuildToolsService;
import com.pragmatik.buildtools.dependency.DependencyConflictService;
import com.pragmatik.buildtools.dependency.DependencyResourceService;
import com.pragmatik.buildtools.dependency.DependencyService;
import com.pragmatik.buildtools.sbt.SbtProjectService;
import com.pragmatik.buildtools.security.BuildAuthService;
import com.pragmatik.buildtools.security.ToolAuthorizationService;
import com.pragmatik.buildtools.tool.DeterministicToolCallbackProvider;
import com.pragmatik.buildtools.tool.JavaVersionService;
import com.pragmatik.buildtools.tool.PromptService;
import com.pragmatik.buildtools.tool.ResourceTemplateService;
import com.pragmatik.buildtools.tool.SupplyChainService;
import com.pragmatik.buildtools.tool.TestFlakinessService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
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
            AsyncBuildService asyncBuildService,
            BuildCacheService buildCacheService,
            TestFlakinessService testFlakinessService,
            SupplyChainService supplyChainService) {
        ToolCallbackProvider methodProvider = MethodToolCallbackProvider.builder()
                .toolObjects(
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
                        asyncBuildService,
                        buildCacheService,
                        testFlakinessService,
                        supplyChainService)
                .build();
        return new DeterministicToolCallbackProvider(methodProvider);
    }
}
