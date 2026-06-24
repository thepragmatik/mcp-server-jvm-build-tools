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
package com.pragmatik.buildtools.tool;

import com.pragmatik.buildtools.build.BuildToolProvider;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SupplyChainService}.
 */
class SupplyChainServiceTest {

    private SupplyChainService service;
    private BuildToolProvider provider;

    @BeforeEach
    void setUp() {
        provider = new BuildToolProvider();
        service = new SupplyChainService(provider);
    }

    @Test
    void testGenerateSbomMavenNoPlugin(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                            <version>3.5.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        String result = service.generateSbom(tempDir.toString(), null);
        assertNotNull(result);
        assertTrue(result.contains("\"tool\":\"maven\""));
        assertTrue(result.contains("\"format\":\"cyclonedx\""));
        assertTrue(result.contains("cyclonedx-maven-plugin"));
        // Plugin not configured
        assertTrue(result.contains("\"pluginAlreadyConfigured\":false"));
    }

    @Test
    void testGenerateSbomWithExistingBom(@TempDir Path tempDir) throws IOException {
        // Create project
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        // Create a fake bom.json
        Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(
                tempDir.resolve("target/bom.json"),
                """
                {
                  "bomFormat": "CycloneDX",
                  "specVersion": "1.6",
                  "components": [
                    {
                      "group": "com.example",
                      "name": "test-lib",
                      "version": "1.0.0",
                      "type": "library",
                      "purl": "pkg:maven/com.example/test-lib@1.0.0"
                    }
                  ]
                }
                """);

        String result = service.generateSbom(tempDir.toString(), null);
        assertNotNull(result);
        assertTrue(result.contains("\"source\":\"existing-file\""));
        assertTrue(result.contains("target/bom.json"));
        assertTrue(result.contains("\"componentCount\""));
    }

    @Test
    void testGenerateSbomPluginAlreadyConfigured(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.cyclonedx</groupId>
                                <artifactId>cyclonedx-maven-plugin</artifactId>
                                <version>2.9.1</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """);

        String result = service.generateSbom(tempDir.toString(), null);
        assertNotNull(result);
        assertTrue(result.contains("\"pluginAlreadyConfigured\":true"));
    }

    @Test
    void testGenerateSbomGradle(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("build.gradle"),
                """
                plugins {
                    id 'java'
                }

                dependencies {
                    implementation 'com.google.guava:guava:33.0.0-jre'
                }
                """);

        String result = service.generateSbom(tempDir.toString(), null);
        assertNotNull(result);
        assertTrue(result.contains("\"tool\":\"gradle\""));
        assertTrue(result.contains("cyclonedxBom"));
    }

    @Test
    void testGenerateSbomSbt(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("build.sbt"),
                """
                name := "test"
                version := "1.0.0"
                libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test
                """);

        String result = service.generateSbom(tempDir.toString(), null);
        assertNotNull(result);
        assertTrue(result.contains("\"tool\":\"sbt\""));
        assertTrue(result.contains("cyclonedxBom"));
    }

    @Test
    void testAuditSupplyChainNoSbom(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                            <version>3.5.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        String result = service.auditSupplyChain(tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"sbomAvailable\":false"));
        assertTrue(result.contains("\"componentCount\""));
    }

    @Test
    void testAuditSupplyChainWithSbom(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(
                tempDir.resolve("target/bom.json"),
                """
                {
                  "bomFormat": "CycloneDX",
                  "components": [
                    {"group": "org.springframework.boot", "name": "spring-boot-starter-web", "version": "3.5.0"},
                    {"group": "com.fasterxml.jackson.core", "name": "jackson-databind", "version": "2.18.0"}
                  ]
                }
                """);

        String result = service.auditSupplyChain(tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"sbomAvailable\":true"));
        assertTrue(result.contains("\"vulnerabilities\""));
        assertTrue(result.contains("\"severityBreakdown\""));
    }

    @Test
    void testCheckLicenseComplianceMaven(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter</artifactId>
                            <version>3.5.0</version>
                        </dependency>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>33.0.0-jre</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        String result = service.checkLicenseCompliance(tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"licenses\""));
        assertTrue(result.contains("\"summary\""));
        assertTrue(result.contains("\"compliant\""));
    }

    @Test
    void testCheckLicenseComplianceGradle(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("build.gradle"),
                """
                plugins {
                    id 'java'
                }
                dependencies {
                    implementation 'com.google.guava:guava:33.0.0-jre'
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.0'
                }
                """);

        String result = service.checkLicenseCompliance(tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"licenses\""));
    }

    @Test
    void testInferLicense() {
        assertEquals("Apache-2.0", SupplyChainService.inferLicense("org.springframework.boot", "spring-boot-starter"));
        assertEquals("Apache-2.0", SupplyChainService.inferLicense("com.google.guava", "guava"));
        assertEquals("MIT", SupplyChainService.inferLicense("org.slf4j", "slf4j-api"));
        assertEquals("EPL-2.0", SupplyChainService.inferLicense("org.junit.jupiter", "junit-jupiter"));
        assertEquals("LGPL-2.1-only", SupplyChainService.inferLicense("org.hibernate", "hibernate-core"));
        assertEquals("GPL-2.0-only", SupplyChainService.inferLicense("mysql", "mysql-connector-java"));
        assertEquals("BSD-2-Clause", SupplyChainService.inferLicense("org.postgresql", "postgresql"));
        assertEquals("UNKNOWN", SupplyChainService.inferLicense("com.unknown.vendor", "unknown-lib"));
    }

    @Test
    void testGenerateSbomInvalidDir() {
        String result = service.generateSbom("/nonexistent/path", null);
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
    }

    @Test
    void testAuditInvalidDir() {
        String result = service.auditSupplyChain("/nonexistent/path");
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
    }

    @Test
    void testCheckLicenseInvalidDir() {
        String result = service.checkLicenseCompliance("/nonexistent/path");
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
    }

    @Test
    void testGenerateSbomSpdxFormat(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        String result = service.generateSbom(tempDir.toString(), "spdx");
        assertNotNull(result);
        assertTrue(result.contains("\"format\":\"spdx\""));
        assertTrue(result.contains("bom.spdx"));
    }
}
