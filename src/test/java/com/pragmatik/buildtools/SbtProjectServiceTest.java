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

class SbtProjectServiceTest {

    private SbtProjectService service;

    @BeforeEach
    void setUp() {
        service = new SbtProjectService();
    }

    @Nested
    @DisplayName("Module detection")
    class ModuleDetection {

        @Test
        @DisplayName("detects multi-module project with lazy vals")
        void detectsMultiModule(@TempDir Path tmp) throws Exception {
            String buildSbt = """
                lazy val root = project.in(file("."))
                  .aggregate(core, api)
                  .settings(name := "myapp")

                lazy val core = project.in(file("core"))
                  .settings(name := "myapp-core")

                lazy val api = project.in(file("api"))
                  .settings(name := "myapp-api")
                  .dependsOn(core)""";
            Files.writeString(tmp.resolve("build.sbt"), buildSbt);
            Files.createDirectory(tmp.resolve("core"));
            Files.createDirectory(tmp.resolve("api"));

            String result = service.detectSbtModules(tmp.toString());

            assertTrue(result.contains("root"));
            assertTrue(result.contains("core"));
            assertTrue(result.contains("api"));
            assertTrue(result.contains("\"multiModule\":true"));
            assertTrue(result.contains("\"moduleCount\":3"));
            assertTrue(result.contains("\"existsOnDisk\":true"));
        }

        @Test
        @DisplayName("detects single-module project")
        void detectsSingleModule(@TempDir Path tmp) throws Exception {
            Files.writeString(tmp.resolve("build.sbt"),
                "name := \"myapp\"\nscalaVersion := \"2.13.15\"");

            String result = service.detectSbtModules(tmp.toString());

            assertTrue(result.contains("\"multiModule\":false"));
            assertTrue(result.contains("\"moduleCount\":0"));
        }

        @Test
        @DisplayName("returns error when no build.sbt")
        void noBuildSbt(@TempDir Path tmp) {
            String result = service.detectSbtModules(tmp.toString());
            assertTrue(result.contains("\"error\":true"));
            assertTrue(result.contains("No build.sbt"));
        }
    }

    @Nested
    @DisplayName("Test framework detection")
    class TestFrameworkDetection {

        @Test
        @DisplayName("detects ScalaTest and specs2")
        void detectsTestFrameworks(@TempDir Path tmp) throws Exception {
            String buildSbt = """
                scalaVersion := "2.13.15"
                libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.18" % Test
                libraryDependencies += "org.specs2" %% "specs2-core" % "4.20.0" % Test
                libraryDependencies += "com.lihaoyi" %% "os-lib" % "0.10.0\"""";
            Files.writeString(tmp.resolve("build.sbt"), buildSbt);

            String result = service.detectSbtTestFrameworks(tmp.toString());

            assertTrue(result.contains("scalatest"));
            assertTrue(result.contains("specs2-core"));
            assertTrue(result.contains("\"scope\":\"Test\""));
            assertTrue(result.contains("\"frameworkCount\":2"));
        }

        @Test
        @DisplayName("detects test settings")
        void detectsTestSettings(@TempDir Path tmp) throws Exception {
            String buildSbt = """
                scalaVersion := "2.13.15"
                Test / fork := true
                Test / parallelExecution := false
                testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oD")""";
            Files.writeString(tmp.resolve("build.sbt"), buildSbt);

            String result = service.detectSbtTestFrameworks(tmp.toString());

            assertTrue(result.contains("Test / fork"));
            assertTrue(result.contains("Test / parallelExecution"));
            assertTrue(result.contains("testOptions"));
        }

        @Test
        @DisplayName("returns empty when no test frameworks")
        void noTestFrameworks(@TempDir Path tmp) throws Exception {
            Files.writeString(tmp.resolve("build.sbt"),
                "name := \"myapp\"\nscalaVersion := \"2.13.15\"");

            String result = service.detectSbtTestFrameworks(tmp.toString());

            assertTrue(result.contains("\"frameworkCount\":0"));
        }
    }

    @Nested
    @DisplayName("Build analysis")
    class BuildAnalysis {

        @Test
        @DisplayName("analyzes complete build configuration")
        void analyzesBuild(@TempDir Path tmp) throws Exception {
            Files.writeString(tmp.resolve("build.sbt"), """
                organization := "com.example"
                name := "myapp"
                scalaVersion := "2.13.15"
                crossScalaVersions := Seq("2.12.20", "2.13.15", "3.3.4")
                scalacOptions ++= Seq("-deprecation", "-feature", "-Xfatal-warnings")
                resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots\"""");
            Files.createDirectory(tmp.resolve("project"));
            Files.writeString(tmp.resolve("project/build.properties"), "sbt.version=1.10.0");

            String result = service.analyzeSbtBuild(tmp.toString());

            assertTrue(result.contains("\"organization\":\"com.example\""));
            assertTrue(result.contains("\"scalaVersion\":\"2.13.15\""));
            assertTrue(result.contains("\"sbtVersion\":\"1.10.0\""));
            assertTrue(result.contains("2.12.20"));
            assertTrue(result.contains("3.3.4"));
            assertTrue(result.contains("-deprecation"));
            assertTrue(result.contains("customResolvers"), "customResolvers missing");
            assertTrue(result.contains("Sonatype"), "Sonatype missing");
        }

        @Test
        @DisplayName("handles missing project/build.properties gracefully")
        void missingProperties(@TempDir Path tmp) throws Exception {
            Files.writeString(tmp.resolve("build.sbt"),
                "name := \"simple\"\nscalaVersion := \"3.3.4\"");

            String result = service.analyzeSbtBuild(tmp.toString());

            assertTrue(result.contains("\"scalaVersion\":\"3.3.4\""));
            assertFalse(result.contains("sbtVersion"));
        }
    }
}
