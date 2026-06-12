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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for multi-build-tool dependency extraction (Maven, Gradle, SBT).
 */
class DependencyResourceServiceTest {

    @Test
    void listResources_findsMavenPom(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");

        BuildToolProvider provider = new BuildToolProvider();
        DependencyResourceService svc = new DependencyResourceService(provider);

        String result = svc.listDependencyResources(tempDir.toString());
        assertTrue(result.contains("\"maven\""));
        assertTrue(result.contains("/dependencies/maven"));
    }

    @Test
    void listResources_findsGradleGroovy(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("build.gradle"), "");

        BuildToolProvider provider = new BuildToolProvider();
        DependencyResourceService svc = new DependencyResourceService(provider);

        String result = svc.listDependencyResources(tempDir.toString());
        assertTrue(result.contains("\"gradle\""));
        assertTrue(result.contains("/dependencies/gradle"));
    }

    @Test
    void listResources_findsGradleKotlin(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("build.gradle.kts"), "");

        BuildToolProvider provider = new BuildToolProvider();
        DependencyResourceService svc = new DependencyResourceService(provider);

        String result = svc.listDependencyResources(tempDir.toString());
        assertTrue(result.contains("\"gradle\""));
        assertTrue(result.contains("/dependencies/gradle"));
    }

    @Test
    void listResources_findsSbt(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("build.sbt"), "");

        BuildToolProvider provider = new BuildToolProvider();
        DependencyResourceService svc = new DependencyResourceService(provider);

        String result = svc.listDependencyResources(tempDir.toString());
        assertTrue(result.contains("\"sbt\""));
        assertTrue(result.contains("/dependencies/sbt"));
    }

    @Test
    void listResources_returnsErrorForInvalidDir() {
        BuildToolProvider provider = new BuildToolProvider();
        DependencyResourceService svc = new DependencyResourceService(provider);

        String result = svc.listDependencyResources("/nonexistent/path/12345");
        assertTrue(result.contains("\"error\""));
    }

    @Test
    void listResources_allThreeTools(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");
        Files.writeString(tempDir.resolve("build.gradle"), "");
        Files.writeString(tempDir.resolve("build.sbt"), "");

        BuildToolProvider provider = new BuildToolProvider();
        DependencyResourceService svc = new DependencyResourceService(provider);

        String result = svc.listDependencyResources(tempDir.toString());
        assertTrue(result.contains("\"maven\""));
        assertTrue(result.contains("\"gradle\""));
        assertTrue(result.contains("\"sbt\""));
        // resourceCount should be 3
        assertTrue(result.contains("\"resourceCount\":3"));
    }

    @Test
    void extractMavenDependencies(@TempDir Path tempDir) throws Exception {
        String pom = """
            <project>
                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter</artifactId>
                        <version>3.5.14</version>
                    </dependency>
                    <dependency>
                        <groupId>junit</groupId>
                        <artifactId>junit</artifactId>
                        <version>4.13.2</version>
                        <scope>test</scope>
                    </dependency>
                </dependencies>
            </project>""";
        Files.writeString(tempDir.resolve("pom.xml"), pom);

        BuildToolProvider provider = new BuildToolProvider();
        DependencyResourceService svc = new DependencyResourceService(provider);

        String result = svc.readDependencyResource(
            "build://test/dependencies/maven", tempDir.toString());
        assertTrue(result.contains("spring-boot-starter"));
        assertTrue(result.contains("3.5.14"));
        assertTrue(result.contains("junit"));
        assertTrue(result.contains("test"));
        assertTrue(result.contains("\"dependencyCount\":2"));
    }

    @Test
    void extractGradleGroovyDependencies(@TempDir Path tempDir) throws Exception {
        String gradle = """
            dependencies {
                implementation 'com.google.guava:guava:33.4.0-jre'
                testImplementation 'junit:junit:4.13.2'
            }""";
        Files.writeString(tempDir.resolve("build.gradle"), gradle);

        BuildToolProvider provider = new BuildToolProvider();
        DependencyResourceService svc = new DependencyResourceService(provider);

        String result = svc.readDependencyResource(
            "build://test/dependencies/gradle", tempDir.toString());
        assertTrue(result.contains("guava"));
        assertTrue(result.contains("33.4.0-jre"));
        assertTrue(result.contains("groovy-dsl"));
    }

    @Test
    void extractGradleKotlinDependencies(@TempDir Path tempDir) throws Exception {
        String gradle = """
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
                testImplementation("io.kotest:kotest-runner-junit5:5.9.0")
            }""";
        Files.writeString(tempDir.resolve("build.gradle.kts"), gradle);

        BuildToolProvider provider = new BuildToolProvider();
        DependencyResourceService svc = new DependencyResourceService(provider);

        String result = svc.readDependencyResource(
            "build://test/dependencies/gradle", tempDir.toString());
        assertTrue(result.contains("kotlin-stdlib"));
        assertTrue(result.contains("kotlin-dsl"));
        assertTrue(result.contains("2.1.0"));
    }

    @Test
    void extractSbtDependencies(@TempDir Path tempDir) throws Exception {
        String sbt = """
            scalaVersion := "2.13.16"
            libraryDependencies ++= Seq(
              "org.typelevel" %% "cats-core" % "2.12.0",
              "org.scalatest" %% "scalatest" % "3.2.19" % Test
            )""";
        Files.writeString(tempDir.resolve("build.sbt"), sbt);

        BuildToolProvider provider = new BuildToolProvider();
        DependencyResourceService svc = new DependencyResourceService(provider);

        String result = svc.readDependencyResource(
            "build://test/dependencies/sbt", tempDir.toString());
        assertTrue(result.contains("cats-core"));
        assertTrue(result.contains("2.12.0"));
        assertTrue(result.contains("scalaVersion"));
        assertTrue(result.contains("2.13.16"));
        assertTrue(result.contains("\"scalaVersioned\":true"));
    }

    @Test
    void unknownResourceUri_returnsError(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");

        BuildToolProvider provider = new BuildToolProvider();
        DependencyResourceService svc = new DependencyResourceService(provider);

        String result = svc.readDependencyResource(
            "build://test/bogus", tempDir.toString());
        assertTrue(result.contains("\"error\""));
    }

    @Test
    void gradleDependencies_noGradleFile_returnsNotAvailable(@TempDir Path tempDir) throws Exception {
        BuildToolProvider provider = new BuildToolProvider();
        DependencyResourceService svc = new DependencyResourceService(provider);

        String result = svc.readDependencyResource(
            "build://test/dependencies/gradle", tempDir.toString());
        assertTrue(result.contains("\"available\":false"));
    }
}
