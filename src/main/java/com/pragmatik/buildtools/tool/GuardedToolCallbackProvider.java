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

import com.pragmatik.buildtools.security.ModelOutputPolicy;
import com.pragmatik.buildtools.security.ProjectAccessPolicy;
import com.pragmatik.buildtools.security.ToolPermission;
import java.util.Arrays;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Applies project access checks at the shared MCP callback boundary. */
public final class GuardedToolCallbackProvider implements ToolCallbackProvider {
    private static final Map<String, String> PUBLIC_DESCRIPTIONS = Map.ofEntries(
            Map.entry("get_build_tool_version", "Return a build-tool version number without host details."),
            Map.entry("list_build_tools", "List supported build-tool names only."),
            Map.entry(
                    "detect_build_tool", "Detect Maven, Gradle, or sbt; return names and count without project paths."),
            Map.entry("validate_build_configuration", "Return validity, counts, and bounded redacted diagnostics."),
            Map.entry(
                    "analyze_build_performance",
                    "Return tracked-build and suggestion counts with an optimization potential level; raw suggestions are withheld."),
            Map.entry(
                    "execute_build_command",
                    "Execute an allowed build command and return optional exitCode for a completed built-in process and at most 12 structured, redacted diagnostics (severity, category, diagnosticRef, optional fileRef, file type and line, normalized message), not raw logs. Use for triage; inspect local files for edits."),
            Map.entry(
                    "analyze_build_output",
                    "Run build analysis and return test counts with at most 12 structured, redacted diagnostics (severity, category, diagnosticRef, optional fileRef, file type and line, normalized message), not raw logs. Use for triage; inspect local files for edits."),
            Map.entry("profile_build", "Run a build and return duration and phase counts without raw phase details."),
            Map.entry(
                    "check_dependency_version",
                    "Return available version numbers and upgrade status without dependency identifiers."),
            Map.entry(
                    "analyze_pom_dependencies",
                    "Return dependency, managed-entry, and BOM counts; coordinates and per-dependency classifications are withheld."),
            Map.entry(
                    "scan_dependency_cves",
                    "Return recognized-declaration and affected-dependency counts with scan status. Sparse OSV matches have unknown severity; high and critical counts appear only when known. Incomplete scans have no counts. Dependency and CVE identities are withheld."),
            Map.entry(
                    "detect_dependency_conflicts",
                    "Return conflict and analyzed-file counts without dependency identifiers."),
            Map.entry(
                    "check_java_compatibility",
                    "Return a compatibility verdict and issue count without dependency details."),
            Map.entry("detect_sbt_modules", "Return module count and structure flags without module names."),
            Map.entry(
                    "detect_sbt_test_frameworks",
                    "Return framework count and configuration flags without framework names."),
            Map.entry("analyze_sbt_build", "Return Scala and sbt versions without organization or plugin details."),
            Map.entry(
                    "prompt_build_and_test",
                    "Return a server-authored build-and-test workflow without echoing user inputs."),
            Map.entry(
                    "prompt_dependency_audit",
                    "Return a server-authored dependency-audit workflow without echoing user inputs."),
            Map.entry(
                    "prompt_build_diagnosis",
                    "Return a server-authored diagnosis workflow without echoing user inputs."),
            Map.entry(
                    "list_build_resources", "Return resource count and kind names without resource URIs or contents."),
            Map.entry(
                    "list_dependency_resources",
                    "Return resource count and available build-tool names without dependency identities."),
            Map.entry(
                    "validate_ci_flow",
                    "Return validity with bounded redacted errors and warnings; raw configuration is withheld."),
            Map.entry("check_tool_authorization", "Return an authorization decision without credential identities."),
            Map.entry("list_available_scopes", "Return the names of public permission scopes."));
    private final ToolCallback[] callbacks;
    private final ProjectAccessPolicy projectAccess;
    private final ModelOutputPolicy outputPolicy;
    private final JsonMapper mapper = new JsonMapper();

    public GuardedToolCallbackProvider(
            ToolCallbackProvider delegate, ProjectAccessPolicy projectAccess, ModelOutputPolicy outputPolicy) {
        this.projectAccess = projectAccess;
        this.outputPolicy = outputPolicy;
        this.callbacks = Arrays.stream(delegate.getToolCallbacks())
                .filter(callback ->
                        ToolPermission.isKnownTool(callback.getToolDefinition().name()))
                .map(callback -> (ToolCallback) new GuardedCallback(callback))
                .toArray(ToolCallback[]::new);
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return callbacks.clone();
    }

    private final class GuardedCallback implements ToolCallback {
        private final ToolCallback callback;
        private final ToolDefinition publicDefinition;

        private GuardedCallback(ToolCallback callback) {
            this.callback = callback;
            ToolDefinition original = callback.getToolDefinition();
            String description = PUBLIC_DESCRIPTIONS.get(original.name());
            if (description == null) {
                throw new IllegalStateException("No public result contract for " + original.name());
            }
            this.publicDefinition = ToolDefinition.builder()
                    .name(original.name())
                    .description(description)
                    .inputSchema(original.inputSchema())
                    .build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return publicDefinition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return callback.getToolMetadata();
        }

        @Override
        public String call(String input) {
            String validated = validatePaths(input);
            try {
                return outputPolicy.protect(callback.getToolDefinition().name(), callback.call(validated));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Tool execution failed; details withheld by privacy policy");
            }
        }

        @Override
        public String call(String input, ToolContext context) {
            String validated = validatePaths(input);
            try {
                return outputPolicy.protect(callback.getToolDefinition().name(), callback.call(validated, context));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Tool execution failed; details withheld by privacy policy");
            }
        }

        private String validatePaths(String input) {
            try {
                JsonNode args = mapper.readTree(input);
                if (args == null || !args.isObject()) {
                    throw new IllegalArgumentException("Tool arguments must be a JSON object");
                }
                ObjectNode object = (ObjectNode) args;
                validatePathArgument(object, "projectDir");
                validatePathArgument(object, "localRepositoryPath");
                return mapper.writeValueAsString(object);
            } catch (tools.jackson.core.JacksonException e) {
                throw new IllegalArgumentException("Tool arguments are not valid JSON", e);
            }
        }

        private void validatePathArgument(ObjectNode args, String name) {
            JsonNode value = args.get(name);
            if (value == null || value.isNull()) {
                return;
            }
            if (!value.isTextual()) {
                throw new IllegalArgumentException(name + " must be a path string");
            }
            args.put(name, projectAccess.requireAllowed(value.asText()).toString());
        }
    }
}
