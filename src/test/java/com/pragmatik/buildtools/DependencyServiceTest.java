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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link DependencyService}.
 * <p>
 * Tests XML parsing, version comparison, stability classification,
 * stability filter parsing, upgrade type computation, JSON serialization,
 * and project context enrichment.
 */
@DisplayName("DependencyService unit tests")
class DependencyServiceTest {

    private final DependencyService service = new DependencyService(new BuildToolProvider());

    // ──────────────────────────────────────────────
    //  XML Parsing
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("XML parsing (maven-metadata.xml)")
    class XmlParsing {

        @Test
        @DisplayName("extracts latest and release versions from well-formed XML")
        void extractsLatestAndReleaseVersions() {
            String xml =
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <metadata>
                      <groupId>com.example</groupId>
                      <artifactId>test-lib</artifactId>
                      <versioning>
                        <latest>2.5.0</latest>
                        <release>2.4.0</release>
                        <lastUpdated>20250601000000</lastUpdated>
                        <versions>
                          <version>2.0.0</version>
                          <version>2.1.0</version>
                          <version>2.2.0</version>
                          <version>2.3.0</version>
                          <version>2.4.0</version>
                          <version>2.5.0</version>
                        </versions>
                      </versioning>
                    </metadata>
                    """;

            Map<String, Object> result =
                    service.parseMetadata("com.example", "test-lib", xml, DependencyService.VersionPreference.RELEASE);

            assertThat(result.get("groupId")).isEqualTo("com.example");
            assertThat(result.get("artifactId")).isEqualTo("test-lib");
            assertThat(result.get("latestVersion")).isEqualTo("2.5.0");
            assertThat(result.get("releaseVersion")).isEqualTo("2.4.0");
            assertThat(result.get("lastUpdated")).isEqualTo("20250601000000");
            assertThat(result.get("versionCount")).isEqualTo(6);
            assertThat(result.get("totalVersions")).isEqualTo(6);
        }

        @Test
        @DisplayName("handles missing releaseVersion gracefully")
        void handlesMissingReleaseVersion() {
            String xml =
                    """
                    <?xml version="1.0"?>
                    <metadata>
                      <groupId>com.example</groupId>
                      <artifactId>no-release</artifactId>
                      <versioning>
                        <latest>1.0.0</latest>
                        <lastUpdated>20250101000000</lastUpdated>
                        <versions>
                          <version>1.0.0</version>
                        </versions>
                      </versioning>
                    </metadata>
                    """;

            Map<String, Object> result = service.parseMetadata(
                    "com.example", "no-release", xml, DependencyService.VersionPreference.RELEASE);

            assertThat(result.get("latestVersion")).isEqualTo("1.0.0");
            assertThat(result.get("releaseVersion")).isNull();
        }

        @Test
        @DisplayName("extracts all versions including snapshots")
        void extractsAllVersions() {
            String xml =
                    """
                    <?xml version="1.0"?>
                    <metadata>
                      <groupId>com.example</groupId>
                      <artifactId>snap-lib</artifactId>
                      <versioning>
                        <latest>2.1.0</latest>
                        <release>2.0.0</release>
                        <versions>
                          <version>1.0.0</version>
                          <version>2.0.0-RC1</version>
                          <version>2.0.0</version>
                          <version>2.1.0-SNAPSHOT</version>
                          <version>2.1.0</version>
                        </versions>
                      </versioning>
                    </metadata>
                    """;

            Map<String, Object> result =
                    service.parseMetadata("com.example", "snap-lib", xml, DependencyService.VersionPreference.ALL);

            assertThat(result.get("versionCount")).isEqualTo(5);
            assertThat(result.get("totalVersions")).isEqualTo(5);
            @SuppressWarnings("unchecked")
            List<String> versions = (List<String>) result.get("filteredVersions");
            assertThat(versions).hasSize(5);
            // Should include stability classifications
            assertThat(versions.get(0)).isEqualTo("1.0.0");
            assertThat(versions.get(1)).contains("[RC]");
            assertThat(versions.get(3)).contains("[SNAPSHOT]");
        }
    }

    // ──────────────────────────────────────────────
    //  Stability Classification
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Stability classification")
    class StabilityClassification {

        @Test
        @DisplayName("classifies STABLE version")
        void classifiesStableVersion() {
            assertThat(DependencyService.Stability.fromVersion("2.0.0")).isEqualTo(DependencyService.Stability.STABLE);
            assertThat(DependencyService.Stability.fromVersion("1.2.3.RELEASE"))
                    .isEqualTo(DependencyService.Stability.STABLE);
        }

        @Test
        @DisplayName("classifies SNAPSHOT version")
        void classifiesSnapshotVersion() {
            assertThat(DependencyService.Stability.fromVersion("2.0.0-SNAPSHOT"))
                    .isEqualTo(DependencyService.Stability.SNAPSHOT);
            assertThat(DependencyService.Stability.fromVersion("1.0-snapshot"))
                    .isEqualTo(DependencyService.Stability.SNAPSHOT);
        }

        @Test
        @DisplayName("classifies RC version")
        void classifiesRcVersion() {
            assertThat(DependencyService.Stability.fromVersion("2.0.0-RC1")).isEqualTo(DependencyService.Stability.RC);
            assertThat(DependencyService.Stability.fromVersion("1.0.rc2")).isEqualTo(DependencyService.Stability.RC);
        }

        @Test
        @DisplayName("classifies ALPHA version")
        void classifiesAlphaVersion() {
            assertThat(DependencyService.Stability.fromVersion("2.0.0-alpha-1"))
                    .isEqualTo(DependencyService.Stability.ALPHA);
            assertThat(DependencyService.Stability.fromVersion("1.0-a1")).isEqualTo(DependencyService.Stability.ALPHA);
        }

        @Test
        @DisplayName("classifies BETA version")
        void classifiesBetaVersion() {
            assertThat(DependencyService.Stability.fromVersion("2.0.0-beta-2"))
                    .isEqualTo(DependencyService.Stability.BETA);
            assertThat(DependencyService.Stability.fromVersion("1.0-b3")).isEqualTo(DependencyService.Stability.BETA);
        }

        @Test
        @DisplayName("classifies MILESTONE version")
        void classifiesMilestoneVersion() {
            assertThat(DependencyService.Stability.fromVersion("2.0.0-M1"))
                    .isEqualTo(DependencyService.Stability.MILESTONE);
            assertThat(DependencyService.Stability.fromVersion("1.0.milestone-3"))
                    .isEqualTo(DependencyService.Stability.MILESTONE);
        }
    }

    // ──────────────────────────────────────────────
    //  Version Comparison
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Version comparison")
    class VersionComparison {

        @Test
        @DisplayName("equal versions compare to zero")
        void equalVersionsCompareToZero() {
            assertThat(DependencyService.compareVersions("1.0.0", "1.0.0")).isEqualTo(0);
        }

        @Test
        @DisplayName("newer major version compares positive")
        void newerMajorComparesPositive() {
            assertThat(DependencyService.compareVersions("2.0.0", "1.9.9")).isPositive();
        }

        @Test
        @DisplayName("older version compares negative")
        void olderVersionComparesNegative() {
            assertThat(DependencyService.compareVersions("1.0.0", "2.0.0")).isNegative();
        }

        @Test
        @DisplayName("newer minor version compares positive")
        void newerMinorComparesPositive() {
            assertThat(DependencyService.compareVersions("1.5.0", "1.4.0")).isPositive();
        }

        @Test
        @DisplayName("newer patch version compares positive")
        void newerPatchComparesPositive() {
            assertThat(DependencyService.compareVersions("1.0.5", "1.0.4")).isPositive();
        }

        @Test
        @DisplayName("shorter version treated as zero-padded")
        void shorterVersionTreatedAsZeroPadded() {
            assertThat(DependencyService.compareVersions("1.0.0", "1.0")).isEqualTo(0);
        }

        @Test
        @DisplayName("handles unequal-length versions")
        void handlesUnequalLengthVersions() {
            assertThat(DependencyService.compareVersions("2.0.0.1", "2.0.0")).isPositive();
        }
    }

    // ──────────────────────────────────────────────
    //  Upgrade Type Computation
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Upgrade type computation")
    class UpgradeTypeComputation {

        @Test
        @DisplayName("detects MAJOR upgrade")
        void detectsMajorUpgrade() {
            assertThat(DependencyService.computeUpgradeType("1.5.0", "2.0.0")).isEqualTo("MAJOR");
        }

        @Test
        @DisplayName("detects MINOR upgrade")
        void detectsMinorUpgrade() {
            assertThat(DependencyService.computeUpgradeType("1.5.0", "1.6.0")).isEqualTo("MINOR");
        }

        @Test
        @DisplayName("detects PATCH upgrade")
        void detectsPatchUpgrade() {
            assertThat(DependencyService.computeUpgradeType("1.5.0", "1.5.3")).isEqualTo("PATCH");
        }

        @Test
        @DisplayName("same version returns PATCH")
        void sameVersionReturnsPatch() {
            assertThat(DependencyService.computeUpgradeType("1.0.0", "1.0.0")).isEqualTo("PATCH");
        }

        @Test
        @DisplayName("handles version with pre-release suffixes")
        void handlesPreReleaseSuffixes() {
            assertThat(DependencyService.computeUpgradeType("1.5.0", "1.6.0-RC1"))
                    .isEqualTo("MINOR");
        }
    }

    // ──────────────────────────────────────────────
    //  Stability Filter Parsing
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Stability filter parsing")
    class VersionPreferenceParsing {

        @Test
        @DisplayName("null filter defaults to RELEASE (Maven's stable release)")
        void nullFilterDefaultsToStableOnly() {
            assertThat(DependencyService.parseVersionPreference(null))
                    .isEqualTo(DependencyService.VersionPreference.RELEASE);
        }

        @Test
        @DisplayName("empty filter defaults to RELEASE (Maven's stable release)")
        void emptyFilterDefaultsToStableOnly() {
            assertThat(DependencyService.parseVersionPreference(""))
                    .isEqualTo(DependencyService.VersionPreference.RELEASE);
        }

        @Test
        @DisplayName("blank filter defaults to RELEASE (Maven's stable release)")
        void blankFilterDefaultsToStableOnly() {
            assertThat(DependencyService.parseVersionPreference("   "))
                    .isEqualTo(DependencyService.VersionPreference.RELEASE);
        }

        @Test
        @DisplayName("parses 'ALL' filter")
        void parsesAllFilter() {
            assertThat(DependencyService.parseVersionPreference("ALL"))
                    .isEqualTo(DependencyService.VersionPreference.ALL);
        }

        @Test
        @DisplayName("parses 'RELEASE' preference")
        void parsesStableOnlyFilter() {
            assertThat(DependencyService.parseVersionPreference("RELEASE"))
                    .isEqualTo(DependencyService.VersionPreference.RELEASE);
        }

        @Test
        @DisplayName("parses 'LATEST' filter")
        void parsesPreferStableFilter() {
            assertThat(DependencyService.parseVersionPreference("LATEST"))
                    .isEqualTo(DependencyService.VersionPreference.LATEST);
        }

        @Test
        @DisplayName("parses 'SNAPSHOT' preference")
        void parsesSnapshotFilter() {
            assertThat(DependencyService.parseVersionPreference("SNAPSHOT"))
                    .isEqualTo(DependencyService.VersionPreference.SNAPSHOT);
        }

        @Test
        @DisplayName("unknown filter defaults to RELEASE")
        void unknownFilterDefaultsToStableOnly() {
            assertThat(DependencyService.parseVersionPreference("BANANA"))
                    .isEqualTo(DependencyService.VersionPreference.RELEASE);
        }
    }

    // ──────────────────────────────────────────────
    //  Version Comparison Enrichment
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Version comparison enrichment")
    class VersionComparisonEnrichment {

        @Test
        @DisplayName("marks upgrade available when newer version exists")
        void marksUpgradeAvailable() {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("groupId", "com.example");
            result.put("artifactId", "test-lib");
            result.put("latestVersion", "2.5.0");
            result.put("latestStable", "2.5.0");

            service.enrichWithVersionComparison(result, "2.0.0", DependencyService.VersionPreference.RELEASE);

            assertThat(result.get("currentVersion")).isEqualTo("2.0.0");
            assertThat(result.get("upgradeAvailable")).isEqualTo(true);
            assertThat(result.get("recommended")).isEqualTo(true);
            assertThat(result.get("upgradeType")).isEqualTo("MINOR");
        }

        @Test
        @DisplayName("marks no upgrade when same version")
        void marksNoUpgradeWhenSameVersion() {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("groupId", "com.example");
            result.put("artifactId", "test-lib");
            result.put("latestVersion", "2.5.0");
            result.put("latestStable", "2.5.0");

            service.enrichWithVersionComparison(result, "2.5.0", DependencyService.VersionPreference.RELEASE);

            assertThat(result.get("upgradeAvailable")).isEqualTo(false);
        }

        @Test
        @DisplayName("handles null latest version gracefully")
        void handlesNullLatestVersion() {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("groupId", "com.example");
            result.put("artifactId", "test-lib");

            service.enrichWithVersionComparison(result, "1.0.0", DependencyService.VersionPreference.RELEASE);

            assertThat(result.get("upgradeAvailable")).isEqualTo(false);
        }
    }

    // ──────────────────────────────────────────────
    //  Project Context Enrichment
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Project context enrichment")
    class ProjectContextEnrichment {

        @Test
        @DisplayName("adds Maven dependency syntax for Maven project")
        void addsMavenDependencySyntax(@TempDir Path projectDir) throws IOException {
            Files.createFile(projectDir.resolve("pom.xml"));

            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("groupId", "org.springframework.boot");
            result.put("artifactId", "spring-boot-starter-web");
            result.put("latestVersion", "3.4.4");

            service.enrichWithProjectContext(result, projectDir.toString());

            assertThat(result.get("detectedBuildTool")).isEqualTo("maven");
            @SuppressWarnings("unchecked")
            Map<String, String> syntax = (Map<String, String>) result.get("dependencySyntax");
            assertThat(syntax).isNotNull();
            assertThat(syntax.get("maven")).contains("<groupId>org.springframework.boot</groupId>");
            assertThat(syntax.get("maven")).contains("<artifactId>spring-boot-starter-web</artifactId>");
        }

        @Test
        @DisplayName("adds Gradle dependency syntax for Gradle project")
        void addsGradleDependencySyntax(@TempDir Path projectDir) throws IOException {
            Files.createFile(projectDir.resolve("build.gradle"));

            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("groupId", "com.google.guava");
            result.put("artifactId", "guava");
            result.put("latestVersion", "33.0.0");

            service.enrichWithProjectContext(result, projectDir.toString());

            assertThat(result.get("detectedBuildTool")).isEqualTo("gradle");
            @SuppressWarnings("unchecked")
            Map<String, String> syntax = (Map<String, String>) result.get("dependencySyntax");
            assertThat(syntax.get("gradle")).contains("implementation('com.google.guava:guava:33.0.0')");
        }

        @Test
        @DisplayName("adds SBT dependency syntax for SBT project")
        void addsSbtDependencySyntax(@TempDir Path projectDir) throws IOException {
            Files.createFile(projectDir.resolve("build.sbt"));

            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("groupId", "org.typelevel");
            result.put("artifactId", "cats-core");
            result.put("latestVersion", "2.9.0");

            service.enrichWithProjectContext(result, projectDir.toString());

            assertThat(result.get("detectedBuildTool")).isEqualTo("sbt");
            @SuppressWarnings("unchecked")
            Map<String, String> syntax = (Map<String, String>) result.get("dependencySyntax");
            assertThat(syntax.get("sbt")).contains("libraryDependencies");
            assertThat(syntax.get("sbt")).contains("cats-core");
        }

        @Test
        @DisplayName("handles nonexistent projectDir gracefully")
        void handlesNonexistentProjectDir(@TempDir Path projectDir) {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("groupId", "com.example");
            result.put("artifactId", "test-lib");

            service.enrichWithProjectContext(
                    result, projectDir.resolve("does-not-exist").toString());

            // Should not throw, just skip enrichment
            assertThat(result.get("detectedBuildTool")).isNull();
        }
    }

    // ──────────────────────────────────────────────
    //  JSON Serialization
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("JSON serialization")
    class JsonSerialization {

        @Test
        @DisplayName("serializes simple map to JSON")
        void serializesSimpleMap() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("name", "test");
            map.put("count", 42);
            map.put("active", true);

            String json = JsonUtils.toJson(map);
            assertThat(json).contains("\"name\":\"test\"");
            assertThat(json).contains("\"count\":42");
            assertThat(json).contains("\"active\":true");
        }

        @Test
        @DisplayName("serializes nested objects")
        void serializesNestedObjects() {
            Map<String, Object> inner = new java.util.LinkedHashMap<>();
            inner.put("key", "value");

            Map<String, Object> outer = new java.util.LinkedHashMap<>();
            outer.put("nested", inner);

            String json = JsonUtils.toJson(outer);
            assertThat(json).contains("\"nested\":{\"key\":\"value\"}");
        }

        @Test
        @DisplayName("serializes lists")
        void serializesLists() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("items", List.of("a", "b", "c"));

            String json = JsonUtils.toJson(map);
            assertThat(json).contains("\"items\":[\"a\",\"b\",\"c\"]");
        }

        @Test
        @DisplayName("escapes special JSON characters")
        void escapesSpecialCharacters() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("message", "hello \"world\"\nnew line");

            String json = JsonUtils.toJson(map);
            assertThat(json).contains("\\\"world\\\"");
            assertThat(json).contains("\\n");
        }

        @Test
        @DisplayName("error response is valid JSON")
        void errorResponseIsValidJson() {
            String error = JsonUtils.errorJson("Something went wrong");
            assertThat(error).contains("\"success\":false");
            assertThat(error).contains("\"error\":\"Something went wrong\"");
            // Should be valid JSON (starts with { and ends with })
            assertThat(error).startsWith("{").endsWith("}");
        }
    }

    // ──────────────────────────────────────────────
    //  Extract Tag Utilities
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("XML tag extraction utilities")
    class XmlTagExtraction {

        @Test
        @DisplayName("extracts text from simple tag")
        void extractsTextFromSimpleTag() {
            String result = DependencyService.extractTag("<root><name>value</name></root>", "name");
            assertThat(result).isEqualTo("value");
        }

        @Test
        @DisplayName("returns null when tag not found")
        void returnsNullWhenTagNotFound() {
            String result = DependencyService.extractTag("<root></root>", "missing");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("returns null for null XML input")
        void returnsNullForNullXml() {
            String result = DependencyService.extractTag(null, "tag");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("extracts all matching tags")
        void extractsAllMatchingTags() {
            String xml = "<versions><version>1.0</version><version>2.0</version></versions>";
            List<String> versions = DependencyService.extractAllTags(xml, "version");
            assertThat(versions).containsExactly("1.0", "2.0");
        }

        @Test
        @DisplayName("returns empty list for null XML")
        void returnsEmptyListForNullXml() {
            List<String> result = DependencyService.extractAllTags(null, "version");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("trims whitespace from extracted values")
        void trimsWhitespaceFromExtractedValues() {
            String xml = "<root><name>  padded value  </name></root>";
            String result = DependencyService.extractTag(xml, "name");
            assertThat(result).isEqualTo("padded value");
        }
    }
}
