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
import com.pragmatik.buildtools.security.AnchoredProjectFileReader;
import com.pragmatik.buildtools.tool.JsonUtils;
import com.pragmatik.buildtools.tool.XmlUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final Logger logger = LoggerFactory.getLogger(DependencyService.class);

    private static final String MAVEN_CENTRAL_BASE = "https://repo1.maven.org/maven2";
    private static final int MAX_SCAN_BUILD_FILE_BYTES = 1024 * 1024;
    private static final HttpClient MAVEN_CENTRAL_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final int SCAN_READ_TIMEOUT_SECONDS = 5;
    // Zero queue and one daemon keep a blocked native file-open race from creating
    // an unbounded number of threads or stalling request threads indefinitely.
    private static final ThreadPoolExecutor SCAN_FILE_READER =
            new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), task -> {
                Thread worker = new Thread(task, "dependency-scan-file-reader");
                worker.setDaemon(true);
                return worker;
            });

    private final HttpClient httpClient;
    private final BuildToolProvider buildToolProvider;
    private final PomDependencyResolver pomResolver;
    private final CveLookupService cveLookup;

    @Autowired
    public DependencyService(BuildToolProvider buildToolProvider) {
        this(buildToolProvider, new CveLookupService());
    }

    DependencyService(BuildToolProvider buildToolProvider, CveLookupService cveLookup) {
        this.httpClient = MAVEN_CENTRAL_HTTP_CLIENT;
        this.buildToolProvider = buildToolProvider;
        this.pomResolver = new PomDependencyResolver();
        this.cveLookup = Objects.requireNonNull(cveLookup);
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
                    + "includeSecurityInfo opts into sending the supplied coordinates to OSV.dev for local enrichment; "
                    + "the public MCP result withholds those security details.")
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
                                    "Opt in to an OSV.dev coordinate lookup for local integrations. MCP withholds its security details; use scan_dependency_cves for aggregate vulnerability presence. Default false.")
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
            logger.warn("[DependencyService] Could not enrich project context");
        }
    }

    // ─── Version parsing utilities ──────────────────────────────────────

    static String extractTag(String xml, String tagName) {
        return XmlUtils.extractTag(xml, tagName);
    }

    static List<String> extractAllTags(String xml, String tagName) {
        return XmlUtils.extractAllTags(xml, tagName);
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
                if ("UNKNOWN".equals(v.severity())) {
                    highest = "UNKNOWN";
                } else if (!"UNKNOWN".equals(highest) && CveLookupService.meetsThreshold(v.severity(), highest)) {
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
            security.put("cveCount", null);
            security.put("highestSeverity", "UNKNOWN");
            security.put("warning", "CVE lookup failed");
            security.put("lookupStatus", "incomplete");
            security.put("vulnerabilities", List.of());
            result.put("security", security);
        }
    }

    /**
     * Bulk-scan a project's recognized direct literal declarations for known vulnerabilities.
     * <p>
     * Reads a bounded POM or Gradle build file, extracts direct dependencies, and
     * sends supported package coordinates to OSV.dev. The MCP projection returns
     * aggregate counts and a completeness status; package and CVE identities stay local.
     * <p>
     * <b>Performance:</b> Uncached coordinates are queried in batches of up to 100;
     * an in-memory cache lasts one hour. A scan is incomplete if any dependency
     * cannot be checked. OSV batch results omit severity, so presence counts are
     * reported without asserting severity for those vulnerabilities.
     */
    @Tool(
            name = "scan_dependency_cves",
            description =
                    "Scan project-level Maven dependencies with explicit literal versions or selected literal Gradle dependency calls using OSV.dev. "
                            + "Sends supported package coordinates and versions to OSV.dev. Accepts build files up to 1 MiB "
                            + "through a no-symlink project handle. MCP returns aggregate counts and scan status; "
                            + "package and CVE identities stay local. Default threshold is HIGH, including CRITICAL. "
                            + "Scans at most 500 coordinates in batches of up to 100; unknown severity is explicit.")
    public String scanDependencyCves(
            @ToolParam(required = true, description = "Path to the project directory containing build files")
                    String projectDir,
            @Schema(allowableValues = {"CRITICAL", "HIGH", "MEDIUM", "LOW", "ALL"})
                    @ToolParam(
                            required = false,
                            description =
                                    "Minimum known severity for local details: CRITICAL, HIGH (default), MEDIUM, LOW, or ALL. Unknown-severity matches remain visible in aggregate counts.")
                    String severityThreshold) {

        if (projectDir == null || projectDir.isBlank()) {
            return JsonUtils.errorJson("projectDir is required");
        }

        String threshold =
                severityThreshold != null && !severityThreshold.isBlank() ? severityThreshold.toUpperCase() : "HIGH";

        Map<String, Object> result = new LinkedHashMap<>();

        try {
            BuildFileSnapshot buildFile = readScanBuildFileWithDeadline(
                    Path.of(projectDir).toAbsolutePath().normalize());
            if (buildFile == null) {
                return JsonUtils.errorJson(
                        "No build files found (pom.xml, build.gradle, build.gradle.kts). Cannot scan dependencies.");
            }
            result.put("project", Map.of("tool", buildFile.tool()));
            List<CveLookupService.PackageRef> packages = extractPackages(buildFile.content(), buildFile.tool());
            if (packages.size() > CveLookupService.MAX_SCAN_PACKAGES) {
                return JsonUtils.errorJson("Dependency vulnerability scan incomplete");
            }

            // Bulk lookup
            Map<String, List<VulnerabilityEntry>> scanResults = cveLookup.bulkLookup(packages);
            for (CveLookupService.PackageRef pkg : packages) {
                if (!scanResults.containsKey(pkg.groupId() + ":" + pkg.artifactId() + ":" + pkg.version())) {
                    return JsonUtils.errorJson("Dependency vulnerability scan incomplete");
                }
            }

            // Build vulnerability report
            List<Map<String, Object>> vulnerabilities = new ArrayList<>();
            int totalDeps = packages.size();
            int vulnerableDeps = 0;
            int criticalCount = 0;
            int highCount = 0;
            boolean severityUnknown = false;

            for (CveLookupService.PackageRef pkg : packages) {
                String key = pkg.groupId() + ":" + pkg.artifactId() + ":" + pkg.version();
                List<VulnerabilityEntry> vulns = scanResults.get(key);
                if (!vulns.isEmpty()) vulnerableDeps++;
                if (vulns.stream().anyMatch(v -> "UNKNOWN".equals(v.severity()))) {
                    severityUnknown = true;
                }
                for (VulnerabilityEntry v : vulns) {
                    if ("CRITICAL".equals(v.severity())) criticalCount++;
                    else if ("HIGH".equals(v.severity())) highCount++;
                }

                // Filter by threshold
                List<VulnerabilityEntry> filtered = vulns.stream()
                        .filter(v -> "UNKNOWN".equals(v.severity())
                                || threshold.equals("ALL")
                                || CveLookupService.meetsThreshold(v.severity(), threshold))
                        .toList();

                if (!filtered.isEmpty()) {
                    Map<String, Object> depVuln = new LinkedHashMap<>();
                    depVuln.put("dependency", key);
                    List<Map<String, Object>> cveList = new ArrayList<>();
                    for (VulnerabilityEntry v : filtered) {
                        cveList.add(v.toMap());
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

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("totalDeps", totalDeps);
            summary.put("vulnerableDeps", vulnerableDeps);
            if (!severityUnknown) {
                summary.put("criticalCount", criticalCount);
                summary.put("highCount", highCount);
            }
            result.put("scanSummary", summary);
            result.put("scanStatus", severityUnknown ? "severity_unknown" : "complete");
            result.put("severityUnknown", severityUnknown);

            result.put("vulnerabilities", vulnerabilities);
            result.put("scannedAt", java.time.Instant.now().toString());

            return JsonUtils.toJson(result);

        } catch (BuildFileTooLargeException e) {
            return JsonUtils.errorJson("Build configuration exceeds the 1 MiB scan limit");
        } catch (CharacterCodingException e) {
            return JsonUtils.errorJson("Build configuration is not valid UTF-8");
        } catch (IncompleteDependencyScanException e) {
            return JsonUtils.errorJson("Dependency vulnerability scan incomplete");
        } catch (IOException | InvalidPathException | UnsupportedOperationException | SecurityException e) {
            return JsonUtils.errorJson("Cannot safely read build configuration");
        }
    }

    private record BuildFileSnapshot(String tool, String content) {}

    private static final class BuildFileTooLargeException extends IOException {}

    private static BuildFileSnapshot readScanBuildFileWithDeadline(Path project) throws IOException {
        Future<BuildFileSnapshot> pending;
        try {
            pending = SCAN_FILE_READER.submit(() -> readScanBuildFile(project));
        } catch (RejectedExecutionException unavailable) {
            throw new IOException("Dependency scan file reader unavailable");
        }
        try {
            return pending.get(SCAN_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IOException("Dependency scan file read timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Dependency scan file read interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Dependency scan file read failed");
        } finally {
            pending.cancel(true);
        }
    }

    private static BuildFileSnapshot readScanBuildFile(Path project) throws IOException {
        try (AnchoredProjectFileReader.ProjectDirectory directory = AnchoredProjectFileReader.open(project)) {
            for (String filename : List.of("pom.xml", "build.gradle.kts", "build.gradle")) {
                byte[] bytes = directory.readIfPresent(filename, MAX_SCAN_BUILD_FILE_BYTES);
                if (bytes == null) continue;
                if (bytes.length > MAX_SCAN_BUILD_FILE_BYTES) throw new BuildFileTooLargeException();
                String content = StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
                return new BuildFileSnapshot("pom.xml".equals(filename) ? "maven" : "gradle", content);
            }
            return null;
        }
    }

    /**
     * Extract package references from build files.
     */
    private List<CveLookupService.PackageRef> extractPackages(String content, String tool) {
        if ("maven".equals(tool)) return MavenPomScanParser.parse(content);
        if ("gradle".equals(tool)) return GradleDependencyScanner.parse(content);
        throw new IncompleteDependencyScanException();
    }

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
