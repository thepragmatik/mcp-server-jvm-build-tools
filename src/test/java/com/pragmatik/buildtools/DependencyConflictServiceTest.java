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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DependencyConflictServiceTest {

    private DependencyConflictService service;

    @BeforeEach
    void setUp() {
        BuildToolProvider provider = new BuildToolProvider(
                List.of(new MavenBuildTool(), new GradleBuildTool(), new SbtBuildTool()));
        service = new DependencyConflictService(provider);
    }

    @Test
    void testNoConflictsInCleanPom(@TempDir Path tempDir) throws IOException {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>junit</groupId>
                            <artifactId>junit</artifactId>
                            <version>4.13.2</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(tempDir.resolve("pom.xml"), pom);

        List<Map<String, Object>> conflicts = service.analyzeMavenConflicts(tempDir.resolve("pom.xml"));
        assertTrue(conflicts.isEmpty(), "Clean POM should have no conflicts");
    }

    @Test
    void testDuplicateDependencyWithDifferentVersions(@TempDir Path tempDir) throws IOException {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>31.0-jre</version>
                        </dependency>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>33.0-jre</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(tempDir.resolve("pom.xml"), pom);

        List<Map<String, Object>> conflicts = service.analyzeMavenConflicts(tempDir.resolve("pom.xml"));
        assertFalse(conflicts.isEmpty(), "Should detect duplicate dependency with different versions");
        assertEquals(1, conflicts.size());
        Map<String, Object> c = conflicts.get(0);
        assertEquals("com.google.guava", c.get("groupId"));
        assertEquals("guava", c.get("artifactId"));
        assertEquals("WARNING", c.get("severity"));
    }

    @Test
    void testDependencyVsManagementConflict(@TempDir Path tempDir) throws IOException {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.slf4j</groupId>
                                <artifactId>slf4j-api</artifactId>
                                <version>2.0.9</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>1.7.36</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(tempDir.resolve("pom.xml"), pom);

        List<Map<String, Object>> conflicts = service.analyzeMavenConflicts(tempDir.resolve("pom.xml"));
        assertFalse(conflicts.isEmpty(), "Should detect dep vs management conflict");
        Map<String, Object> c = conflicts.get(0);
        assertEquals("ERROR", c.get("severity"),
                "Direct vs managed version conflict should be ERROR severity");
    }

    @Test
    void testMavenPropertiesResolution(@TempDir Path tempDir) throws IOException {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <spring.version>6.1.0</spring.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework</groupId>
                            <artifactId>spring-core</artifactId>
                            <version>${spring.version}</version>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework</groupId>
                            <artifactId>spring-core</artifactId>
                            <version>5.3.30</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(tempDir.resolve("pom.xml"), pom);

        List<Map<String, Object>> conflicts = service.analyzeMavenConflicts(tempDir.resolve("pom.xml"));
        assertFalse(conflicts.isEmpty());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> versions = (List<Map<String, Object>>) conflicts.get(0).get("versions");
        boolean hasResolved = versions.stream()
                .anyMatch(v -> "6.1.0".equals(v.get("version")));
        assertTrue(hasResolved, "Property ${spring.version} should resolve to 6.1.0");
    }

    @Test
    void testGradleConflictDetection(@TempDir Path tempDir) throws IOException {
        String gradle = """
                dependencies {
                    implementation 'com.google.guava:guava:31.0-jre'
                    testImplementation 'com.google.guava:guava:33.0-jre'
                }
                """;
        Files.writeString(tempDir.resolve("build.gradle"), gradle);

        List<Map<String, Object>> conflicts = service.analyzeGradleConflicts(tempDir.resolve("build.gradle"));
        assertFalse(conflicts.isEmpty());
        assertEquals("WARNING", conflicts.get(0).get("severity"));
    }

    @Test
    void testSbtConflictDetection(@TempDir Path tempDir) throws IOException {
        String sbt = """
                libraryDependencies += "org.typelevel" %% "cats-core" % "2.9.0"
                libraryDependencies += "org.typelevel" %% "cats-core" % "2.10.0"
                """;
        Files.writeString(tempDir.resolve("build.sbt"), sbt);

        List<Map<String, Object>> conflicts = service.analyzeSbtConflicts(tempDir.resolve("build.sbt"));
        assertFalse(conflicts.isEmpty());
        assertEquals("WARNING", conflicts.get(0).get("severity"));
    }

    @Test
    void testNoSbtConflicts(@TempDir Path tempDir) throws IOException {
        String sbt = """
                libraryDependencies += "org.typelevel" %% "cats-core" % "2.10.0"
                libraryDependencies += "org.typelevel" %% "cats-effect" % "3.5.0"
                """;
        Files.writeString(tempDir.resolve("build.sbt"), sbt);

        List<Map<String, Object>> conflicts = service.analyzeSbtConflicts(tempDir.resolve("build.sbt"));
        assertTrue(conflicts.isEmpty(), "Different artifacts should not conflict");
    }
}
