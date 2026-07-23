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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.pragmatik.buildtools.build.BuildToolProvider;
import com.pragmatik.buildtools.dependency.DependencyService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the POM-aware dependency analysis feature (F1 of v1.1.0).
 *
 * @see PomAnalyzer
 * @see PomDependencyResolver
 * @see DependencyService
 */
@DisplayName("POM-aware dependency analysis")
class PomAnalyzerTest {

    private static final Path FIXTURES = Path.of("src/test/resources/test-pom-projects");

    // ── XML Parsing ──────────────────────────────────────────────────

    @Nested
    @DisplayName("PomAnalyzer XML parsing")
    class XmlParsing {

        @Test
        @DisplayName("extracts tag content from XML")
        void extractsTagContent() {
            String xml = "<root><child>value</child></root>";
            assertThat(PomAnalyzer.extractTag(xml, "child")).isEqualTo("value");
        }

        @Test
        @DisplayName("returns null for missing tag")
        void returnsNullForMissingTag() {
            String xml = "<root></root>";
            assertThat(PomAnalyzer.extractTag(xml, "missing")).isNull();
        }

        @Test
        @DisplayName("extracts all matching tags")
        void extractsAllTags() {
            String xml = "<items><item>a</item><item>b</item><item>c</item></items>";
            List<String> items = PomAnalyzer.extractAllTags(xml, "item");
            assertThat(items).containsExactly("a", "b", "c");
        }
    }

    // ── Simple POM ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Simple POM analysis")
    class SimplePom {

        @Test
        @DisplayName("parses simple POM with explicit dependencies")
        void parsesSimplePom() throws IOException {
            PomAnalyzer analyzer = new PomAnalyzer();
            Path projectDir = FIXTURES.resolve("simple");

            PomModel.AnalysisResult result = analyzer.analyze(projectDir);

            assertThat(result.project().groupId()).isEqualTo("com.example");
            assertThat(result.project().artifactId()).isEqualTo("simple-project");
            assertThat(result.project().version()).isEqualTo("1.0.0");

            assertThat(result.dependencies()).hasSize(2);
            PomModel.ResolvedDependency guava = result.dependencies().get(0);
            assertThat(guava.groupId()).isEqualTo("com.google.guava");
            assertThat(guava.artifactId()).isEqualTo("guava");
            assertThat(guava.version()).isEqualTo("31.1-jre");
            assertThat(guava.classification()).isEqualTo(PomModel.Classification.EXPLICIT);

            PomModel.ResolvedDependency junit = result.dependencies().get(1);
            assertThat(junit.scope()).isEqualTo("test");
        }
    }

    // ── dependencyManagement classification ──────────────────────────

    @Nested
    @DisplayName("dependencyManagement classification")
    class DependencyManagement {

        @Test
        @DisplayName("classifies versionless dependency as MANAGED when in depMgmt")
        void classifiesManaged() throws IOException {
            PomAnalyzer analyzer = new PomAnalyzer();
            Path projectDir = FIXTURES.resolve("with-dep-mgmt");

            PomModel.AnalysisResult result = analyzer.analyze(projectDir);

            PomModel.ResolvedDependency guava = findDep(result, "com.google.guava", "guava");
            assertThat(guava).isNotNull();
            assertThat(guava.classification()).isEqualTo(PomModel.Classification.MANAGED);
            assertThat(guava.version()).isEqualTo("32.0.0-jre");
            assertThat(guava.source()).contains("dependencyManagement");

            PomModel.ResolvedDependency slf4j = findDep(result, "org.slf4j", "slf4j-api");
            assertThat(slf4j).isNotNull();
            assertThat(slf4j.classification()).isEqualTo(PomModel.Classification.EXPLICIT);
            assertThat(slf4j.version()).isEqualTo("2.0.7");
        }

        @Test
        @DisplayName("classifies explicit version that differs from managed as OVERRIDE")
        void classifiesOverride() throws IOException {
            PomAnalyzer analyzer = new PomAnalyzer();
            Path projectDir = FIXTURES.resolve("with-dep-mgmt/override");

            PomModel.AnalysisResult result = analyzer.analyze(projectDir);

            PomModel.ResolvedDependency guava = findDep(result, "com.google.guava", "guava");
            assertThat(guava).isNotNull();
            assertThat(guava.classification()).isEqualTo(PomModel.Classification.OVERRIDE);
            assertThat(guava.version()).isEqualTo("33.0.0-jre");
            assertThat(guava.source()).contains("overrides");
        }
    }

    // ── Property interpolation ──────────────────────────────────────

    @Nested
    @DisplayName("Property interpolation")
    class PropertyInterpolation {

        @Test
        @DisplayName("interpolates ${property} references")
        void interpolatesProperties() throws IOException {
            PomAnalyzer analyzer = new PomAnalyzer();
            Path projectDir = FIXTURES.resolve("with-properties");

            PomModel.AnalysisResult result = analyzer.analyze(projectDir);

            PomModel.ResolvedDependency guava = findDep(result, "com.google.guava", "guava");
            assertThat(guava).isNotNull();
            assertThat(guava.version()).isEqualTo("31.1-jre");

            PomModel.ResolvedDependency junit = findDep(result, "org.junit.jupiter", "junit-jupiter");
            assertThat(junit).isNotNull();
            assertThat(junit.version()).isEqualTo("5.10.0");

            Map<String, String> subs = result.propertySubstitutions();
            // Should have tracked the substitutions
            assertThat(subs).containsKey("${guava.version}");
            assertThat(subs).containsKey("${junit.version}");
        }

        @Test
        @DisplayName("warns on unresolvable properties")
        void warnsOnUnresolvableProperties() throws IOException {
            PomAnalyzer analyzer = new PomAnalyzer();
            Path projectDir = FIXTURES.resolve("with-properties");

            PomModel.AnalysisResult result = analyzer.analyze(projectDir);

            Map<String, String> subs = result.propertySubstitutions();
            assertThat(subs.get("${junit.version}")).isNotNull();
        }
    }

    // ── Gradle project rejection ────────────────────────────────────

    @Nested
    @DisplayName("Non-Maven project rejection")
    class NonMavenProject {

        @Test
        @DisplayName("rejects Gradle project with clear error message")
        void rejectsGradleProject() throws IOException {
            // Create a temp dir with only build.gradle
            Path tempDir = Files.createTempDirectory("gradle-test");
            try {
                Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }");

                PomDependencyResolver resolver = new PomDependencyResolver();
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> resolver.resolve(tempDir, false))
                        .withMessageContaining("Gradle");
            } finally {
                // Clean up temp dir
                Files.list(tempDir).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                    }
                });
                Files.deleteIfExists(tempDir);
            }
        }

        @Test
        @DisplayName("rejects directory without pom.xml")
        void rejectsNoPomXml() throws IOException {
            Path tempDir = Files.createTempDirectory("empty-dir");
            try {
                PomDependencyResolver resolver = new PomDependencyResolver();
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> resolver.resolve(tempDir, false))
                        .withMessageContaining("No pom.xml");
            } finally {
                Files.deleteIfExists(tempDir);
            }
        }
    }

    // ── DependencyService integration ───────────────────────────────

    @Nested
    @DisplayName("DependencyService integration")
    class DependencyServiceIntegration {

        private DependencyService service;

        @BeforeEach
        void setUp() {
            service = new DependencyService(new BuildToolProvider());
        }

        @Test
        @DisplayName("analyzePomDependencies returns valid JSON for a simple project")
        void analyzePomDependenciesReturnsValidJson() throws Exception {
            String json =
                    service.analyzePomDependencies(FIXTURES.resolve("simple").toString(), false, null);

            assertThat(json).isNotNull().isNotEmpty();
            assertThat(json).contains("\"project\"");
            assertThat(json).contains("\"dependencies\"");
            assertThat(json).contains("\"parentChain\"");
        }

        @Test
        @DisplayName("analyzePomDependencies returns error for invalid path")
        void analyzePomDependenciesErrorForInvalidPath() {
            String json = service.analyzePomDependencies("/nonexistent/path", false, null);
            assertThat(json).contains("error");
        }

        @Test
        @DisplayName("analyzePomDependencies returns error for null projectDir")
        void analyzePomDependenciesErrorForNull() {
            String json = service.analyzePomDependencies(null, false, null);
            assertThat(json).contains("error");
        }

        @Test
        @DisplayName("analyzePomDependencies returns error for Gradle project")
        void analyzePomDependenciesErrorForGradleProject() throws IOException {
            Path tempDir = Files.createTempDirectory("gradle-refuse");
            try {
                Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }");

                String json = service.analyzePomDependencies(tempDir.toString(), false, null);
                assertThat(json).contains("error");
                assertThat(json).contains("Gradle");
            } finally {
                Files.list(tempDir).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                    }
                });
                Files.deleteIfExists(tempDir);
            }
        }
    }

    // ── AnalysisResult serialization ─────────────────────────────────

    @Nested
    @DisplayName("AnalysisResult serialization")
    class AnalysisResultSerialization {

        @Test
        @DisplayName("toMap produces correct structure")
        void toMapProducesCorrectStructure() throws IOException {
            PomAnalyzer analyzer = new PomAnalyzer();
            Path projectDir = FIXTURES.resolve("simple");

            PomModel.AnalysisResult result = analyzer.analyze(projectDir);
            Map<String, Object> map = result.toMap();

            assertThat(map)
                    .containsKeys(
                            "project",
                            "dependencies",
                            "managedDependencies",
                            "importedBoms",
                            "parentChain",
                            "propertySubstitutions",
                            "unresolvedProperties",
                            "warnings");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> deps = (List<Map<String, Object>>) map.get("dependencies");
            assertThat(deps).hasSize(2);
            assertThat(deps.get(0))
                    .containsKeys("groupId", "artifactId", "version", "scope", "classification", "source");
        }
    }

    // ── Helper ──────────────────────────────────────────────────────

    private static PomModel.ResolvedDependency findDep(
            PomModel.AnalysisResult result, String groupId, String artifactId) {
        return result.dependencies().stream()
                .filter(d -> d.groupId().equals(groupId) && d.artifactId().equals(artifactId))
                .findFirst()
                .orElse(null);
    }
}
