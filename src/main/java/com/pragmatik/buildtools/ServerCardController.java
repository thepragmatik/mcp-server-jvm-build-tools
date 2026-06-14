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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Server Card endpoint for discoverability, plus health/readiness/liveness endpoints.
 * <p>
 * Implements the MCP .well-known pattern for server metadata discovery
 * without needing to connect via the MCP protocol. Exposed at
 * {@code GET /.well-known/mcp-server} when running in Streamable HTTP mode.
 * <p>
 * Also provides {@code /health}, {@code /health/ready}, and {@code /health/live}
 * endpoints for container orchestration and monitoring.
 */
@RestController
public class ServerCardController {

    @Value("${spring.application.name:mcp-server-jvm-build-tools}")
    private String applicationName;

    @Value("${buildtools.version:0.1.1-SNAPSHOT}")
    private String version;

    @GetMapping(value = "/.well-known/mcp-server", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> serverCard() {
        Map<String, Object> card = new LinkedHashMap<>();

        card.put("name", applicationName);
        card.put("version", version);
        card.put("description",
                "MCP Server for JVM build tools: Maven, Gradle, and SBT. " +
                "Execute builds, detect project types, check dependency versions, " +
                "analyze build output, validate configurations, scan credentials, " +
                "and detect dependency conflicts — all through a unified MCP API.");

        card.put("vendor", "The Pragmatik");
        card.put("homepage", "https://github.com/thepragmatik/mcp-server-jvm-build-tools");
        card.put("license", "Apache-2.0");

        List<String> transports = List.of("stdio", "streamable-http");
        card.put("transports", transports);

        List<String> protocolVersions = List.of("2024-11-05", "2025-03-26");
        card.put("mcpVersions", protocolVersions);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", true);
        capabilities.put("resources", true);
        capabilities.put("prompts", true);
        capabilities.put("logging", false);
        card.put("capabilities", capabilities);

        List<Map<String, String>> buildTools = List.of(
                Map.of("name", "maven", "minVersion", "3.6+", "detectionFile", "pom.xml"),
                Map.of("name", "gradle", "minVersion", "7.0+", "detectionFile", "build.gradle(.kts)"),
                Map.of("name", "sbt", "minVersion", "1.0+", "detectionFile", "build.sbt")
        );
        card.put("supportedBuildTools", buildTools);

        Map<String, Object> requirements = new LinkedHashMap<>();
        requirements.put("java", "21+");
        requirements.put("maven_home", "required for Maven builds (MAVEN_HOME env var)");
        requirements.put("gradle", "optional — uses wrapper or PATH fallback");
        requirements.put("sbt", "optional — uses PATH fallback");
        card.put("requirements", requirements);

        List<String> features = List.of(
                "Multi-build-tool execution (Maven, Gradle, SBT)",
                "Automatic build tool detection from project markers",
                "Structured build output analysis with error/warning parsing",
                "Dependency version checking against Maven Central",
                "Dependency conflict detection across build files",
                "Build configuration validation (syntax, required elements)",
                "Credential status scanning (masked, read-only)",
                "Resource exposure for build configs and dependencies",
                "SBT project structure analysis (modules, test frameworks)",
                "Streamable HTTP transport with health check endpoint",
                "Prompt templates for build analysis, dependency audits, failure diagnosis"
        );
        card.put("features", features);

        // Deprecation notices (MCP 2026-07-28)
        Map<String, Object> deprecations = new LinkedHashMap<>();
        deprecations.put("roots", Map.of("deprecated", "2026-07-28", "removal", "2027-07-28"));
        deprecations.put("sampling", Map.of("deprecated", "2026-07-28", "removal", "2027-07-28"));
        deprecations.put("logging", Map.of("deprecated", "2026-07-28", "removal", "2027-07-28"));
        card.put("deprecations", deprecations);

        Map<String, Object> security = new LinkedHashMap<>();
        security.put("transportSecurity", "stdio (local, no network surface); Streamable HTTP with Origin validation");
        security.put("inputValidation", "Shell injection blocking, dangerous flag blocking, path canonicalization");
        security.put("credentialHandling", "Read-only scanning with masked values (only last 3 chars shown)");
        security.put("commandRestrictions", "Length limits (500 chars), character allowlists, rate limiting");
        card.put("security", security);

        Map<String, Object> registry = new LinkedHashMap<>();
        registry.put("namespace", "com.thepragmatik.mcp-server-jvm-build-tools");
        registry.put("smithery", "https://smithery.ai/server/mcp-server-jvm-build-tools");
        registry.put("dockerImage", "thepragmatik/mcp-server-jvm-build-tools:latest");
        card.put("registry", registry);

        return card;
    }

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> health() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("status", "UP");
        h.put("version", version);
        h.put("transport", "streamable-http");
        return h;
    }

    @GetMapping(value = "/health/ready", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> readiness() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "READY");
        r.put("version", version);
        r.put("availableBuildTools", List.of("maven", "gradle", "sbt"));
        return r;
    }

    @GetMapping(value = "/health/live", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> liveness() {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("status", "ALIVE");
        l.put("timestamp", System.currentTimeMillis());
        return l;
    }
}
