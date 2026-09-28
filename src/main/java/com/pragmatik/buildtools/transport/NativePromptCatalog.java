/*
 * Copyright 2025 Rahul Thakur
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.pragmatik.buildtools.transport;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Immutable, server-authored native MCP prompts shared by stdio and HTTP. */
@Component
public final class NativePromptCatalog {
    private static final Map<String, String> MESSAGES = Map.of(
            "diagnose_build_failure",
            "Diagnose a JVM build failure. First identify the build tool and request the smallest relevant redacted diagnostic result. Separate the first root-cause error from follow-on failures, then propose a minimal fix and a targeted verification command. Do not request raw logs, secrets, personal data, or unrestricted paths.",
            "review_dependency_updates",
            "Review JVM dependency updates. Inspect only the dependency metadata needed for the decision. Compare compatibility, security, and build impact; propose one bounded update at a time and verify with targeted tests. Avoid disclosing private coordinates, paths, credentials, or raw dependency output.",
            "plan_test_strategy",
            "Plan a JVM test strategy. Identify the relevant module and test layer, run the narrowest safe tests first, and expand only when evidence warrants it. Summarize failures from redacted diagnostics and report the exact verification scope without copying raw test output or local paths.");

    private static final List<Prompt> PROMPTS = List.of(
            new Prompt(
                    "diagnose_build_failure",
                    "Diagnose build failure",
                    "A privacy-safe workflow for fixing a Maven, Gradle, or sbt build failure.",
                    List.of()),
            new Prompt(
                    "review_dependency_updates",
                    "Review dependency updates",
                    "A bounded workflow for assessing JVM dependency changes.",
                    List.of()),
            new Prompt(
                    "plan_test_strategy",
                    "Plan test strategy",
                    "A focused workflow for choosing and verifying JVM tests.",
                    List.of()));

    public List<Prompt> prompts() {
        return PROMPTS;
    }

    public GetPromptResult getPrompt(GetPromptRequest request) {
        if (request == null
                || request.name() == null
                || !MESSAGES.containsKey(request.name())
                || (request.arguments() != null && !request.arguments().isEmpty())) {
            throw McpError.builder(-32602).message("Invalid prompt request").build();
        }
        return new GetPromptResult(
                "Privacy-safe JVM build workflow",
                List.of(new PromptMessage(Role.USER, new TextContent(MESSAGES.get(request.name())))));
    }
}
