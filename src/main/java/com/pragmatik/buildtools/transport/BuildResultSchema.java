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
package com.pragmatik.buildtools.transport;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.pragmatik.buildtools.build.BuildResultLimits;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** MCP schema for bounded {@code ModelOutputPolicy} build results. */
final class BuildResultSchema {
    static final String TOOL_NAME = "analyze_build_output";
    static final String EXECUTE_TOOL_NAME = "execute_build_command";
    private static final JsonMapper JSON = new JsonMapper();
    private static final Map<String, Object> SCHEMA = buildSchema();

    private BuildResultSchema() {}

    static boolean supports(String toolName) {
        return TOOL_NAME.equals(toolName) || EXECUTE_TOOL_NAME.equals(toolName);
    }

    static Map<String, Object> schema() {
        return SCHEMA;
    }

    static boolean conforms(Map<String, Object> result) {
        return ValidatorHolder.VALIDATOR.validate(JSON.valueToTree(result)).isEmpty();
    }

    /** Tool discovery does not pay the validator compilation cost until the first call. */
    private static final class ValidatorHolder {
        private static final Schema VALIDATOR = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(JSON.valueToTree(SCHEMA));
    }

    private static Map<String, Object> buildSchema() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("completed", Map.of("const", true));
        fields.put("success", booleanField());
        fields.put("isError", booleanField());
        fields.put("truncated", booleanField());
        fields.put("outputTruncated", Map.of("const", true));
        fields.put("exitCode", Map.of("type", "integer", "minimum", Integer.MIN_VALUE, "maximum", Integer.MAX_VALUE));
        fields.put("errorCount", count());
        fields.put("warningCount", count());
        fields.put("testSummary", summary());
        fields.put("diagnostics", Map.of("type", "array", "maxItems", 12, "items", diagnostic()));
        fields.put("diagnosticsTruncated", booleanField());
        fields.put(
                "details",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of(
                                "No model-visible details",
                                "Tool execution failed; details withheld by privacy policy")));
        return object(fields, List.of("completed"));
    }

    private static Map<String, Object> summary() {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String name : List.of("total", "passed", "failed", "errors", "skipped")) {
            fields.put(name, count());
        }
        fields.put("countsCapped", Map.of("const", true));
        return object(fields, List.of());
    }

    private static Map<String, Object> diagnostic() {
        return object(
                Map.of(
                        "diagnosticRef", Map.of("type", "string", "pattern", "^d(?:[1-9]|1[0-2])$"),
                        "severity", Map.of("type", "string", "enum", List.of("error", "warning")),
                        "category",
                                Map.of(
                                        "type",
                                        "string",
                                        "enum",
                                        List.of(
                                                "compilation",
                                                "test",
                                                "dependency",
                                                "configuration",
                                                "execution",
                                                "other")),
                        "fileRef", Map.of("type", "string", "pattern", "^f(?:[1-9]|1[0-2])$"),
                        "fileType",
                                Map.of(
                                        "type",
                                        "string",
                                        "enum",
                                        List.of("java", "kt", "scala", "xml", "gradle", "kts", "sbt")),
                        "line", Map.of("type", "integer", "minimum", 1, "maximum", 9_999_999),
                        "message", Map.of("type", "string", "maxLength", 500)),
                List.of("severity", "category", "diagnosticRef", "message"));
    }

    private static Map<String, Object> count() {
        return Map.of("type", "integer", "minimum", 0, "maximum", BuildResultLimits.MAX_VISIBLE_COUNTER);
    }

    private static Map<String, Object> booleanField() {
        return Map.of("type", "boolean");
    }

    private static Map<String, Object> object(Map<String, Object> fields, List<String> required) {
        return Map.of("type", "object", "properties", fields, "required", required, "additionalProperties", false);
    }
}
