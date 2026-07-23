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
package com.pragmatik.buildtools.dependency;

import com.pragmatik.buildtools.build.BuildTool;
import com.pragmatik.buildtools.build.BuildToolProvider;
import com.pragmatik.buildtools.dependency.pom.PomDependencyResolver;
import com.pragmatik.buildtools.dependency.pom.PomModel.AnalysisResult;
import com.pragmatik.buildtools.dependency.security.CveLookupService;
import com.pragmatik.buildtools.dependency.security.CveLookupService.VulnerabilityEntry;
import com.pragmatik.buildtools.tool.JsonUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Dependency intelligence service for Maven Central version lookups.
 * <p>
 * Queries the Maven Central REST API for {@code maven-metadata.xml} to
 * retrieve available versions of a dependency. Supports
 * version filtering and project-aware context when a project directory
 * is provided.
 * <p>
 * <b>Differentiation from arvindand/maven-tools-mcp:</b> This service is
 * integrated with our build tool detection infrastructure. When
 * {@code projectDir} is provided, we auto-detect the build tool (Maven,
 * Gradle, or SBT) and include project-specific context in the response
 * (e.g., dependency declaration syntax for the detected tool).
 */
@Service
public class DependencyService {

    private static final String MAVEN_CENTRAL_BASE = "https://repo1.maven.org/maven2";

    private final HttpClient httpClient;
    private final BuildToolProvider buildToolProvider;
    private final PomDependencyResolver pomResolver;
    private final CveLookupService cveLookup;

    public DependencyService(BuildToolProvider buildToolProvider) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.buildToolProvider = buildToolProvider;
        this.pomResolver = new PomDependencyResolver();
        this.cveLookup = new CveLookupService();
    }

    /**
     * Check if a newer version exists for a Maven Central dependency.
     * <p>
     * Queries {@code maven-metadata.xml} from Maven Central, extracts all
     * published versions, classifies stability (STABLE, RC, MILESTONE, BETA,
     * ALPHA, SNAPSHOT), and optionally compares against a current version
     * to determine upgrade type (MAJOR, MINOR, PATCH).
     * <p>
     * When {@code projectDir} is provided, auto-detects the build tool and
     * includes project-specific context (e.g., the correct dependency
     * declaration syntax for Maven, Gradle, or SBT).
     */
    @Tool(
            name = "check_dependency_version",
            description = "Check if a newer version exists for a Maven Central dependency. "
                    + "Use this to determine whether a dependency can be upgraded. "
                    + "Returns a JSON object with latest version, all versions, "
                    + "stability classification, and upgrade type (major/minor/patch). "
                    + "Provide projectDir to get build-tool-specific dependency syntax. "
                    + "Set includeSecurityInfo=true to include CVE vulnerability data from OSV.dev.")
    public String checkDependencyVersion(
            @ToolParam(required = true, description = "Maven group ID (e.g., 'org.springframework.boot')")
                    String groupId,
            @ToolParam(required = true, description = "Maven artifact ID (e.g., 'spring-boot-starter-web')")
                    String artifactId,
            @ToolParam(
                            required = false,
                            description = "Current version to compare against. Omit to just get the latest version.")
                    String currentVersion,
            @Schema(allowableValues = {"RELEASE", "LATEST", "SNAPSHOT", "ALL"})
                    @ToolParam(
                            required = false,
                            description = "Version preference: RELEASE (default), LATEST, SNAPSHOT, or ALL")
                    String versionPreference,
            @ToolParam(
                            required = false,
                            description =
                                    "Project directory path. When provided, auto-detects build tool and includes project context.")
                    String projectDir,
            @ToolParam(
                            required = false,
                            description =
                                    "Include CVE/security vulnerability information from OSV.dev. Default false for backward compatibility.")
                    boolean includeSecurityInfo) {

        VersionPreference filter = parseVersionPreference(versionPreference);

        if (groupId == null || groupId.isBlank()) {
            return JsonUtils.errorJson("groupId is required");
        }
        if (artifactId == null || artifactId.isBlank()) {
            return JsonUtils.errorJson("artifactId is required");
        }

        try {
            String groupPath = groupId.replace('.', '/');
            String metadataUrl =
                    String.format("%s/%s/%s/maven-metadata.xml", MAVEN_CENTRAL_BASE, groupPath, artifactId);

            HttpRequest request =
                    HttpRequest.newBuilder().uri(URI.create(metadataUrl)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                return JsonUtils.errorJson("Dependency not found on Maven Central: " + groupId + ":" + artifactId);
            }
            if (response.statusCode() != 200) {
                return JsonUtils.errorJson(
                        "Maven Central returned HTTP " + response.statusCode() + " for " + groupId + ":" + artifactId);
            }

            String xmlBody = response.body();
            if (xmlBody == null || xmlBody.isBlank()) {
                return JsonUtils.errorJson("No metadata found for " + groupId + ":" + artifactId);
            }

            Map<String, Object> result = parseMetadata(groupId, artifactId, xmlBody, filter);

            if (currentVersion != null && !currentVersion.isBlank()) {
                enrichWithVersionComparison(result, currentVersion, filter);
            }

            if (projectDir != null && !projectDir.isBlank()) {
                enrichWithProjectContext(result, projectDir);
            }

            // Enrich with CVE security information if requested
            if (includeSecurityInfo && currentVersion != null && !currentVersion.isBlank()) {
                enrichWithSecurityInfo(result, groupId, artifactId, currentVersion);
            }

            return JsonUtils.toJson(result);

        } catch (IOException e) {
            return JsonUtils.errorJson("Network error checking dependency version: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JsonUtils.errorJson("Request interrupted checking dependency version");
        } catch (Exception e) {
            return JsonUtils.errorJson("Error checking dependency version: " + e.getMessage());
        }
    }

    /**
     * Analyze all dependencies in a Maven project's POM file.
     * <p>
     * Reads pom.xml, walks the parent POM chain, resolves
     * {@code <dependencyManagement>} (including imported BOMs), interpolates
     * properties, and classifies every dependency as EXPLICIT (directly
     * declared), MANAGED (version inherited from depMgmt), or OVERRIDE
     * (explicit version that differs from managed).
     * <p>
     * <b>Pure Java — no Maven execution required.</b> This tool is advisory
     * and works even when Maven is not installed. Gradle and SBT projects
     * get a clear error message.
     */
    @Tool(
            name = "analyze_pom_dependencies",
            description = "Analyze all dependencies declared in a Maven project's pom.xml. "
                    + "Walks the parent POM chain, resolves dependencyManagement (including BOM imports), "
                    + "interpolates properties, and classifies each dependency as EXPLICIT, MANAGED, or OVERRIDE. "
                    + "Returns a structured JSON report with dependency classifications, managed versions, "
                    + "imported BOMs, property substitutions, parent chain, and warnings. "
                    + "Pure Java analysis — no Maven execution required. "
                    + "Requires a Maven POM project; Gradle/SBT projects get a clear error message.")
    public String analyzePomDependencies(
            @ToolParam(required = true, description = "Path to the Maven project directory containing pom.xml")
                    String projectDir,
            @ToolParam(
                            required = false,
                            description =
                                    "Whether to resolve transitive dependencies (reserved for future use; default false)")
                    boolean resolveTransitive,
            @ToolParam(
                            required = false,
                            description = "Path to the local Maven repository. Defaults to ~/.m2/repository.")
                    String localRepositoryPath) {

        if (projectDir == null || projectDir.isBlank()) {
            return JsonUtils.errorJson("projectDir is required");
        }

        Path dir;
        try {
            dir = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            return JsonUtils.errorJson("Cannot resolve project directory: " + e.getMessage());
        }
        if (!Files.isDirectory(dir)) {
            return JsonUtils.errorJson("Project directory is not valid: " + projectDir);
        }

        try {
            PomDependencyResolver resolver = localRepositoryPath != null && !localRepositoryPath.isBlank()
                    ? new PomDependencyResolver(Path.of(localRepositoryPath))
                    : pomResolver;

            AnalysisResult result = resolver.resolve(dir, resolveTransitive);
            return JsonUtils.toJson(result.toMap());
        } catch (IllegalArgumentException e) {
            return JsonUtils.errorJson(e.getMessage());
        } catch (IOException e) {
            return JsonUtils.errorJson("Error analyzing POM dependencies: " + e.getMessage());
        }
    }

    /**
     * Parse the maven-metadata.xml response and extract version information.
     */
    Map<String, Object> parseMetadata(String groupId, String artifactId, String xmlBody, VersionPreference filter) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groupId", groupId);
        result.put("artifactId", artifactId);
        result.put("status", "success");

        // Extract <versioning> section
        String versioning = extractTag(xmlBody, "versioning");
        String latest = extractTag(versioning, "latest");
        String release = extractTag(versioning, "release");
        String lastUpdated = extractTag(versioning, "lastUpdated");

        // Extract all versions
        String versionsBlock = extractTag(versioning, "versions");
        List<String> allVersions = extractAllTags(versionsBlock, "version");

        result.put("latestVersion", latest != null ? latest : release);
        result.put("releaseVersion", release);
        if (lastUpdated != null) {
            result.put("lastUpdated", lastUpdated);
        }

        // Classify and filter versions
        List<String> stableVersions = new ArrayList<>();
        List<String> preReleaseVersions = new ArrayList<>();
        List<String> allClassified = new ArrayList<>();

        for (String v : allVersions) {
            allClassified.add(classifyVersion(v));
        }

        for (String v : allVersions) {
            Stability s = Stability.fromVersion(v);
            if (s == Stability.STABLE) {
                stableVersions.add(v);
            } else {
                preReleaseVersions.add(v);
            }
        }

        int totalVersions = allVersions.size();

        switch (filter) {
            case RELEASE:
                result.put("versionCount", stableVersions.size());
                result.put("totalVersions", totalVersions);
                result.put("filteredVersions", stableVersions);
                if (!stableVersions.isEmpty()) {
                    result.put("latestStable", stableVersions.get(stableVersions.size() - 1));
                }
                break;
            case LATEST:
                result.put("versionCount", allVersions.size());
                result.put("totalVersions", totalVersions);
                result.put("stableVersions", stableVersions);
                List<String> preferred = new ArrayList<>(stableVersions);
                for (String v : preReleaseVersions) {
                    preferred.add(v + " [PRE-RELEASE]");
                }
                result.put("filteredVersions", preferred);
                if (!stableVersions.isEmpty()) {
                    result.put("latestStable", stableVersions.get(stableVersions.size() - 1));
                }
                break;
            case SNAPSHOT:
                result.put("versionCount", allVersions.size());
                result.put("totalVersions", totalVersions);
                result.put("filteredVersions", allClassified);
                if (!allVersions.isEmpty()) {
                    result.put("latestVersion", allVersions.get(allVersions.size() - 1));
                }
                break;
            case ALL:
                result.put("versionCount", allVersions.size());
                result.put("totalVersions", totalVersions);
                result.put("filteredVersions", allClassified);
                break;
        }

        return result;
    }

    /**
     * Enrich the result with version comparison data.
     */
    void enrichWithVersionComparison(Map<String, Object> result, String currentVersion, VersionPreference filter) {
        result.put("currentVersion", currentVersion);

        Object latestObj = result.get("latestVersion");
        Object stableObj = result.get("latestStable");

        String compareTarget = null;
        if (filter == VersionPreference.RELEASE
                || filter == VersionPreference.LATEST
                || filter == VersionPreference.SNAPSHOT) {
            compareTarget = stableObj != null ? stableObj.toString() : null;
        }
        if (compareTarget == null) {
            compareTarget = latestObj != null ? latestObj.toString() : null;
        }

        if (compareTarget == null) {
            result.put("upgradeAvailable", false);
            return;
        }

        String upgradeType = computeUpgradeType(currentVersion, compareTarget);
        boolean isNewer = compareVersions(compareTarget, currentVersion) > 0;

        result.put("latestVersion", compareTarget);
        result.put("upgradeAvailable", isNewer);
        if (isNewer) {
            result.put("upgradeType", upgradeType);
            result.put("recommended", true);
        }
    }

    /**
     * Enrich the result with project-specific context.
     */
    void enrichWithProjectContext(Map<String, Object> result, String projectDir) {
        try {
            Path dir = Path.of(projectDir).toRealPath();
            if (!Files.isDirectory(dir)) return;

            BuildTool tool = buildToolProvider.resolve(null, dir);
            result.put("detectedBuildTool", tool.getName());

            String groupId = (String) result.get("groupId");
            String artifactId = (String) result.get("artifactId");
            String version = (String) result.getOrDefault("latestVersion", result.get("latestStable"));

            Map<String, String> syntax = new LinkedHashMap<>();
            switch (tool.getName()) {
                case "maven":
                    syntax.put(
                            "maven",
                            String.format(
                                    "<dependency>\n  <groupId>%s</groupId>\n"
                                            + "  <artifactId>%s</artifactId>\n  <version>%s</version>\n"
                                            + "</dependency>",
                                    groupId, artifactId, version));
                    break;
                case "gradle":
                    syntax.put("gradle", String.format("implementation('%s:%s:%s')", groupId, artifactId, version));
                    break;
                case "sbt":
                    syntax.put(
                            "sbt",
                            String.format(
                                    "libraryDependencies += \"%s\" %% \"%s\" %% \"%s\"", groupId, artifactId, version));
                    break;
            }
            if (!syntax.isEmpty()) {
                result.put("dependencySyntax", syntax);
            }
        } catch (Exception e) {
            // If project context can't be determined, just omit it
            System.err.println("[DependencyService] Could not enrich project context: " + e.getMessage());
        }
    }

    // ─── Version parsing utilities ──────────────────────────────────────

    static String extractTag(String xml, String tagName) {
        if (xml == null) return null;
        String openTag = "<" + tagName + ">";
        String closeTag = "</" + tagName + ">";
        int start = xml.indexOf(openTag);
        if (start < 0) return null;
        start += openTag.length();
        int end = xml.indexOf(closeTag, start);
        if (end < 0) return null;
        return xml.substring(start, end).trim();
    }

    static List<String> extractAllTags(String xml, String tagName) {
        List<String> values = new ArrayList<>();
        if (xml == null) return values;
        String openTag = "<" + tagName + ">";
        String closeTag = "</" + tagName + ">";
        int pos = 0;
        while (true) {
            int start = xml.indexOf(openTag, pos);
            if (start < 0) break;
            start += openTag.length();
            int end = xml.indexOf(closeTag, start);
            if (end < 0) break;
            values.add(xml.substring(start, end).trim());
            pos = end + closeTag.length();
        }
        return values;
    }

    static VersionPreference parseVersionPreference(String filter) {
        if (filter == null || filter.isBlank()) return VersionPreference.RELEASE;
        try {
            return VersionPreference.valueOf(filter.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return VersionPreference.RELEASE;
        }
    }

    static String classifyVersion(String version) {
        Stability s = Stability.fromVersion(version);
        if (s == Stability.STABLE) return version;
        return version + " [" + s.name() + "]";
    }

    /**
     * Compare two version strings. Returns a negative, zero, or positive integer
     * as the first version is less than, equal to, or greater than the second.
     */
    static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("[.\\-]");
        String[] parts2 = v2.split("[.\\-]");
        int maxLen = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < maxLen; i++) {
            String p1 = i < parts1.length ? parts1[i] : "0";
            String p2 = i < parts2.length ? parts2[i] : "0";

            // Try numeric comparison first
            try {
                int n1 = Integer.parseInt(p1.replaceAll("[^0-9].*$", ""));
                int n2 = Integer.parseInt(p2.replaceAll("[^0-9].*$", ""));
                int cmp = Integer.compare(n1, n2);
                if (cmp != 0) return cmp;
            } catch (NumberFormatException e) {
                int cmp = p1.compareTo(p2);
                if (cmp != 0) return cmp;
            }
        }
        return 0;
    }

    static String computeUpgradeType(String current, String latest) {
        String[] cur = current.split("[.\\-]");
        String[] lat = latest.split("[.\\-]");
        if (cur.length == 0 || lat.length == 0) return "UNKNOWN";

        try {
            int curMajor = Integer.parseInt(cur[0].replaceAll("[^0-9].*$", ""));
            int latMajor = Integer.parseInt(lat[0].replaceAll("[^0-9].*$", ""));
            if (curMajor != latMajor) return "MAJOR";
        } catch (NumberFormatException ignored) {
        }

        if (cur.length > 1 && lat.length > 1) {
            try {
                int curMinor = Integer.parseInt(cur[1].replaceAll("[^0-9].*$", ""));
                int latMinor = Integer.parseInt(lat[1].replaceAll("[^0-9].*$", ""));
                if (curMinor != latMinor) return "MINOR";
            } catch (NumberFormatException ignored) {
            }
        }

        return "PATCH";
    }

    // ─── Security enrichment (F2) ──────────────────────────────────────

    /**
     * Enrich a dependency version check result with CVE vulnerability data.
     */
    void enrichWithSecurityInfo(Map<String, Object> result, String groupId, String artifactId, String currentVersion) {
        try {
            List<VulnerabilityEntry> vulns = cveLookup.lookup(groupId, artifactId, currentVersion);

            Map<String, Object> security = new LinkedHashMap<>();
            security.put("cveCount", vulns.size());

            String highest = "NONE";
            for (VulnerabilityEntry v : vulns) {
                if (CveLookupService.meetsThreshold(v.severity(), highest)) {
                    highest = v.severity();
                }
            }
            security.put("highestSeverity", highest);

            List<Map<String, Object>> vulnList = new ArrayList<>();
            for (VulnerabilityEntry v : vulns) {
                Map<String, Object> vmap = new LinkedHashMap<>();
                vmap.put("id", v.id());
                if (v.summary() != null) vmap.put("summary", v.summary());
                vmap.put("severity", v.severity());
                if (v.fixedIn() != null) vmap.put("fixedIn", v.fixedIn());
                if (v.cvssScore() > 0) vmap.put("cvssScore", v.cvssScore());
                vulnList.add(vmap);
            }
            security.put("vulnerabilities", vulnList);

            result.put("security", security);
        } catch (Exception e) {
            // Network failures shouldn't break the version check — return partial result
            Map<String, Object> security = new LinkedHashMap<>();
            security.put("cveCount", 0);
            security.put("highestSeverity", "UNKNOWN");
            security.put("warning", "CVE lookup failed: " + e.getMessage());
            security.put("vulnerabilities", List.of());
            result.put("security", security);
        }
    }

    /**
     * Bulk-scan a project's direct dependencies for known vulnerabilities.
     * <p>
     * Parses the project's pom.xml or build.gradle to extract direct dependencies,
     * then queries OSV.dev for each one. Returns a prioritized vulnerability report
     * filtered by severity threshold.
     * <p>
     * <b>Performance:</b> Scans one dependency at a time with a 1-hour in-memory
     * cache. For projects with 100+ dependencies, the scan may take several seconds.
     * Set a higher {@code severityThreshold} to get faster results.
     */
    @Tool(
            name = "scan_dependency_cves",
            description = "Scan a project's direct dependencies for known vulnerabilities (CVEs) using OSV.dev. "
                    + "Parses pom.xml or build.gradle to extract dependencies, queries OSV.dev for each, "
                    + "and returns a prioritized vulnerability report filtered by severity threshold. "
                    + "Use this to audit a project's dependencies for security issues. "
                    + "Default threshold is HIGH (includes HIGH and CRITICAL). "
                    + "Rate-limited — large projects may take several seconds.")
    public String scanDependencyCves(
            @ToolParam(required = true, description = "Path to the project directory containing build files")
                    String projectDir,
            @Schema(allowableValues = {"CRITICAL", "HIGH", "MEDIUM", "LOW", "ALL"})
                    @ToolParam(
                            required = false,
                            description = "Minimum severity to include: CRITICAL, HIGH (default), MEDIUM, LOW, or ALL")
                    String severityThreshold) {

        if (projectDir == null || projectDir.isBlank()) {
            return JsonUtils.errorJson("projectDir is required");
        }

        Path dir;
        try {
            dir = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            return JsonUtils.errorJson("Cannot resolve project directory: " + e.getMessage());
        }
        if (!Files.isDirectory(dir)) {
            return JsonUtils.errorJson("Project directory is not valid: " + projectDir);
        }

        String threshold =
                severityThreshold != null && !severityThreshold.isBlank() ? severityThreshold.toUpperCase() : "HIGH";

        Map<String, Object> result = new LinkedHashMap<>();

        // Detect build tool and file
        String tool = null;
        Path buildFile = null;

        if (Files.exists(dir.resolve("pom.xml"))) {
            tool = "maven";
            buildFile = dir.resolve("pom.xml");
        } else if (Files.exists(dir.resolve("build.gradle.kts"))) {
            tool = "gradle";
            buildFile = dir.resolve("build.gradle.kts");
        } else if (Files.exists(dir.resolve("build.gradle"))) {
            tool = "gradle";
            buildFile = dir.resolve("build.gradle");
        }

        if (buildFile == null) {
            return JsonUtils.errorJson(
                    "No build files found (pom.xml, build.gradle, build.gradle.kts). " + "Cannot scan dependencies.");
        }

        result.put("project", Map.of("tool", tool, "dir", dir.toString()));

        try {
            String content = Files.readString(buildFile);
            List<CveLookupService.PackageRef> packages = extractPackages(content, tool);

            // Bulk lookup
            Map<String, List<VulnerabilityEntry>> scanResults = cveLookup.bulkLookup(packages);

            // Build vulnerability report
            List<Map<String, Object>> vulnerabilities = new ArrayList<>();
            int totalDeps = packages.size();
            int vulnerableDeps = 0;
            int criticalCount = 0;
            int highCount = 0;
            List<String> warnings = new ArrayList<>();

            for (CveLookupService.PackageRef pkg : packages) {
                String key = pkg.groupId() + ":" + pkg.artifactId() + ":" + pkg.version();
                List<VulnerabilityEntry> vulns = scanResults.getOrDefault(key, List.of());

                // Filter by threshold
                List<VulnerabilityEntry> filtered = vulns.stream()
                        .filter(v ->
                                threshold.equals("ALL") || CveLookupService.meetsThreshold(v.severity(), threshold))
                        .toList();

                if (!filtered.isEmpty()) {
                    vulnerableDeps++;
                    Map<String, Object> depVuln = new LinkedHashMap<>();
                    depVuln.put("dependency", key);
                    List<Map<String, Object>> cveList = new ArrayList<>();
                    for (VulnerabilityEntry v : filtered) {
                        cveList.add(v.toMap());
                        if ("CRITICAL".equals(v.severity())) criticalCount++;
                        else if ("HIGH".equals(v.severity())) highCount++;
                    }
                    depVuln.put("cves", cveList);

                    // Recommendation
                    String recommendation = "No fix version available";
                    for (VulnerabilityEntry v : filtered) {
                        if (v.fixedIn() != null) {
                            recommendation = "Upgrade to " + v.fixedIn() + " or later (PATCH upgrade)";
                            break;
                        }
                    }
                    depVuln.put("recommendation", recommendation);

                    vulnerabilities.add(depVuln);
                }
            }

            result.put(
                    "scanSummary",
                    Map.of(
                            "totalDeps", totalDeps,
                            "vulnerableDeps", vulnerableDeps,
                            "criticalCount", criticalCount,
                            "highCount", highCount));

            result.put("vulnerabilities", vulnerabilities);
            result.put("scannedAt", java.time.Instant.now().toString());

            if (!warnings.isEmpty()) {
                result.put("warnings", warnings);
            }

            return JsonUtils.toJson(result);

        } catch (IOException e) {
            return JsonUtils.errorJson("Error scanning dependencies: " + e.getMessage());
        }
    }

    /**
     * Extract package references from build files.
     */
    private List<CveLookupService.PackageRef> extractPackages(String content, String tool) {
        List<CveLookupService.PackageRef> packages = new ArrayList<>();

        if ("maven".equals(tool)) {
            // Extract <dependency> blocks from POM
            String depsBlock = extractTag(content, "dependencies");
            if (depsBlock == null) return packages;

            String[] depSections = depsBlock.split("</dependency>");
            for (String section : depSections) {
                int start = section.indexOf("<dependency>");
                if (start < 0) continue;
                String depXml = section.substring(start);
                String g = extractTag(depXml, "groupId");
                String a = extractTag(depXml, "artifactId");
                String v = extractTag(depXml, "version");
                if (g != null && a != null && v != null && !v.contains("${")) {
                    packages.add(new CveLookupService.PackageRef(g, a, v));
                }
            }
        } else if ("gradle".equals(tool)) {
            // Extract dependency declarations from Gradle build file
            // Match implementation('group:artifact:version') patterns
            java.util.regex.Pattern depPattern = java.util.regex.Pattern.compile(
                    "(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly)\\s*[(']\"?"
                            + "([^:'\"\\s]+):([^:'\"\\s]+):([^:'\"\\s)]+)\"?[')]");
            java.util.regex.Matcher m = depPattern.matcher(content);
            while (m.find()) {
                String g = m.group(1);
                String a = m.group(2);
                String v = m.group(3);
                if (!v.contains("$") && !v.startsWith("+")) {
                    packages.add(new CveLookupService.PackageRef(g, a, v));
                }
            }
        }

        return packages;
    }

    // ─── Supporting enums ───────────────────────────────────────────────

    public enum VersionPreference {
        /** Stable releases only — no snapshots, no milestones/RCs */
        RELEASE,
        /** Latest release including milestones and RCs, excluding snapshots */
        LATEST,
        /** Latest version including snapshots */
        SNAPSHOT,
        /** Every published version */
        ALL
    }

    public enum Stability {
        STABLE,
        RC,
        MILESTONE,
        BETA,
        ALPHA,
        SNAPSHOT;

        static Stability fromVersion(String version) {
            String lower = version.toLowerCase();
            if (lower.contains("snapshot")) return SNAPSHOT;
            if (lower.contains("alpha") || lower.contains("-a")) return ALPHA;
            if (lower.contains("beta") || lower.contains("-b")) return BETA;
            if (lower.contains("milestone") || lower.contains("-m") || lower.contains(".m")) return MILESTONE;
            if (lower.contains("-rc") || lower.contains(".rc")) return RC;
            return STABLE;
        }
    }
}
