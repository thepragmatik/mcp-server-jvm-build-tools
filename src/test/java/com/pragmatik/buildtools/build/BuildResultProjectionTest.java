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
package com.pragmatik.buildtools.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.pragmatik.buildtools.security.ModelOutputPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BuildResultProjectionTest {
    @Test
    void removesLargeRawOutputBeforePolicyAndPreservesFinalDiagnostic() {
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("rawOutput", "SYNTHETIC PRIVATE OUTPUT ".repeat(20_000));
        parsed.put("command", "SYNTHETIC PRIVATE COMMAND");
        parsed.put("success", false);
        parsed.put("testSummary", Map.of("total", 2, "failed", 1));
        parsed.put(
                "errors",
                List.of(Map.of(
                        "file",
                        "/workspace/private/Main.java",
                        "line",
                        27,
                        "message",
                        "cannot find symbol SYNTHETIC_SOURCE_IDENTIFIER")));

        String forPolicy = BuildToolsService.modelVisibleBuildResult(parsed);
        var safe = new JsonMapper().readTree(new ModelOutputPolicy().protect("analyze_build_output", forPolicy));
        assertFalse(forPolicy.contains("SYNTHETIC PRIVATE OUTPUT"));
        assertFalse(forPolicy.contains("SYNTHETIC PRIVATE COMMAND"));
        assertEquals(1, safe.get("testSummary").get("failed").intValue());
        assertEquals("test", safe.get("diagnostics").get(0).get("category").asText());
        assertEquals(27, safe.get("diagnostics").get(1).get("line").intValue());
        assertEquals(
                "compilation", safe.get("diagnostics").get(1).get("category").asText());
        assertFalse(safe.toString().contains("SYNTHETIC_SOURCE_IDENTIFIER"));
        assertFalse(safe.toString().contains("/workspace/private"));
    }

    @Test
    void boundsParsedDiagnosticInputBeforeJsonSerialization() {
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("errorCount", 14);
        parsed.put(
                "errors",
                java.util.stream.IntStream.rangeClosed(1, 14)
                        .mapToObj(i -> Map.of(
                                "file",
                                "/workspace/private/Main.java",
                                "line",
                                i,
                                "message",
                                "cannot find symbol " + "SYNTHETIC_SOURCE_IDENTIFIER".repeat(20_000)))
                        .toList());

        String forPolicy = BuildToolsService.modelVisibleBuildResult(parsed);
        var safe = new JsonMapper().readTree(new ModelOutputPolicy().protect("analyze_build_output", forPolicy));
        assertEquals(14, safe.get("errorCount").intValue());
        assertEquals(12, safe.get("diagnostics").size());
        assertEquals(12, safe.get("diagnostics").get(11).get("line").intValue());
        assertEquals(true, safe.get("diagnosticsTruncated").booleanValue());
        assertFalse(forPolicy.length() > 40_000);
    }
}
