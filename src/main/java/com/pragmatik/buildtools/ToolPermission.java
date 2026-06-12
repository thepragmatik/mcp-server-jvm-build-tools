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

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * MCP tool permission scopes for fine-grained authorization.
 *
 * Each scope maps to a category of MCP tools. API keys can be
 * granted one or more scopes. The wildcard {@code *} grants
 * access to all tools.
 *
 * Scope naming follows the {@code category:access-level} convention,
 * aligned with OAuth 2.1 resource indicator patterns.
 */
public enum ToolPermission {

    BUILD_READ("build:read", Set.of(
            "get_build_tool_version", "list_build_tools", "detect_build_tool",
            "validate_build_configuration")),

    BUILD_EXECUTE("build:execute", Set.of(
            "execute_build_command")),

    BUILD_PROFILE("build:profile", Set.of(
            "profile_build", "analyze_build_performance")),

    DEPENDENCY_READ("dependency:read", Set.of(
            "check_dependency_version", "list_dependencies")),

    DEPENDENCY_MANAGE("dependency:manage", Set.of(
            "detect_dependency_conflicts")),

    CREDENTIAL_READ("credential:read", Set.of(
            "check_credential_status")),

    JAVA_READ("java:read", Set.of(
            "check_java_compatibility")),

    SBT_READ("sbt:read", Set.of(
            "check_sbt_project", "list_sbt_modules")),

    SBT_EXECUTE("sbt:execute", Set.of(
            "execute_sbt_command")),

    PROMPT_READ("prompt:read", Set.of(
            "get_build_tool_prompt")),

    RESOURCE_READ("resource:read", Set.of(
            "list_build_resources", "read_build_resource",
            "list_dependency_resources", "read_dependency_resource")),

    RESOURCE_TEMPLATE("resource:template", Set.of(
            "list_resource_templates", "get_resource_template"));

    private final String scope;
    private final Set<String> toolNames;

    ToolPermission(String scope, Set<String> toolNames) {
        this.scope = scope;
        this.toolNames = toolNames;
    }

    public String scope() { return scope; }
    public Set<String> toolNames() { return toolNames; }

    public static ToolPermission fromScope(String scope) {
        if (scope == null || scope.isBlank()) return null;
        String s = scope.trim().toLowerCase();
        for (ToolPermission p : values()) {
            if (p.scope.equals(s)) return p;
        }
        return null;
    }

    public static boolean isToolAuthorized(String toolName, List<String> grantedScopes) {
        if (grantedScopes == null || grantedScopes.isEmpty()) return false;
        if (grantedScopes.contains("*")) return true;
        for (String scope : grantedScopes) {
            ToolPermission perm = fromScope(scope);
            if (perm != null && perm.toolNames.contains(toolName)) return true;
        }
        return false;
    }

    public static List<String> allScopes() {
        return Arrays.stream(values()).map(ToolPermission::scope).toList();
    }
}
