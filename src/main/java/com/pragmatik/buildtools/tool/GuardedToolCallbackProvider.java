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
import java.util.Arrays;
import java.util.Set;
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
    private final ToolCallback[] callbacks;
    private static final Set<String> PRIVATE_TOOLS = Set.of(
            "validate_access_token",
            "audit_tool_access",
            "check_credential_status",
            "execute_build_plan",
            "create_build_plan");
    private final ProjectAccessPolicy projectAccess;
    private final ModelOutputPolicy outputPolicy;
    private final JsonMapper mapper = new JsonMapper();

    public GuardedToolCallbackProvider(
            ToolCallbackProvider delegate, ProjectAccessPolicy projectAccess, ModelOutputPolicy outputPolicy) {
        this.projectAccess = projectAccess;
        this.outputPolicy = outputPolicy;
        this.callbacks = Arrays.stream(delegate.getToolCallbacks())
                .filter(callback ->
                        !PRIVATE_TOOLS.contains(callback.getToolDefinition().name()))
                .map(callback -> (ToolCallback) new GuardedCallback(callback))
                .toArray(ToolCallback[]::new);
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return callbacks.clone();
    }

    private final class GuardedCallback implements ToolCallback {
        private final ToolCallback callback;

        private GuardedCallback(ToolCallback callback) {
            this.callback = callback;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return callback.getToolDefinition();
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
