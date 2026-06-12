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

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Tool authorization and scoping service.
 * <p>
 * Provides MCP-native tools for checking tool access permissions,
 * managing API key scopes, and auditing tool invocations.
 * Directly addresses OWASP MCP07 (Insufficient Authentication &amp; Authorization)
 * and MCP06 (Insufficient Logging &amp; Monitoring).
 * <p>
 * Permission model:
 * <ul>
 *   <li>12 fine-grained scopes (build:read, build:execute, dependency:read, etc.)</li>
 *   <li>Wildcard {@code *} grants full access</li>
 *   <li>API keys map to one or more scopes</li>
 *   <li>Audit logging records every tool invocation</li>
 * </ul>
 * <p>
 * Registered MCP tools:
 * <ul>
 *   <li>{@code check_tool_authorization} — verify if a tool is authorized</li>
 *   <li>{@code list_available_scopes} — list all permission scopes with tool counts</li>
 *   <li>{@code audit_tool_access} — read recent audit log entries</li>
 *   <li>{@code validate_access_token} — validate token and show granted scopes</li>
 * </ul>
 */
@Service
public class ToolAuthorizationService {

    private final ToolAuditLogger auditLogger;
    private final Map<String, ToolApiKey> apiKeys;
    private final boolean authEnabled;
    private final String authMode;

    public ToolAuthorizationService() {
        boolean enabled = Boolean.parseBoolean(
                System.getProperty("buildtools.auth.enabled", "false"));
        String mode = System.getProperty("buildtools.auth.mode", "permissive");
        String auditPath = System.getProperty("buildtools.audit.path", "");
        boolean auditEnabled = Boolean.parseBoolean(
                System.getProperty("buildtools.audit.enabled", "true"));

        this.authEnabled = enabled;
        this.authMode = mode;
        this.auditLogger = new ToolAuditLogger(auditPath, auditEnabled);
        this.apiKeys = loadApiKeys();
    }

    /**
     * Load API keys from environment variables or system properties.
     * Format: BUILDTOOLS_API_KEY_SCOPE_SCOPENAME=keyvalue
     * Multiple scopes: BUILDTOOLS_API_KEY_0=keyvalue;BUILDTOOLS_API_KEY_0_SCOPES=build:read,build:execute
     */
    private Map<String, ToolApiKey> loadApiKeys() {
        Map<String, ToolApiKey> keys = new LinkedHashMap<>();

        // Env-var based keys: BUILDTOOLS_API_KEY_<name>=<value>
        for (Map.Entry<String, String> env : System.getenv().entrySet()) {
            String name = env.getKey();
            if (name.startsWith("BUILDTOOLS_API_KEY_") && !name.endsWith("_SCOPES")) {
                String suffix = name.substring("BUILDTOOLS_API_KEY_".length());
                String keyValue = env.getValue();
                if (keyValue != null && !keyValue.isBlank()) {
                    String scopesEnv = System.getenv("BUILDTOOLS_API_KEY_" + suffix + "_SCOPES");
                    List<String> scopes = parseScopes(scopesEnv);
                    keys.put(suffix, new ToolApiKey(keyValue, scopes));
                }
            }
        }

        // System-property based keys (same naming convention)
        for (Map.Entry<Object, Object> prop : System.getProperties().entrySet()) {
            String name = prop.getKey().toString();
            if (name.startsWith("buildtools.api.key.") && !name.endsWith(".scopes")) {
                String suffix = name.substring("buildtools.api.key.".length());
                String keyValue = prop.getValue().toString();
                if (!keyValue.isBlank()) {
                    String scopesProp = System.getProperty(
                            "buildtools.api.key." + suffix + ".scopes", "");
                    List<String> scopes = parseScopes(scopesProp);
                    keys.put(suffix, new ToolApiKey(keyValue, scopes));
                }
            }
        }

        // Default development key if none configured
        if (keys.isEmpty()) {
            keys.put("default", new ToolApiKey("dev-key-unsafe-do-not-use-in-production",
                    List.of("*")));
        }

        return Collections.unmodifiableMap(keys);
    }

    private List<String> parseScopes(String scopesStr) {
        if (scopesStr == null || scopesStr.isBlank()) return List.of("*");
        List<String> scopes = new ArrayList<>();
        for (String s : scopesStr.split(",")) {
            String trimmed = s.trim().toLowerCase();
            if (!trimmed.isEmpty()) scopes.add(trimmed);
        }
        return scopes.isEmpty() ? List.of("*") : scopes;
    }

    /**
     * Check whether a specific tool is authorized for a given set of permission scopes.
     * <p>
     * Useful for MCP clients to pre-validate whether they have permission
     * before calling a tool, avoiding denied requests.
     */
    @Tool(name = "check_tool_authorization",
          description = "Check whether a specific MCP tool is authorized for a given set of " +
                        "permission scopes. Returns the authorization decision, matching scopes, " +
                        "and a human-readable explanation. Useful for pre-validation before " +
                        "calling tools.")
    public String checkToolAuthorization(
            @ToolParam(required = true,
                       description = "The MCP tool name to check (e.g., 'execute_build_command', " +
                                     "'check_dependency_version').")
            String toolName,
            @ToolParam(required = true,
                       description = "Comma-separated list of granted scopes " +
                                     "(e.g., 'build:read,build:execute'). Use '*' for full access.")
            String grantedScopes) {

        List<String> scopes;
        if (grantedScopes == null || grantedScopes.isBlank()) {
            scopes = List.of();
        } else {
            scopes = parseScopes(grantedScopes);
        }
        boolean authorized = ToolPermission.isToolAuthorized(toolName, scopes);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool", toolName);
        result.put("authorized", authorized);
        result.put("scopesChecked", scopes);

        // Find which specific scopes grant access
        List<String> matchingScopes = new ArrayList<>();
        if (!scopes.contains("*")) {
            for (String scope : scopes) {
                ToolPermission perm = ToolPermission.fromScope(scope);
                if (perm != null && perm.toolNames().contains(toolName)) {
                    matchingScopes.add(scope);
                }
            }
        }
        result.put("matchingScopes", matchingScopes);

        // Explanation
        if (scopes.contains("*")) {
            result.put("explanation", "Wildcard '*' scope grants access to ALL tools.");
        } else if (authorized) {
            result.put("explanation", "Tool '" + toolName + "' is authorized via scope(s): " +
                    String.join(", ", matchingScopes));
        } else if (scopes.isEmpty()) {
            result.put("explanation", "No scopes granted. Tool '" + toolName +
                    "' requires at least one matching scope.");
        } else {
            result.put("explanation", "Tool '" + toolName +
                    "' is NOT authorized. None of the granted scopes cover this tool. " +
                    "Check available scopes with list_available_scopes.");
        }

        // List required scopes for this tool
        List<String> requiredScopes = new ArrayList<>();
        for (ToolPermission perm : ToolPermission.values()) {
            if (perm.toolNames().contains(toolName)) {
                requiredScopes.add(perm.scope());
            }
        }
        result.put("requiredScopes", requiredScopes);

        // Audit the check
        auditLogger.record("check_tool_authorization", "anonymous", scopes, true, -1);

        return JsonUtils.toJson(result);
    }

    /**
     * List all available permission scopes with their tool coverage.
     */
    @Tool(name = "list_available_scopes",
          description = "List all available permission scopes for tool authorization. " +
                        "Each scope covers a category of tools (e.g., 'build:read' covers " +
                        "detection and validation tools, 'build:execute' covers build commands). " +
                        "Returns scope name, covered tool count, and specific tool names.")
    public String listAvailableScopes() {
        List<Map<String, Object>> scopes = new ArrayList<>();

        for (ToolPermission perm : ToolPermission.values()) {
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("scope", perm.scope());
            scope.put("toolCount", perm.toolNames().size());
            scope.put("tools", new ArrayList<>(perm.toolNames()));
            scopes.add(scope);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalScopes", scopes.size());
        result.put("authEnabled", authEnabled);
        result.put("authMode", authMode);
        result.put("scopes", scopes);

        // Security recommendations
        Map<String, Object> recommendations = new LinkedHashMap<>();
        recommendations.put("principleOfLeastPrivilege",
                "Grant only the scopes an AI agent actually needs. " +
                "Start with build:read + dependency:read and add execute scopes incrementally.");
        recommendations.put("dangerousScopes",
                "build:execute and sbt:execute allow arbitrary command execution. " +
                "Grant these only to trusted agents in controlled environments.");
        recommendations.put("wildcardWarning",
                "The '*' wildcard grants unrestricted access. Avoid in production deployments.");
        result.put("recommendations", recommendations);

        // Known tools without explicit scope coverage (should not happen)
        Set<String> allCovered = new HashSet<>();
        for (ToolPermission perm : ToolPermission.values()) {
            allCovered.addAll(perm.toolNames());
        }
        result.put("totalCoveredTools", allCovered.size());

        auditLogger.record("list_available_scopes", "anonymous", List.of("*"), true, -1);

        return JsonUtils.toJson(result);
    }

    /**
     * Read the most recent audit log entries.
     */
    @Tool(name = "audit_tool_access",
          description = "Read the most recent tool invocation audit log entries. " +
                        "Returns timestamp, tool name, caller identity, authorization status, " +
                        "and duration. Requires the audit logging feature to be enabled. " +
                        "Designed to satisfy OWASP MCP06 logging requirements.")
    public String auditToolAccess(
            @ToolParam(required = false,
                       description = "Number of most recent audit entries to return (default: 20, max: 100).")
            @Schema(description = "Number of most recent audit entries to return")
            Integer count,
            @Schema(allowableValues = {"all", "authorized", "denied"})
            @ToolParam(required = false,
                       description = "Filter by authorization status: 'all' (default), " +
                                     "'authorized', or 'denied'.")
            String filter) {

        int limit = (count != null && count > 0) ? Math.min(count, 100) : 20;
        String filterVal = (filter != null) ? filter.toLowerCase().trim() : "all";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("auditEnabled", auditLogger.isEnabled());
        result.put("auditLogPath", auditLogger.getAuditLogPath());

        if (!auditLogger.isEnabled()) {
            result.put("status", "disabled");
            result.put("message", "Audit logging is disabled. Enable with buildtools.audit.enabled=true");
            result.put("entries", List.of());
            return JsonUtils.toJson(result);
        }

        List<String> rawEntries = auditLogger.tail(limit * 3); // fetch extra for filtering
        List<Map<String, Object>> entries = new ArrayList<>();

        for (String raw : rawEntries) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>)
                        new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Map.class);

                boolean isAuthorized = Boolean.TRUE.equals(entry.get("authorized"));

                if (filterVal.equals("authorized") && !isAuthorized) continue;
                if (filterVal.equals("denied") && isAuthorized) continue;

                entries.add(entry);
                if (entries.size() >= limit) break;
            } catch (Exception ignored) {
                // Skip unparseable entries
            }
        }

        // Reverse to show most recent first
        Collections.reverse(entries);

        result.put("status", "success");
        result.put("entryCount", entries.size());
        result.put("entries", entries);

        // Summary statistics
        long authorizedCount = entries.stream()
                .filter(e -> Boolean.TRUE.equals(e.get("authorized"))).count();
        long deniedCount = entries.size() - authorizedCount;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", entries.size());
        summary.put("authorized", authorizedCount);
        summary.put("denied", deniedCount);
        result.put("summary", summary);

        auditLogger.record("audit_tool_access", "anonymous", List.of("*"), true, -1);

        return JsonUtils.toJson(result);
    }

    /**
     * Validate an access token and return its granted scopes.
     */
    @Tool(name = "validate_access_token",
          description = "Validate an MCP access token (API key) and return the granted " +
                        "permission scopes. Tokens are configured via BUILDTOOLS_API_KEY_* " +
                        "environment variables. Returns the token identity, granted scopes, " +
                        "and expiration status. Token values are never exposed in the response.")
    public String validateAccessToken(
            @ToolParam(required = true,
                       description = "The access token (API key) to validate. " +
                                     "This value is used only for validation and is never logged or stored.")
            String token) {

        Map<String, Object> result = new LinkedHashMap<>();

        if (token == null || token.isBlank()) {
            result.put("valid", false);
            result.put("error", "Token is empty or missing");
            auditLogger.record("validate_access_token", "anonymous", List.of("*"),
                    false, -1);
            return JsonUtils.toJson(result);
        }

        // Hash the token for lookup
        String tokenHash = sha256(token);

        for (Map.Entry<String, ToolApiKey> entry : apiKeys.entrySet()) {
            String keyHash = sha256(entry.getValue().key);
            if (tokenHash.equals(keyHash)) {
                result.put("valid", true);
                result.put("identity", entry.getKey());
                result.put("scopes", entry.getValue().scopes);
                result.put("scopeCount", entry.getValue().scopes.size());
                result.put("hasWildcard", entry.getValue().scopes.contains("*"));

                Map<String, Object> security = new LinkedHashMap<>();
                security.put("hasDangerousScopes",
                        entry.getValue().scopes.contains("build:execute") ||
                        entry.getValue().scopes.contains("sbt:execute"));
                security.put("recommendation",
                        entry.getValue().scopes.contains("*")
                                ? "Wildcard scope detected. Consider restricting to specific scopes."
                                : "Scopes look appropriate.");
                result.put("security", security);

                auditLogger.record("validate_access_token", entry.getKey(),
                        entry.getValue().scopes, true, -1);

                return JsonUtils.toJson(result);
            }
        }

        result.put("valid", false);
        result.put("error", "Token not recognized. Configure tokens via BUILDTOOLS_API_KEY_* " +
                "environment variables or buildtools.api.key.* system properties.");
        result.put("configuredKeyCount", apiKeys.size());

        auditLogger.record("validate_access_token", "anonymous", List.of("*"),
                false, -1);

        return JsonUtils.toJson(result);
    }

    /**
     * SHA-256 hash for token comparison.
     */
    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Internal representation of an API key.
     */
    private record ToolApiKey(String key, List<String> scopes) {}
}
