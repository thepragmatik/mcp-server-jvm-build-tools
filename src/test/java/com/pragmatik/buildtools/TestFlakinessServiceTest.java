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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link TestFlakinessService}.
 */
class TestFlakinessServiceTest {

    private TestFlakinessService service;
    private BuildToolProvider provider;

    @BeforeEach
    void setUp() {
        provider = new BuildToolProvider();
        service = new TestFlakinessService(provider);
    }

    @Test
    void testDetectFlakyTestsNoReports(@TempDir Path tempDir) throws IOException {
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

        String result = service.detectFlakyTests(tempDir.toString(), 3, null);
        assertNotNull(result);
        assertTrue(result.contains("\"note\""));
        assertTrue(result.contains("No existing test report files found"));
    }

    @Test
    void testDetectFlakyTestsWithSurefireReports(@TempDir Path tempDir) throws IOException {
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

        // Create Surefire XML report
        Path surefireDir = tempDir.resolve("target/surefire-reports");
        Files.createDirectories(surefireDir);
        Files.writeString(
                surefireDir.resolve("TEST-com.example.MyTest.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.MyTest" tests="3" failures="1" errors="0" skipped="0" time="1.234">
                  <testcase name="testPasses" classname="com.example.MyTest" time="0.100"/>
                  <testcase name="testFails" classname="com.example.MyTest" time="0.200">
                    <failure message="expected: true but was: false"/>
                  </testcase>
                  <testcase name="testAlsoPasses" classname="com.example.MyTest" time="0.150"/>
                </testsuite>
                """);

        String result = service.detectFlakyTests(tempDir.toString(), 5, null);
        assertNotNull(result);
        assertTrue(result.contains("\"totalTests\""));
        assertTrue(result.contains("\"flakyTests\""));
        assertTrue(result.contains("testFails"));
    }

    @Test
    void testDetectFlakyTestsWithGradleReports(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                """);

        Path testResultsDir = tempDir.resolve("build/test-results/test");
        Files.createDirectories(testResultsDir);
        Files.writeString(
                testResultsDir.resolve("TEST-com.example.MyTest.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.MyTest" tests="2" failures="0" errors="0" skipped="0" time="0.500">
                  <testcase name="testA" classname="com.example.MyTest" time="0.200"/>
                  <testcase name="testB" classname="com.example.MyTest" time="0.300"/>
                </testsuite>
                """);

        String result = service.detectFlakyTests(tempDir.toString(), 3, null);
        assertNotNull(result);
        assertTrue(result.contains("\"totalTests\""));
    }

    @Test
    void testDetectFlakyTestsInvalidDir() {
        String result = service.detectFlakyTests("/nonexistent", 5, null);
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
    }

    @Test
    void testAnalyzeTestHistoryNoHistory(@TempDir Path tempDir) throws IOException {
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

        String result = service.analyzeTestHistory(tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"totalBuilds\":0"));
    }

    @Test
    void testAnalyzeTestHistoryWithData(@TempDir Path tempDir) throws IOException {
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

        // Create history directory with entries
        Path historyDir = tempDir.resolve(".buildtools/history");
        Files.createDirectories(historyDir);

        String entry1 =
                """
                [
                  {"timestamp":"2026-06-10T10:00:00Z","durationSeconds":12.5,"success":true,"phases":8}
                ]
                """;
        Files.writeString(historyDir.resolve("maven_test.json"), entry1);

        String entry2 =
                """
                [
                  {"timestamp":"2026-06-11T10:00:00Z","durationSeconds":15.3,"success":false,"phases":8}
                ]
                """;
        Files.writeString(historyDir.resolve("maven_test2.json"), entry2);

        String result = service.analyzeTestHistory(tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"totalBuilds\""));
    }

    @Test
    void testAnalyzeTestHistoryInvalidDir() {
        String result = service.analyzeTestHistory("/nonexistent");
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
    }

    @Test
    void testDetectFlakyTestsWithFilter(@TempDir Path tempDir) throws IOException {
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

        Path surefireDir = tempDir.resolve("target/surefire-reports");
        Files.createDirectories(surefireDir);
        Files.writeString(
                surefireDir.resolve("TEST-com.example.ServiceTest.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.ServiceTest" tests="1" failures="0" errors="0" skipped="0" time="0.100">
                  <testcase name="testService" classname="com.example.ServiceTest" time="0.100"/>
                </testsuite>
                """);

        String result = service.detectFlakyTests(tempDir.toString(), 10, "com.example.ServiceTest");
        assertNotNull(result);
        assertTrue(result.contains("ServiceTest"));
        assertTrue(result.contains("\"suggestedCommand\""));
    }

    @Test
    void testAllTestsStable(@TempDir Path tempDir) throws IOException {
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

        Path surefireDir = tempDir.resolve("target/surefire-reports");
        Files.createDirectories(surefireDir);
        // All passing
        Files.writeString(
                surefireDir.resolve("TEST-com.example.AllPassTest.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.AllPassTest" tests="3" failures="0" errors="0" skipped="0" time="0.300">
                  <testcase name="testOne" classname="com.example.AllPassTest" time="0.100"/>
                  <testcase name="testTwo" classname="com.example.AllPassTest" time="0.100"/>
                  <testcase name="testThree" classname="com.example.AllPassTest" time="0.100"/>
                </testsuite>
                """);

        String result = service.detectFlakyTests(tempDir.toString(), 5, null);
        assertNotNull(result);
        assertTrue(result.contains("\"flakyCount\":0"));
    }
}
