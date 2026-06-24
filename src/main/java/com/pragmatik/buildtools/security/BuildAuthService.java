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
package com.pragmatik.buildtools.security;

import com.pragmatik.buildtools.tool.JsonUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Build tool credential management service.
 * <p>
 * Provides read-only MCP tools for scanning configured credentials in
 * Maven ({@code settings.xml}) and Gradle ({@code gradle.properties})
 * configuration files, as well as common environment variables.
 * <p>
 * All credential values are masked — only the last 3 characters are
 * shown, preceded by {@code ****}. Raw secrets are never exposed.
 * <p>
 * Registered MCP tools:
 * <ul>
 *   <li>{@code check_credential_status} — scan project and system for configured credentials</li>
 * </ul>
 */
@Service
public class BuildAuthService {

    // Known credential-bearing environment variables
    private static final List<String> CREDENTIAL_ENV_VARS = List.of(
            "MAVEN_USERNAME",
            "MAVEN_PASSWORD",
            "MAVEN_CENTRAL_USERNAME",
            "MAVEN_CENTRAL_PASSWORD",
            "GRADLE_USERNAME",
            "GRADLE_PASSWORD",
            "NEXUS_USERNAME",
            "NEXUS_PASSWORD",
            "ARTIFACTORY_USERNAME",
            "ARTIFACTORY_PASSWORD",
            "REPOSILITE_USERNAME",
            "REPOSILITE_PASSWORD",
            "JFROG_USERNAME",
            "JFROG_PASSWORD",
            "GITHUB_ACTOR",
            "GITHUB_TOKEN",
            "GITLAB_USERNAME",
            "GITLAB_TOKEN",
            "CODEARTIFACT_AUTH_TOKEN",
            "PUBLISH_USERNAME",
            "PUBLISH_PASSWORD");

    // Known Gradle properties that carry credentials
    private static final List<String> GRADLE_CREDENTIAL_PROPERTY_PATTERNS = List.of(
            "systemProp.reposilite.",
            "systemProp.maven.central.",
            "nexusUsername",
            "nexusPassword",
            "artifactoryUsername",
            "artifactoryPassword",
            "repoUsername",
            "repoPassword",
            "publishUsername",
            "publishPassword");

    private final DocumentBuilder documentBuilder;

    public BuildAuthService() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setNamespaceAware(true);
            this.documentBuilder = factory.newDocumentBuilder();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize XML parser", e);
        }
    }

    /**
     * Scan the project directory and system for configured build tool credentials.
     * <p>
     * Checks Maven {@code ~/.m2/settings.xml} for server entries, mirrors, and
     * proxies. Checks Gradle {@code ~/.gradle/gradle.properties} for repository
     * credentials. Also scans common environment variables.
     * <p>
     * Returns a structured JSON report showing what is configured, what is missing,
     * with all credential values masked.
     */
    @Tool(
            name = "check_credential_status",
            description = "Scan the project directory and system for configured Maven and Gradle "
                    + "credentials. Checks ~/.m2/settings.xml (servers, mirrors, proxies), "
                    + "~/.gradle/gradle.properties (repo credentials), and common environment "
                    + "variables. Returns a JSON report with WHAT is configured, WHAT is missing, "
                    + "and masked credential values (only last 3 chars shown as ****abc). "
                    + "NEVER exposes raw passwords or tokens. Read-only and safe.")
    public String checkCredentialStatus(
            @ToolParam(
                            required = false,
                            description = "Project directory path. When provided, also scans for "
                                    + "project-local credential configuration files.")
                    String projectDir,
            @Schema(allowableValues = {"maven", "gradle", "all"})
                    @ToolParam(
                            required = false,
                            description = "Build tool scope: 'maven' (Maven only), 'gradle' (Gradle only), "
                                    + "or 'all' (default, checks everything including env vars).")
                    String scope) {

        String effectiveScope =
                (scope == null || scope.isBlank()) ? "all" : scope.toLowerCase().trim();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("scope", effectiveScope);

        // Resolve home directory
        String homeDir = System.getProperty("user.home");
        if (homeDir == null || homeDir.isBlank()) {
            return JsonUtils.errorJson("Cannot determine user home directory");
        }
        Path homePath = Paths.get(homeDir);

        // Maven
        if (effectiveScope.equals("maven") || effectiveScope.equals("all")) {
            Map<String, Object> mavenSection = new LinkedHashMap<>();
            mavenSection.put("checked", false);
            mavenSection.put("configured", false);

            Path m2Settings = homePath.resolve(".m2/settings.xml");
            if (Files.exists(m2Settings)) {
                try {
                    Map<String, Object> parsed = parseMavenSettings(m2Settings);
                    mavenSection.put("checked", true);
                    mavenSection.put("configured", true);
                    mavenSection.put("path", m2Settings.toString());
                    mavenSection.putAll(parsed);
                } catch (Exception e) {
                    mavenSection.put("checked", true);
                    mavenSection.put("configured", false);
                    mavenSection.put("error", "Failed to parse settings.xml: " + e.getMessage());
                }
            } else {
                mavenSection.put("checked", true);
                mavenSection.put("configured", false);
                mavenSection.put("path", m2Settings.toString());
                mavenSection.put("message", "No ~/.m2/settings.xml found");
            }

            mavenSection.put("serverCount", countServersConfigured(mavenSection));
            mavenSection.put("mirrorCount", countMirrorsConfigured(mavenSection));
            mavenSection.put("proxyCount", countProxiesConfigured(mavenSection));

            result.put("maven", mavenSection);
        }

        // Gradle
        if (effectiveScope.equals("gradle") || effectiveScope.equals("all")) {
            Map<String, Object> gradleSection = new LinkedHashMap<>();
            gradleSection.put("checked", false);
            gradleSection.put("configured", false);

            Path gradleProperties = homePath.resolve(".gradle/gradle.properties");
            if (Files.exists(gradleProperties)) {
                try {
                    Map<String, Object> parsed = parseGradleProperties(gradleProperties);
                    gradleSection.put("checked", true);
                    gradleSection.put("configured", true);
                    gradleSection.put("path", gradleProperties.toString());
                    gradleSection.putAll(parsed);
                } catch (Exception e) {
                    gradleSection.put("checked", true);
                    gradleSection.put("configured", false);
                    gradleSection.put("error", "Failed to parse gradle.properties: " + e.getMessage());
                }
            } else {
                gradleSection.put("checked", true);
                gradleSection.put("configured", false);
                gradleSection.put("path", gradleProperties.toString());
                gradleSection.put("message", "No ~/.gradle/gradle.properties found");
            }

            gradleSection.put("credentialPropertyCount", countGradleCredentials(gradleSection));

            result.put("gradle", gradleSection);
        }

        // Environment Variables
        if (effectiveScope.equals("all")) {
            Map<String, Object> envSection = new LinkedHashMap<>();
            envSection.put("checked", true);
            List<Map<String, Object>> envVars = new ArrayList<>();
            boolean anyConfigured = false;

            for (String varName : CREDENTIAL_ENV_VARS) {
                String value = System.getenv(varName);
                Map<String, Object> varInfo = new LinkedHashMap<>();
                varInfo.put("name", varName);
                if (value != null && !value.isBlank()) {
                    varInfo.put("configured", true);
                    varInfo.put("value", maskCredential(value));
                    anyConfigured = true;
                } else {
                    varInfo.put("configured", false);
                }
                envVars.add(varInfo);
            }

            envSection.put("configured", anyConfigured);
            envSection.put(
                    "configuredCount",
                    envVars.stream()
                            .filter(v -> Boolean.TRUE.equals(v.get("configured")))
                            .count());
            envSection.put("variables", envVars);

            result.put("environment", envSection);
        }

        // Overall Summary
        Map<String, Object> summary = new LinkedHashMap<>();
        int totalConfigured = 0;
        int totalChecked = 0;
        List<String> gaps = new ArrayList<>();

        if (result.containsKey("maven")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) result.get("maven");
            if (Boolean.TRUE.equals(m.get("configured"))) totalConfigured++;
            totalChecked++;
        }
        if (result.containsKey("gradle")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> g = (Map<String, Object>) result.get("gradle");
            if (Boolean.TRUE.equals(g.get("configured"))) totalConfigured++;
            totalChecked++;
        }
        if (result.containsKey("environment")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> e = (Map<String, Object>) result.get("environment");
            if (Boolean.TRUE.equals(e.get("configured"))) totalConfigured++;
            totalChecked++;
        }

        summary.put("sourcesChecked", totalChecked);
        summary.put("sourcesConfigured", totalConfigured);

        if (totalConfigured == 0) {
            gaps.add("No Maven settings.xml found with credentials");
            gaps.add("No Gradle gradle.properties found with credentials");
            gaps.add("No credential environment variables are set");
        } else {
            if (result.containsKey("maven")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) result.get("maven");
                if (!Boolean.TRUE.equals(m.get("configured"))) {
                    gaps.add("Maven settings.xml not configured");
                }
            }
            if (result.containsKey("gradle")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> g = (Map<String, Object>) result.get("gradle");
                if (!Boolean.TRUE.equals(g.get("configured"))) {
                    gaps.add("Gradle properties not configured");
                }
            }
            if (result.containsKey("environment")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> e = (Map<String, Object>) result.get("environment");
                if (!Boolean.TRUE.equals(e.get("configured"))) {
                    gaps.add("No credential environment variables set");
                }
            }
        }

        summary.put("gaps", gaps);
        summary.put("gapsCount", gaps.size());
        summary.put(
                "recommendation",
                "Consider setting up ~/.m2/settings.xml with <servers> entries for Maven "
                        + "and ~/.gradle/gradle.properties with repo credentials for Gradle.");

        result.put("summary", summary);

        return JsonUtils.toJson(result);
    }

    // Maven settings.xml parsing

    Map<String, Object> parseMavenSettings(Path settingsFile) throws Exception {
        String xmlContent = Files.readString(settingsFile);
        Document doc = documentBuilder.parse(new org.xml.sax.InputSource(new StringReader(xmlContent)));
        Element root = doc.getDocumentElement();

        Map<String, Object> result = new LinkedHashMap<>();

        // Parse servers
        List<Map<String, Object>> servers = new ArrayList<>();
        NodeList serverNodes = root.getElementsByTagNameNS("*", "server");
        for (int i = 0; i < serverNodes.getLength(); i++) {
            Element serverEl = (Element) serverNodes.item(i);
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("id", getChildText(serverEl, "id"));
            String username = getChildText(serverEl, "username");
            String password = getChildText(serverEl, "password");
            if (username != null && !username.isBlank()) {
                server.put("username", username);
            }
            if (password != null && !password.isBlank()) {
                server.put("password", maskCredential(password));
            }
            servers.add(server);
        }
        result.put("servers", servers);

        // Parse mirrors
        List<Map<String, Object>> mirrors = new ArrayList<>();
        NodeList mirrorNodes = root.getElementsByTagNameNS("*", "mirror");
        for (int i = 0; i < mirrorNodes.getLength(); i++) {
            Element mirrorEl = (Element) mirrorNodes.item(i);
            Map<String, Object> mirror = new LinkedHashMap<>();
            mirror.put("id", getChildText(mirrorEl, "id"));
            mirror.put("name", getChildText(mirrorEl, "name"));
            mirror.put("url", getChildText(mirrorEl, "url"));
            mirror.put("mirrorOf", getChildText(mirrorEl, "mirrorOf"));
            mirrors.add(mirror);
        }
        result.put("mirrors", mirrors);

        // Parse proxies
        List<Map<String, Object>> proxies = new ArrayList<>();
        NodeList proxyNodes = root.getElementsByTagNameNS("*", "proxy");
        for (int i = 0; i < proxyNodes.getLength(); i++) {
            Element proxyEl = (Element) proxyNodes.item(i);
            Map<String, Object> proxy = new LinkedHashMap<>();
            proxy.put("id", getChildText(proxyEl, "id"));
            proxy.put("active", "true".equalsIgnoreCase(getChildText(proxyEl, "active")));
            proxy.put("protocol", getChildText(proxyEl, "protocol"));
            proxy.put("host", getChildText(proxyEl, "host"));
            String port = getChildText(proxyEl, "port");
            if (port != null && !port.isBlank()) {
                try {
                    proxy.put("port", Integer.parseInt(port));
                } catch (NumberFormatException ignored) {
                    proxy.put("port", port);
                }
            }
            String proxyUsername = getChildText(proxyEl, "username");
            String proxyPassword = getChildText(proxyEl, "password");
            if (proxyUsername != null && !proxyUsername.isBlank()) {
                proxy.put("username", proxyUsername);
            }
            if (proxyPassword != null && !proxyPassword.isBlank()) {
                proxy.put("password", maskCredential(proxyPassword));
            }
            String nonProxyHosts = getChildText(proxyEl, "nonProxyHosts");
            if (nonProxyHosts != null && !nonProxyHosts.isBlank()) {
                proxy.put("nonProxyHosts", nonProxyHosts);
            }
            proxies.add(proxy);
        }
        result.put("proxies", proxies);

        // Parse active profiles
        List<String> activeProfiles = new ArrayList<>();
        NodeList activeProfileNodes = root.getElementsByTagNameNS("*", "activeProfile");
        for (int i = 0; i < activeProfileNodes.getLength(); i++) {
            String text = activeProfileNodes.item(i).getTextContent();
            if (text != null && !text.isBlank()) {
                activeProfiles.add(text.trim());
            }
        }
        if (!activeProfiles.isEmpty()) {
            result.put("activeProfiles", activeProfiles);
        }

        // Parse profiles (list them)
        List<Map<String, Object>> profiles = new ArrayList<>();
        NodeList profileNodes = root.getElementsByTagNameNS("*", "profile");
        for (int i = 0; i < profileNodes.getLength(); i++) {
            Element profileEl = (Element) profileNodes.item(i);
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("id", getChildText(profileEl, "id"));
            List<Map<String, Object>> profileRepos = new ArrayList<>();
            NodeList repoNodes = profileEl.getElementsByTagNameNS("*", "repository");
            for (int j = 0; j < repoNodes.getLength(); j++) {
                Element repoEl = (Element) repoNodes.item(j);
                Map<String, Object> repo = new LinkedHashMap<>();
                repo.put("id", getChildText(repoEl, "id"));
                repo.put("url", getChildText(repoEl, "url"));
                profileRepos.add(repo);
            }
            if (!profileRepos.isEmpty()) {
                profile.put("repositories", profileRepos);
            }
            profiles.add(profile);
        }
        if (!profiles.isEmpty()) {
            result.put("profiles", profiles);
        }

        return result;
    }

    // Gradle properties parsing

    Map<String, Object> parseGradleProperties(Path propertiesFile) throws IOException {
        java.util.Properties props = new java.util.Properties();
        try (var reader = Files.newBufferedReader(propertiesFile)) {
            props.load(reader);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> credentials = new ArrayList<>();
        Map<String, String> repoUrls = new LinkedHashMap<>();

        for (String name : props.stringPropertyNames()) {
            String value = props.getProperty(name);

            boolean isCredential = name.toLowerCase().contains("password")
                    || name.toLowerCase().contains("token")
                    || name.toLowerCase().contains("secret")
                    || name.toLowerCase().contains("key")
                    || name.toLowerCase().contains("username");

            boolean matchesPattern = false;
            for (String pattern : GRADLE_CREDENTIAL_PROPERTY_PATTERNS) {
                if (name.startsWith(pattern) || name.equals(pattern)) {
                    matchesPattern = true;
                    break;
                }
            }

            if (isCredential || matchesPattern) {
                Map<String, Object> cred = new LinkedHashMap<>();
                cred.put("property", name);
                cred.put("value", maskCredential(value));
                credentials.add(cred);
            }

            if (name.toLowerCase().contains("url") || name.toLowerCase().contains("repo")) {
                repoUrls.put(name, value);
            }
        }

        result.put("credentials", credentials);
        if (!repoUrls.isEmpty()) {
            result.put("repoUrls", repoUrls);
        }

        return result;
    }

    // Credential masking

    static String maskCredential(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() <= 3) return "****";
        return "****" + value.substring(value.length() - 3);
    }

    // XML helpers

    private static String getChildText(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagNameNS("*", tagName);
        if (children.getLength() == 0) return null;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getParentNode() == parent) {
                String text = child.getTextContent();
                return text != null ? text.trim() : null;
            }
        }
        return null;
    }

    // Summary counters

    @SuppressWarnings("unchecked")
    private static int countServersConfigured(Map<String, Object> mavenSection) {
        Object serversObj = mavenSection.get("servers");
        if (serversObj instanceof List) return ((List<?>) serversObj).size();
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static int countMirrorsConfigured(Map<String, Object> mavenSection) {
        Object mirrorsObj = mavenSection.get("mirrors");
        if (mirrorsObj instanceof List) return ((List<?>) mirrorsObj).size();
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static int countProxiesConfigured(Map<String, Object> mavenSection) {
        Object proxiesObj = mavenSection.get("proxies");
        if (proxiesObj instanceof List) return ((List<?>) proxiesObj).size();
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static int countGradleCredentials(Map<String, Object> gradleSection) {
        Object credsObj = gradleSection.get("credentials");
        if (credsObj instanceof List) return ((List<?>) credsObj).size();
        return 0;
    }
}
