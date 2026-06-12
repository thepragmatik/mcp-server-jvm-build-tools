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
package com.pragmatik.buildtools;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class BuildToolsApplication {

    public static void main(String[] args) {
        SpringApplication.run(BuildToolsApplication.class, args);
        // Block main thread to keep JVM alive for stdio MCP transport
        try { Thread.currentThread().join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Bean
    public ToolCallbackProvider buildTools(BuildToolsService buildToolsService,
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
                                           ToolAuthorizationService toolAuthorizationService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(buildToolsService, dependencyService, promptService,
                             buildResourceService, dependencyResourceService,
                             resourceTemplateService, sbtProjectService,
                             buildAuthService,
                             dependencyConflictService,
                             buildPerformanceService,
                             javaVersionService,
                             toolAuthorizationService)
                .build();
    }
}
