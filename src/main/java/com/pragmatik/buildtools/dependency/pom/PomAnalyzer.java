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
package com.pragmatik.buildtools.dependency.pom;

import com.pragmatik.buildtools.dependency.pom.PomModel.AnalysisResult;
import com.pragmatik.buildtools.dependency.pom.PomModel.Classification;
import com.pragmatik.buildtools.dependency.pom.PomModel.DependencyEntry;
import com.pragmatik.buildtools.dependency.pom.PomModel.PomInfo;
import com.pragmatik.buildtools.dependency.pom.PomModel.ResolvedDependency;
import com.pragmatik.buildtools.tool.XmlUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Pure-Java POM parser and dependency analyser.
 * <p>
 * Reads a {@code pom.xml}, walks the parent POM chain (local repo,
 * filesystem, then remote Maven Central), resolves {@code <dependencyManagement>}
 * including BOM imports, interpolates properties, and classifies every
 * dependency as EXPLICIT, MANAGED, or OVERRIDE.
 * <p>
 * <b>No Maven execution required.</b> This is a static analysis tool, not a build
 * replacement. It uses the same XML-reading pattern as
 * {@code BuildToolsService#validateBuildConfiguration}.
 *
 * @see PomDependencyResolver
 * @see PomModel
 */
public class PomAnalyzer {

    private static final String MAVEN_CENTRAL_BASE = "https://repo1.maven.org/maven2";
    private static final Path DEFAULT_LOCAL_REPO = Path.of(System.getProperty("user.home"), ".m2", "repository");

    private final Path localRepoPath;
    private final HttpClient httpClient;

    public PomAnalyzer() {
        this(DEFAULT_LOCAL_REPO);
    }

    public PomAnalyzer(Path localRepoPath) {
        this.localRepoPath = localRepoPath;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Analyse a Maven project's POM and return a fully classified dependency report.
     *
     * @param projectDir path to the project directory containing pom.xml
     * @return the analysis result
     * @throws IOException if pom.xml cannot be read
     */
    public AnalysisResult analyze(Path projectDir) throws IOException {
        return analyze(projectDir, false);
    }

    /**
     * Analyse a Maven project's POM.
     *
     * @param projectDir        path to the project directory containing pom.xml
     * @param resolveTransitive reserved for future transitive resolution;
     *                           currently not implemented
     * @return the analysis result
     * @throws IOException if pom.xml cannot be read
     */
    public AnalysisResult analyze(Path projectDir, boolean resolveTransitive) throws IOException {
        Path pomFile = projectDir.resolve("pom.xml");
        if (!Files.exists(pomFile)) {
            throw new IllegalArgumentException("No pom.xml found in " + projectDir);
        }

        String content = Files.readString(pomFile);

        // Parse the project-level POM
        PomInfo projectPom = parsePom(content, projectDir);

        // Walk the parent chain
        List<String> parentChain = new ArrayList<>();
        Map<String, String> accumulatedProperties = new LinkedHashMap<>();
        List<DependencyEntry> accumulatedManagedDeps = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        parentChain.add(projectPom.artifactId() + ":" + Objects.requireNonNullElse(projectPom.version(), "unknown"));

        // Add self properties first (child overrides parent)
        accumulatedProperties.putAll(projectPom.properties());

        PomInfo current = projectPom;
        int maxDepth = 20;
        while (current.parent() != null && !current.parent().isEmpty() && maxDepth-- > 0) {
            String parentGroupId = current.parent().getOrDefault("groupId", projectPom.groupId());
            String parentArtifactId = current.parent().get("artifactId");
            String parentVersion = current.parent().get("version");

            if (parentArtifactId == null || parentVersion == null) {
                warnings.add("Incomplete parent reference: " + current.parent());
                break;
            }

            parentChain.add(parentGroupId + ":" + parentArtifactId + ":" + parentVersion);

            // Try to resolve the parent POM
            String parentPomXml = resolveParentPom(parentGroupId, parentArtifactId, parentVersion);
            if (parentPomXml == null) {
                warnings.add("Could not resolve parent POM: " + parentGroupId + ":" + parentArtifactId + ":"
                        + parentVersion + ". Stopping parent chain walk.");
                break;
            }

            PomInfo parentPom = parsePom(parentPomXml, projectDir);

            // Parent properties: child overrides parent (already in map)
            for (Map.Entry<String, String> e : parentPom.properties().entrySet()) {
                accumulatedProperties.putIfAbsent(e.getKey(), e.getValue());
            }

            // Accumulate managed deps from parent (parent first, child overrides)
            accumulatedManagedDeps.addAll(0, parentPom.managedDependencies());

            current = parentPom;
        }

        if (maxDepth <= 0) {
            warnings.add("Parent chain depth exceeded safety limit (20). Chain truncated.");
        }

        // Resolve BOM imports in managed dependencies
        List<DependencyEntry> resolvedManagedDeps = new ArrayList<>();
        List<String> importedBoms = new ArrayList<>();

        for (DependencyEntry dep : projectPom.managedDependencies()) {
            if ("import".equals(dep.scope())) {
                importedBoms.add(dep.groupId() + ":" + dep.artifactId() + ":" + dep.version());
                List<DependencyEntry> bomDeps = resolveBom(dep, accumulatedProperties);
                resolvedManagedDeps.addAll(bomDeps);
            } else {
                resolvedManagedDeps.add(dep);
            }
        }

        // Merge accumulated (parent chain) managed deps, then self managed deps, then BOM deps
        Map<String, DependencyEntry> managedMap = new LinkedHashMap<>();
        for (DependencyEntry d : accumulatedManagedDeps) {
            managedMap.put(d.groupId() + ":" + d.artifactId(), d);
        }
        for (DependencyEntry d : resolvedManagedDeps) {
            managedMap.put(d.groupId() + ":" + d.artifactId(), d);
        }

        // Resolve dependencies with interpolation and classification
        List<ResolvedDependency> resolvedDeps = new ArrayList<>();
        Map<String, String> propertySubs = new LinkedHashMap<>();
        List<String> unresolvedProps = new ArrayList<>();

        for (DependencyEntry dep : projectPom.dependencies()) {
            String resolvedVersion =
                    resolveVersion(dep.version(), accumulatedProperties, propertySubs, unresolvedProps);

            DependencyEntry resolved =
                    new DependencyEntry(dep.groupId(), dep.artifactId(), resolvedVersion, dep.scope(), dep.optional());

            // Classify
            String ga = dep.groupId() + ":" + dep.artifactId();
            DependencyEntry managed = managedMap.get(ga);
            Classification classification;
            String source;

            if (managed != null && dep.version() == null) {
                classification = Classification.MANAGED;
                source = "dependencyManagement:" + ga;
                resolved = new DependencyEntry(
                        dep.groupId(), dep.artifactId(), managed.version(), dep.scope(), dep.optional());
            } else if (managed != null && dep.version() != null && !Objects.equals(dep.version(), managed.version())) {
                classification = Classification.OVERRIDE;
                source = "pom.xml (overrides managed version " + managed.version() + ")";
            } else if (managed != null) {
                classification = Classification.MANAGED;
                source = "dependencyManagement:" + ga;
            } else {
                classification = Classification.EXPLICIT;
                source = "pom.xml";
            }

            resolvedDeps.add(new ResolvedDependency(
                    resolved.groupId(),
                    resolved.artifactId(),
                    resolved.version(),
                    resolved.scope(),
                    resolved.optional(),
                    classification,
                    source));
        }

        // Build the full PomInfo for the project
        PomInfo fullProject = new PomInfo(
                projectPom.groupId(),
                projectPom.artifactId(),
                projectPom.version(),
                projectPom.packaging(),
                projectPom.parent(),
                accumulatedProperties,
                projectPom.dependencies(),
                projectPom.managedDependencies(),
                importedBoms);

        return new AnalysisResult(fullProject, resolvedDeps, parentChain, propertySubs, unresolvedProps, warnings);
    }

    // ── XML helpers (package-private for testing, delegated to XmlUtils) ──

    static String extractTag(String xml, String tagName) {
        return XmlUtils.extractTag(xml, tagName);
    }

    static List<String> extractAllTags(String xml, String tagName) {
        return XmlUtils.extractAllTags(xml, tagName);
    }

    // ── POM parsing ───────────────────────────────────────────────────

    PomInfo parsePom(String content, Path projectDir) {
        String groupId = extractTag(content, "groupId");
        String artifactId = extractTag(content, "artifactId");
        String version = extractTag(content, "version");
        String packaging = extractTag(content, "packaging");
        if (packaging == null) packaging = "jar";

        // Parse parent
        String parentBlock = extractTag(content, "parent");
        Map<String, String> parent = null;
        if (parentBlock != null && !parentBlock.isEmpty()) {
            parent = new LinkedHashMap<>();
            String pg = extractTag(parentBlock, "groupId");
            String pa = extractTag(parentBlock, "artifactId");
            String pv = extractTag(parentBlock, "version");
            if (pg != null) parent.put("groupId", pg);
            if (pa != null) parent.put("artifactId", pa);
            if (pv != null) parent.put("version", pv);
        }

        // Parse properties
        String propsBlock = extractTag(content, "properties");
        Map<String, String> properties = new LinkedHashMap<>();
        if (propsBlock != null && !propsBlock.isBlank()) {
            // Extract known project properties
            for (String key : List.of("project.version", "project.groupId", "project.artifactId")) {
                // These are implicit — handled by interpolation
            }
            // Extract custom properties: match <key>value</key> inside properties block
            java.util.regex.Pattern propPattern =
                    java.util.regex.Pattern.compile("<([a-zA-Z][a-zA-Z0-9._-]*)>([^<]*)</\\1>");
            java.util.regex.Matcher propMatcher = propPattern.matcher(propsBlock);
            while (propMatcher.find()) {
                String key = propMatcher.group(1);
                if (!key.equals("project") && !key.startsWith("maven")) {
                    properties.putIfAbsent(key, propMatcher.group(2).trim());
                }
            }
        }

        // Add implicit project properties (self values override template-like values)
        if (version != null) properties.put("project.version", version);
        if (groupId != null) properties.put("project.groupId", groupId);
        if (artifactId != null) properties.put("project.artifactId", artifactId);

        // Parse dependencyManagement first (so we can strip it before parsing project deps)
        String depMgmtBlock = extractTag(content, "dependencyManagement");
        List<DependencyEntry> managedDeps = new ArrayList<>();
        if (depMgmtBlock != null && !depMgmtBlock.isBlank()) {
            String mgmtDepsBlock = extractTag(depMgmtBlock, "dependencies");
            if (mgmtDepsBlock != null) {
                managedDeps = parseDependencyEntries(mgmtDepsBlock);
            }
        }

        // Strip dependencyManagement section before extracting project-level dependencies,
        // otherwise the naive XML parser will pick up the managed dependencies block first.
        String contentForDeps = content;
        if (depMgmtBlock != null) {
            int depMgmtStart = content.indexOf("<dependencyManagement>");
            int depMgmtEnd =
                    content.indexOf("</dependencyManagement>", depMgmtStart) + "</dependencyManagement>".length();
            if (depMgmtStart >= 0 && depMgmtEnd > depMgmtStart) {
                contentForDeps = content.substring(0, depMgmtStart) + content.substring(depMgmtEnd);
            }
        }

        // Parse project-level dependencies (from the stripped content)
        String depsBlock = extractTag(contentForDeps, "dependencies");
        List<DependencyEntry> dependencies = new ArrayList<>();
        if (depsBlock != null) {
            dependencies = parseDependencyEntries(depsBlock);
        }

        return new PomInfo(
                groupId, artifactId, version, packaging, parent, properties, dependencies, managedDeps, List.of());
    }

    private List<DependencyEntry> parseDependencyEntries(String depsBlock) {
        List<DependencyEntry> entries = new ArrayList<>();
        String[] depSections = depsBlock.split("</dependency>");

        for (String section : depSections) {
            int start = section.indexOf("<dependency>");
            if (start < 0) continue;
            String depXml = section.substring(start);

            String g = extractTag(depXml, "groupId");
            String a = extractTag(depXml, "artifactId");
            String v = extractTag(depXml, "version");
            String scope = extractTag(depXml, "scope");
            boolean optional = depXml.contains("<optional>true</optional>");

            if (g != null && a != null) {
                entries.add(new DependencyEntry(g, a, v, scope, optional));
            }
        }
        return entries;
    }

    // ── Parent POM resolution ─────────────────────────────────────────

    String resolveParentPom(String groupId, String artifactId, String version) {
        // Strategy 1: Local repository (~/.m2)
        String groupPath = groupId.replace('.', '/');
        Path localPom = localRepoPath
                .resolve(groupPath)
                .resolve(artifactId)
                .resolve(version)
                .resolve(artifactId + "-" + version + ".pom");
        if (Files.exists(localPom)) {
            try {
                return Files.readString(localPom);
            } catch (IOException e) {
                // Fall through to remote
            }
        }

        // Strategy 2: Remote (Maven Central)
        String pomUrl = String.format(
                "%s/%s/%s/%s/%s-%s.pom", MAVEN_CENTRAL_BASE, groupPath, artifactId, version, artifactId, version);

        try {
            HttpRequest request =
                    HttpRequest.newBuilder().uri(URI.create(pomUrl)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        return null;
    }

    // ── BOM resolution ────────────────────────────────────────────────

    List<DependencyEntry> resolveBom(DependencyEntry bomDep, Map<String, String> properties) {
        String version = resolveSimple(bomDep.version(), properties);
        String bomXml = resolveParentPom(bomDep.groupId(), bomDep.artifactId(), version);
        if (bomXml == null) return List.of();

        String depMgmtBlock = extractTag(bomXml, "dependencyManagement");
        if (depMgmtBlock == null || depMgmtBlock.isBlank()) return List.of();

        String depsBlock = extractTag(depMgmtBlock, "dependencies");
        if (depsBlock == null) return List.of();

        return parseDependencyEntries(depsBlock);
    }

    // ── Property interpolation ────────────────────────────────────────

    String resolveVersion(
            String version,
            Map<String, String> properties,
            Map<String, String> substitutions,
            List<String> unresolved) {
        if (version == null) return null;

        String resolved = resolveSimple(version, properties);
        if (!resolved.equals(version)) {
            substitutions.put(version, resolved);
        }

        // Check for remaining unresolved references
        if (resolved.contains("${")) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\$\\{([^}]+)}");
            java.util.regex.Matcher m = p.matcher(resolved);
            while (m.find()) {
                String prop = m.group(1);
                if (!unresolved.contains(prop)) {
                    unresolved.add(prop);
                }
            }
        }

        return resolved;
    }

    private String resolveSimple(String value, Map<String, String> properties) {
        if (value == null || !value.contains("${")) return value;

        String result = value;
        // Resolve ${property.name} references
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\$\\{([^}]+)}");
        java.util.regex.Matcher m = p.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String propName = m.group(1);
            String propValue = properties.getOrDefault(propName, null);
            if (propValue != null) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(propValue));
            } else {
                // Keep the reference for unresolved reporting
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
