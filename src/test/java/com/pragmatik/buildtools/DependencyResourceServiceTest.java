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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DependencyResourceServiceTest {

    private DependencyResourceService service;

    @BeforeEach
    void setUp() {
        var provider = new BuildToolProvider();
        service = new DependencyResourceService(provider);
    }

    @Nested
    @DisplayName("Maven dependency extraction")
    class MavenExtraction {

        @Test
        @DisplayName("extracts dependencies from pom.xml")
        void extractsMavenDeps(@TempDir Path tmp) throws Exception {
            String pom = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                      <version>33.0.0-jre</version>
                    </dependency>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.10.0</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>""";
            Files.writeString(tmp.resolve("pom.xml"), pom);

            String result = service.readDependencyResource(
                "build://test/dependencies/maven", tmp.toString());

            assertTrue(result.contains("spring-boot-starter"));
            assertTrue(result.contains("guava"));
            assertTrue(result.contains("junit-jupiter"));
            assertTrue(result.contains("[managed]"));
            assertTrue(result.contains("test"));
            assertTrue(result.contains("\"buildTool\":\"maven\""));
        }
    }

    @Nested
    @DisplayName("Gradle dependency extraction")
    class GradleExtraction {

        @Test
        @DisplayName("extracts groovy DSL dependencies")
        void extractsGroovyDeps(@TempDir Path tmp) throws Exception {
            String gradle = """
                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter:3.2.0'
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
                    runtimeOnly 'com.h2database:h2:2.2.224'
                }""";
            Files.writeString(tmp.resolve("build.gradle"), gradle);

            String result = service.readDependencyResource(
                "build://test/dependencies/gradle", tmp.toString());

            assertTrue(result.contains("spring-boot-starter"));
            assertTrue(result.contains("3.2.0"));
            assertTrue(result.contains("junit-jupiter"));
            assertTrue(result.contains("h2"));
            assertTrue(result.contains("\"configuration\":\"implementation\""));
            assertTrue(result.contains("\"configuration\":\"testImplementation\""));
            assertTrue(result.contains("\"configuration\":\"runtimeOnly\""));
            assertTrue(result.contains("groovy-dsl"));
        }

        @Test
        @DisplayName("extracts kotlin DSL dependencies")
        void extractsKotlinDeps(@TempDir Path tmp) throws Exception {
            String gradle = """
                dependencies {
                    implementation("org.springframework.boot:spring-boot-starter:3.2.0")
                    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
                }""";
            Files.writeString(tmp.resolve("build.gradle.kts"), gradle);

            String result = service.readDependencyResource(
                "build://test/dependencies/gradle", tmp.toString());

            assertTrue(result.contains("spring-boot-starter"));
            assertTrue(result.contains("junit-jupiter"));
            assertTrue(result.contains("kotlin-dsl"));
        }
    }

    @Nested
    @DisplayName("SBT dependency extraction")
    class SbtExtraction {

        @Test
        @DisplayName("extracts SBT dependencies with % and %%")
        void extractsSbtDeps(@TempDir Path tmp) throws Exception {
            String sbt = """
                scalaVersion := "2.13.15"
                libraryDependencies += "org.typelevel" %% "cats-core" % "2.12.0"
                libraryDependencies += "com.lihaoyi" %% "os-lib" % "0.10.0"
                libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.18" % Test""";
            Files.writeString(tmp.resolve("build.sbt"), sbt);

            String result = service.readDependencyResource(
                "build://test/dependencies/sbt", tmp.toString());

            assertTrue(result.contains("cats-core"));
            assertTrue(result.contains("os-lib"));
            assertTrue(result.contains("scalatest"));
            assertTrue(result.contains("2.12.0"));
            assertTrue(result.contains("2.13.15"));
            assertTrue(result.contains("\"buildTool\":\"sbt\""));
            assertTrue(result.contains("\"scalaVersioned\":true"));
        }
    }

    @Nested
    @DisplayName("Resource listing")
    class ResourceListing {

        @Test
        @DisplayName("lists available dependency resources")
        void listsResources(@TempDir Path tmp) throws Exception {
            Files.writeString(tmp.resolve("pom.xml"), "<project></project>");
            Files.writeString(tmp.resolve("build.gradle"), "");

            String result = service.listDependencyResources(tmp.toString());

            assertTrue(result.contains("dependencies/maven"));
            assertTrue(result.contains("dependencies/gradle"));
            assertTrue(result.contains("\"resourceCount\":2"));
        }

        @Test
        @DisplayName("returns error for invalid directory")
        void invalidDir() {
            String result = service.listDependencyResources("/nonexistent/dir");
            assertTrue(result.contains("\"error\":true"));
        }

        @Test
        @DisplayName("returns error for unknown URI")
        void unknownUri(@TempDir Path tmp) throws Exception {
            Files.writeString(tmp.resolve("pom.xml"), "<project></project>");

            String result = service.readDependencyResource(
                "build://test/dependencies/unknown", tmp.toString());
            assertTrue(result.contains("\"error\":true"));
        }
    }
}
