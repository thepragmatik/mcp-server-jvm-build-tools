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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for MCP resource template discovery and resolution.
 */
class ResourceTemplateServiceTest {

    private final ResourceTemplateService svc = new ResourceTemplateService();

    @Test
    void listTemplates_returnsAllFive() {
        String result = svc.listResourceTemplates();
        assertTrue(result.contains("\"templateCount\":5"));
        assertTrue(result.contains("dependencies"));
        assertTrue(result.contains("config"));
        assertTrue(result.contains("logs"));
        assertTrue(result.contains("test-results"));
        assertTrue(result.contains("summary"));
    }

    @Test
    void listTemplates_includesTemplateScheme() {
        String result = svc.listResourceTemplates();
        assertTrue(result.contains("\"templateScheme\""));
        assertTrue(result.contains("build://{projectName}"));
    }

    @Test
    void listTemplates_eachTemplateHasParameters() {
        String result = svc.listResourceTemplates();
        // Every template except summary should have parameter definitions
        assertTrue(result.contains("\"parameters\""));
        assertTrue(result.contains("\"uriTemplate\""));
        assertTrue(result.contains("\"name\""));
    }

    @Test
    void resolveTemplate_withAllParams() {
        String result = svc.resolveResourceTemplate(
                "build://{projectName}/dependencies/{buildTool}",
                "{\"projectName\":\"myapp\",\"buildTool\":\"maven\"}");
        assertTrue(result.contains("\"allParamsResolved\":true"));
        assertTrue(result.contains("build://myapp/dependencies/maven"));
    }

    @Test
    void resolveTemplate_withMissingParam() {
        String result = svc.resolveResourceTemplate(
                "build://{projectName}/dependencies/{buildTool}", "{\"projectName\":\"myapp\"}");
        assertTrue(result.contains("\"allParamsResolved\":false"));
        assertTrue(result.contains("\"missingParams\""));
        assertTrue(result.contains("buildTool"));
    }

    @Test
    void resolveTemplate_singleParam() {
        String result =
                svc.resolveResourceTemplate("build://{projectName}/summary", "{\"projectName\":\"my-service\"}");
        assertTrue(result.contains("\"allParamsResolved\":true"));
        assertTrue(result.contains("build://my-service/summary"));
    }

    @Test
    void resolveTemplate_configFile() {
        String result = svc.resolveResourceTemplate(
                "build://{projectName}/config/{fileName}",
                "{\"projectName\":\"lib\",\"fileName\":\"build.gradle.kts\"}");
        assertTrue(result.contains("\"allParamsResolved\":true"));
        assertTrue(result.contains("build://lib/config/build.gradle.kts"));
    }

    @Test
    void resolveTemplate_invalidJson_returnsError() {
        String result = svc.resolveResourceTemplate("build://{projectName}/summary", "not-json");
        assertTrue(result.contains("\"error\""));
    }

    @Test
    void resolveTemplate_emptyParams() {
        String result = svc.resolveResourceTemplate("build://{projectName}/summary", "{}");
        assertTrue(result.contains("\"allParamsResolved\":false"));
        assertTrue(result.contains("projectName"));
    }

    @Test
    void resolveTemplate_logsCommand() {
        String result = svc.resolveResourceTemplate(
                "build://{projectName}/logs/{buildCommand}", "{\"projectName\":\"app\",\"buildCommand\":\"test\"}");
        assertTrue(result.contains("\"allParamsResolved\":true"));
        assertTrue(result.contains("build://app/logs/test"));
    }
}
