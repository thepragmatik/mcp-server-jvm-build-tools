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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.pragmatik.buildtools.build.BuildToolProvider;
import com.pragmatik.buildtools.dependency.security.CveLookupService;
import com.pragmatik.buildtools.security.ProjectAccessPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for CVE/security features added to DependencyService in v1.1.0 (F2).
 */
@DisplayName("DependencyService security features (F2)")
class DependencyServiceSecurityTest {

    private final DependencyService service = new DependencyService(new BuildToolProvider());

    @TempDir
    Path temporary;

    private static final String POM = """
            <project><dependencies><dependency><groupId>org.example</groupId>
            <artifactId>safe</artifactId><version>1.2.3</version></dependency></dependencies></project>
            """;

    private static final class CountingLookup extends CveLookupService {
        private final AtomicInteger calls = new AtomicInteger();
        private List<PackageRef> queried = List.of();

        @Override
        public Map<String, List<VulnerabilityEntry>> bulkLookup(List<PackageRef> packages) {
            calls.incrementAndGet();
            queried = List.copyOf(packages);
            Map<String, List<VulnerabilityEntry>> result = new LinkedHashMap<>();
            for (PackageRef pkg : packages) {
                result.put(pkg.groupId() + ":" + pkg.artifactId() + ":" + pkg.version(), List.of());
            }
            return result;
        }
    }

    // ── Security enrichment (internal) ──────────────────────────────

    @Nested
    @DisplayName("enrichWithSecurityInfo")
    class EnrichWithSecurityInfo {

        @Test
        @DisplayName("adds security field to result")
        void addsSecurityField() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("groupId", "com.example");
            result.put("artifactId", "test-lib");

            CveLookupService lookup = new CveLookupService() {
                @Override
                public List<VulnerabilityEntry> lookup(String groupId, String artifactId, String version) {
                    return List.of();
                }
            };
            new DependencyService(new BuildToolProvider(), lookup)
                    .enrichWithSecurityInfo(result, "com.example", "test-lib", "1.0.0");

            assertThat(result).containsKey("security");
            @SuppressWarnings("unchecked")
            Map<String, Object> security = (Map<String, Object>) result.get("security");
            assertThat(security).containsKeys("cveCount", "highestSeverity", "vulnerabilities");
        }

        @Test
        @DisplayName("handles network errors gracefully (returns partial result with warning)")
        void handlesNetworkErrorsGracefully() {
            Map<String, Object> result = new LinkedHashMap<>();
            CveLookupService lookup = new CveLookupService() {
                @Override
                public List<VulnerabilityEntry> lookup(String groupId, String artifactId, String version)
                        throws IOException {
                    throw new IOException("PRIVATE_EMAIL@example.com");
                }
            };
            new DependencyService(new BuildToolProvider(), lookup)
                    .enrichWithSecurityInfo(result, "com.nonexistent.dep", "fake-artifact", "999.0.0");

            assertThat(result).containsKey("security");
            @SuppressWarnings("unchecked")
            Map<String, Object> security = (Map<String, Object>) result.get("security");
            // Should still have the basic fields even on error
            assertThat(security).containsKey("cveCount");
            assertThat(security).containsKey("highestSeverity");
            assertThat(security.get("warning").toString()).doesNotContain("PRIVATE_EMAIL");
        }
    }

    // ── scanDependencyCves ──────────────────────────────────────────

    @Nested
    @DisplayName("scanDependencyCves")
    class ScanDependencyCves {

        @Test
        void namedPipeBuildMarkerFailsBeforeNetworkWithoutBlocking() throws Exception {
            Path project = Files.createDirectory(temporary.toRealPath().resolve("pipe-project"));
            Path marker = project.resolve("pom.xml");
            Process command;
            try {
                command = new ProcessBuilder("mkfifo", marker.toString()).start();
            } catch (IOException unavailable) {
                assumeTrue(false, "mkfifo is unavailable");
                return;
            }
            assumeTrue(command.waitFor() == 0, "mkfifo is unavailable");
            CountingLookup lookup = new CountingLookup();

            String result = assertTimeoutPreemptively(
                    Duration.ofSeconds(2),
                    () -> new DependencyService(new BuildToolProvider(), lookup)
                            .scanDependencyCves(project.toString(), "HIGH"));

            assertThat(lookup.calls).hasValue(0);
            assertThat(result)
                    .contains("Cannot safely read build configuration")
                    .doesNotContain(project.toString());
        }

        @Test
        @DisplayName("returns error for missing projectDir")
        void returnsErrorForMissingProjectDir() {
            String json = service.scanDependencyCves(null, null);
            assertThat(json).contains("error");
        }

        @Test
        @DisplayName("returns error for nonexistent path")
        void returnsErrorForNonexistentPath() {
            String json = service.scanDependencyCves("/nonexistent/path", null);
            assertThat(json).contains("error");
        }

        @Test
        @DisplayName("returns error for directory without build files")
        void returnsErrorForNoBuildFiles() throws Exception {
            String tmpdir = temporary.toRealPath().toString();
            String json = service.scanDependencyCves(tmpdir, null);
            assertThat(json).contains("error");
            assertThat(json).contains("No build files");
        }

        @Test
        void validInRootPomQueriesOnlyItsCoordinates() throws Exception {
            Path project = Files.createDirectory(temporary.toRealPath().resolve("project"));
            Files.writeString(project.resolve("pom.xml"), POM);
            Files.writeString(project.resolve("build.gradle.kts"), "implementation(\"org.example:ignored:9.9\")");
            CountingLookup lookup = new CountingLookup();
            DependencyService scanner = new DependencyService(new BuildToolProvider(), lookup);

            String result = scanner.scanDependencyCves(project.toString(), "HIGH");

            assertThat(lookup.calls).hasValue(1);
            assertThat(lookup.queried).containsExactly(new CveLookupService.PackageRef("org.example", "safe", "1.2.3"));
            assertThat(result).contains("scanSummary").doesNotContain(project.toString());
        }

        @Test
        void managedBlockBeforeDirectDependenciesQueriesOnlyProjectLevelDeclarations() throws Exception {
            Path project = Files.createDirectory(temporary.toRealPath().resolve("project"));
            Files.writeString(project.resolve("pom.xml"), """
                    <project><dependencyManagement><dependencies><dependency>
                    <groupId>org.example</groupId><artifactId>managed</artifactId><version>9.9</version>
                    </dependency></dependencies></dependencyManagement>
                    <dependencies><dependency><groupId>org.example</groupId>
                    <artifactId>direct</artifactId><version>1.0</version></dependency></dependencies></project>
                    """);
            CountingLookup lookup = new CountingLookup();

            String result = new DependencyService(new BuildToolProvider(), lookup)
                    .scanDependencyCves(project.toString(), "HIGH");

            assertThat(lookup.queried).containsExactly(new CveLookupService.PackageRef("org.example", "direct", "1.0"));
            assertThat(result).contains("\"totalDeps\":1", "\"scanStatus\":\"complete\"");
        }

        @Test
        void managedOnlyPomHasNoProjectLevelDeclarationsOrEgress() throws Exception {
            Path project = Files.createDirectory(temporary.toRealPath().resolve("project"));
            Files.writeString(project.resolve("pom.xml"), """
                    <project><dependencyManagement><dependencies><dependency>
                    <groupId>org.example</groupId><artifactId>managed</artifactId><version>9.9</version>
                    </dependency></dependencies></dependencyManagement></project>
                    """);
            CountingLookup lookup = new CountingLookup();

            String result = new DependencyService(new BuildToolProvider(), lookup)
                    .scanDependencyCves(project.toString(), "HIGH");

            assertThat(lookup.queried).isEmpty();
            assertThat(result).contains("\"totalDeps\":0", "\"scanStatus\":\"complete\"");
        }

        @Test
        void namespacedPomDirectDeclarationIsRecognized() throws Exception {
            Path project = Files.createDirectory(temporary.toRealPath().resolve("project"));
            Files.writeString(project.resolve("pom.xml"), """
                    <project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies><dependency>
                    <groupId>org.example</groupId><artifactId>namespaced</artifactId><version>2.0</version>
                    </dependency></dependencies></project>
                    """);
            CountingLookup lookup = new CountingLookup();
            new DependencyService(new BuildToolProvider(), lookup).scanDependencyCves(project.toString(), "HIGH");
            assertThat(lookup.queried)
                    .containsExactly(new CveLookupService.PackageRef("org.example", "namespaced", "2.0"));
        }

        @Test
        void malformedDtdAndInheritedVersionPomFailBeforeLookup() throws Exception {
            List<String> unsafe = List.of(
                    "<project><dependencies><dependency>",
                    "<!DOCTYPE project [<!ENTITY secret SYSTEM 'file:///synthetic/private'>]><project>&secret;</project>",
                    """
                    <project><dependencyManagement><dependencies><dependency>
                    <groupId>org.example</groupId><artifactId>managed</artifactId><version>1.0</version>
                    </dependency></dependencies></dependencyManagement><dependencies><dependency>
                    <groupId>org.example</groupId><artifactId>managed</artifactId>
                    </dependency></dependencies></project>
                    """);
            for (int i = 0; i < unsafe.size(); i++) {
                Path project = Files.createDirectory(temporary.toRealPath().resolve("project" + i));
                Files.writeString(project.resolve("pom.xml"), unsafe.get(i));
                CountingLookup lookup = new CountingLookup();
                String result = new DependencyService(new BuildToolProvider(), lookup)
                        .scanDependencyCves(project.toString(), "HIGH");
                assertThat(lookup.calls).hasValue(0);
                assertThat(result)
                        .contains("Dependency vulnerability scan incomplete")
                        .doesNotContain("scanSummary", "synthetic/private", project.toString());
            }
        }

        @Test
        void gradleCommentsAndQuotedCodeDoNotBecomeOutboundDependencies() throws Exception {
            Path project = Files.createDirectory(temporary.toRealPath().resolve("project"));
            Files.writeString(project.resolve("build.gradle.kts"), """
                    // implementation("org.example:line-comment:1.0")
                    /* implementation("org.example:block-comment:1.0") */
                    val note = "// implementation(\\"org.example:quoted-code:1.0\\")"
                    val url = "https://example.invalid/path"
                    dependencies { implementation("org.example:real:2.0") }
                    """);
            CountingLookup lookup = new CountingLookup();

            new DependencyService(new BuildToolProvider(), lookup).scanDependencyCves(project.toString(), "HIGH");

            assertThat(lookup.queried).containsExactly(new CveLookupService.PackageRef("org.example", "real", "2.0"));
        }

        @Test
        void commentOnlyGradleFileHasNoCoordinatesToSend() throws Exception {
            Path project = Files.createDirectory(temporary.toRealPath().resolve("project"));
            Files.writeString(project.resolve("build.gradle.kts"), """
                    // implementation("org.example:ignored:1.0")
                    /* api("org.example:also-ignored:1.0") */
                    val sample = "implementation(\\"org.example:quoted:1.0\\")"
                    """);
            CountingLookup lookup = new CountingLookup();

            String result = new DependencyService(new BuildToolProvider(), lookup)
                    .scanDependencyCves(project.toString(), "HIGH");

            assertThat(lookup.queried).isEmpty();
            assertThat(result).contains("\"totalDeps\":0", "\"scanStatus\":\"complete\"");
        }

        @Test
        void dynamicGradleVersionFailsBeforeLookup() throws Exception {
            Path project = Files.createDirectory(temporary.toRealPath().resolve("project"));
            Files.writeString(project.resolve("build.gradle"), "implementation 'org.example:dynamic:1.+' ");
            CountingLookup lookup = new CountingLookup();

            String result = new DependencyService(new BuildToolProvider(), lookup)
                    .scanDependencyCves(project.toString(), "HIGH");

            assertThat(lookup.calls).hasValue(0);
            assertThat(result)
                    .contains("Dependency vulnerability scan incomplete")
                    .doesNotContain("scanSummary");
        }

        @Test
        void unsupportedGradleCallFailsBeforeLookup() throws Exception {
            Path project = Files.createDirectory(temporary.toRealPath().resolve("project"));
            Files.writeString(project.resolve("build.gradle.kts"), "dependencies { implementation(libs.guava) }");
            CountingLookup lookup = new CountingLookup();

            String result = new DependencyService(new BuildToolProvider(), lookup)
                    .scanDependencyCves(project.toString(), "HIGH");

            assertThat(lookup.calls).hasValue(0);
            assertThat(result)
                    .contains("Dependency vulnerability scan incomplete")
                    .doesNotContain("scanSummary");
        }

        @Test
        void slashyAndBacktickSyntaxFailBeforeLookup() throws Exception {
            List<String> scripts = List.of(
                    "def note = /implementation('org.example:quoted:1.0')/",
                    "val `implementation(\"org.example:quoted:1.0\")` = 1");
            for (int i = 0; i < scripts.size(); i++) {
                Path project = Files.createDirectory(temporary.toRealPath().resolve("project" + i));
                Files.writeString(project.resolve("build.gradle.kts"), scripts.get(i));
                CountingLookup lookup = new CountingLookup();
                String result = new DependencyService(new BuildToolProvider(), lookup)
                        .scanDependencyCves(project.toString(), "HIGH");
                assertThat(lookup.calls).hasValue(0);
                assertThat(result).contains("Dependency vulnerability scan incomplete");
            }
        }

        @Test
        void uncheckedDependencyReturnsFixedIncompleteErrorWithoutCounts() throws Exception {
            Path project = Files.createDirectory(temporary.toRealPath().resolve("project"));
            Files.writeString(project.resolve("pom.xml"), POM);
            CveLookupService lookup = new CveLookupService() {
                @Override
                public Map<String, List<VulnerabilityEntry>> bulkLookup(List<PackageRef> packages) {
                    return Map.of();
                }
            };

            String result = new DependencyService(new BuildToolProvider(), lookup)
                    .scanDependencyCves(project.toString(), "HIGH");

            assertThat(result)
                    .contains("Dependency vulnerability scan incomplete")
                    .doesNotContain("scanSummary", "vulnerableDeps", project.toString());
        }

        @Test
        void defaultHighThresholdPreservesUnknownVulnerabilityPresence() throws Exception {
            Path project = Files.createDirectory(temporary.toRealPath().resolve("project"));
            Files.writeString(project.resolve("pom.xml"), POM);
            CveLookupService lookup = new CveLookupService() {
                @Override
                public Map<String, List<VulnerabilityEntry>> bulkLookup(List<PackageRef> packages) {
                    return Map.of(
                            "org.example:safe:1.2.3",
                            List.of(new VulnerabilityEntry("OSV-2026-1", null, "UNKNOWN", null, 0.0)));
                }
            };

            String result = new DependencyService(new BuildToolProvider(), lookup)
                    .scanDependencyCves(project.toString(), "HIGH");

            assertThat(result)
                    .contains("\"vulnerableDeps\":1", "\"scanStatus\":\"severity_unknown\"", "\"severityUnknown\":true")
                    .doesNotContain("\"highCount\"", "\"criticalCount\"");
        }

        @Test
        void kotlinGradleMarkerPrecedesGroovyMarker() throws Exception {
            Path project = Files.createDirectory(temporary.toRealPath().resolve("project"));
            Files.writeString(project.resolve("build.gradle.kts"), "implementation(\"org.example:kts:2.0\")");
            Files.writeString(project.resolve("build.gradle"), "implementation 'org.example:ignored:9.9'");
            CountingLookup lookup = new CountingLookup();

            new DependencyService(new BuildToolProvider(), lookup).scanDependencyCves(project.toString(), "HIGH");

            assertThat(lookup.queried).containsExactly(new CveLookupService.PackageRef("org.example", "kts", "2.0"));
        }

        @Test
        void groovyGradleMarkerIsReadWhenOthersAreAbsent() throws Exception {
            Path project = Files.createDirectory(temporary.toRealPath().resolve("project"));
            Files.writeString(project.resolve("build.gradle"), "implementation 'org.example:groovy:3.0'");
            CountingLookup lookup = new CountingLookup();

            new DependencyService(new BuildToolProvider(), lookup).scanDependencyCves(project.toString(), "HIGH");

            assertThat(lookup.queried).containsExactly(new CveLookupService.PackageRef("org.example", "groovy", "3.0"));
        }

        @Test
        void groovySpaceDoubleQuotedDependencyIsRecognized() throws Exception {
            Path project = Files.createDirectory(temporary.toRealPath().resolve("project"));
            Files.writeString(project.resolve("build.gradle"), "implementation \"org.example:groovy-double:3.1\"");
            CountingLookup lookup = new CountingLookup();

            String result = new DependencyService(new BuildToolProvider(), lookup)
                    .scanDependencyCves(project.toString(), "HIGH");

            assertThat(lookup.queried)
                    .containsExactly(new CveLookupService.PackageRef("org.example", "groovy-double", "3.1"));
            assertThat(result).contains("\"totalDeps\":1", "\"scanStatus\":\"complete\"");
        }

        @Test
        void fileSymlinkSwapAfterGuardFailsBeforeEgress() throws Exception {
            Path root = temporary.toRealPath();
            Path allowed = Files.createDirectory(root.resolve("allowed"));
            Path project = Files.createDirectory(allowed.resolve("project"));
            Path pom = Files.writeString(project.resolve("pom.xml"), POM);
            Path outside = Files.writeString(root.resolve("outside.xml"), POM + "PRIVATE_EMAIL@example.com");
            new ProjectAccessPolicy(allowed.toString()).requireAllowed(project.toString());
            Files.delete(pom);
            Files.createSymbolicLink(pom, outside);
            CountingLookup lookup = new CountingLookup();

            String result = new DependencyService(new BuildToolProvider(), lookup)
                    .scanDependencyCves(project.toString(), "HIGH");

            assertThat(lookup.calls).hasValue(0);
            assertThat(result)
                    .contains("Cannot safely read build configuration")
                    .doesNotContain("PRIVATE_EMAIL", project.toString());
        }

        @Test
        void directorySymlinkSwapAfterGuardFailsBeforeEgress() throws Exception {
            Path root = temporary.toRealPath();
            Path allowed = Files.createDirectory(root.resolve("allowed"));
            Path project = Files.createDirectory(allowed.resolve("project"));
            Files.writeString(project.resolve("pom.xml"), POM);
            Path outside = Files.createDirectory(root.resolve("outside"));
            Files.writeString(outside.resolve("pom.xml"), POM + "PRIVATE_SECRET");
            new ProjectAccessPolicy(allowed.toString()).requireAllowed(project.toString());
            Files.move(project, allowed.resolve("moved"));
            Files.createSymbolicLink(project, outside);
            CountingLookup lookup = new CountingLookup();

            String result = new DependencyService(new BuildToolProvider(), lookup)
                    .scanDependencyCves(project.toString(), "HIGH");

            assertThat(lookup.calls).hasValue(0);
            assertThat(result)
                    .contains("Cannot safely read build configuration")
                    .doesNotContain("PRIVATE_SECRET", project.toString());
        }

        @Test
        void oversizedBuildFileFailsBeforeEgress() throws Exception {
            Path project = Files.createDirectory(temporary.toRealPath().resolve("project"));
            Files.writeString(project.resolve("pom.xml"), "x".repeat(1024 * 1024 + 1));
            CountingLookup lookup = new CountingLookup();

            String result = new DependencyService(new BuildToolProvider(), lookup)
                    .scanDependencyCves(project.toString(), "HIGH");

            assertThat(lookup.calls).hasValue(0);
            assertThat(result).contains("exceeds the 1 MiB scan limit").doesNotContain(project.toString());
        }

        @Test
        void malformedUtf8FailsBeforeEgress() throws Exception {
            Path project = Files.createDirectory(temporary.toRealPath().resolve("project"));
            Files.write(project.resolve("build.gradle.kts"), new byte[] {(byte) 0xc3, (byte) 0x28});
            CountingLookup lookup = new CountingLookup();

            String result = new DependencyService(new BuildToolProvider(), lookup)
                    .scanDependencyCves(project.toString(), "HIGH");

            assertThat(lookup.calls).hasValue(0);
            assertThat(result).contains("not valid UTF-8").doesNotContain(project.toString());
        }
    }
}
