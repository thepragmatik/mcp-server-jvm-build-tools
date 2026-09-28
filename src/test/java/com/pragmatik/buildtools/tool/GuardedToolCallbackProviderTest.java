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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pragmatik.buildtools.security.ModelOutputPolicy;
import com.pragmatik.buildtools.security.ProjectAccessPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class GuardedToolCallbackProviderTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsOutsideRootBeforeDelegating() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("allowed"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        AtomicReference<String> received = new AtomicReference<>();
        ToolCallback callback = guarded("detect_build_tool", root, received);
        assertThrows(IllegalArgumentException.class, () -> callback.call("{\"projectDir\":\"" + outside + "\"}"));
        assertEquals(null, received.get());
    }

    @Test
    void resolvesAliasAndRedactsResult() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("allowed"));
        Path project = Files.createDirectory(root.resolve("project"));
        AtomicReference<String> received = new AtomicReference<>();
        ToolCallback callback = guarded("detect_build_tool", root, received);
        String result = callback.call("{\"projectDir\":\"project\"}");
        assertTrue(received.get().contains(project.toString()));
        assertFalse(result.contains("private-user"));
        assertFalse(result.contains("example.invalid"));
        assertTrue(result.contains("[redacted-path]"));
    }

    @Test
    void exposesOnlyExplicitlyScopedTools() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("allowed"));
        GuardedToolCallbackProvider provider = new GuardedToolCallbackProvider(
                () -> new ToolCallback[] {
                    fake("validate_access_token", new AtomicReference<>()),
                    fake("execute_build_plan", new AtomicReference<>()),
                    fake("create_build_plan", new AtomicReference<>()),
                    fake("future_unreviewed_tool", new AtomicReference<>()),
                    fake("read_build_resource", new AtomicReference<>()),
                    fake("resolve_resource_template", new AtomicReference<>()),
                    fake("interpret_ci_flow", new AtomicReference<>()),
                    fake("detect_build_tool", new AtomicReference<>())
                },
                new ProjectAccessPolicy(root.toString()),
                new ModelOutputPolicy());
        assertEquals(1, provider.getToolCallbacks().length);
    }

    private ToolCallback guarded(String name, Path root, AtomicReference<String> received) {
        GuardedToolCallbackProvider provider = new GuardedToolCallbackProvider(
                () -> new ToolCallback[] {fake(name, received)},
                new ProjectAccessPolicy(root.toString()),
                new ModelOutputPolicy());
        return provider.getToolCallbacks()[0];
    }

    private static ToolCallback fake(String name, AtomicReference<String> received) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("synthetic test tool")
                .inputSchema("{\"type\":\"object\"}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String input) {
                received.set(input);
                return "ERROR /home/private-user/App.java test.user@example.invalid";
            }
        };
    }
}
