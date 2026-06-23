package com.pragmatik.buildtools;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaVersionServiceTest {

    private JavaVersionService service;

    @BeforeEach
    void setUp() {
        service = new JavaVersionService();
    }

    @Test
    void testDetectMavenJavaVersion(@TempDir Path tempDir) throws IOException {
        String pom =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0</version>
                    <properties>
                        <maven.compiler.release>21</maven.compiler.release>
                    </properties>
                </project>
                """;
        Files.writeString(tempDir.resolve("pom.xml"), pom);

        // Use reflection to test the private method
        String result = service.checkJavaCompatibility(tempDir.toString(), null);
        assertNotNull(result);
        assertTrue(result.contains("21"));
    }

    @Test
    void testMavenSourceTargetDetection(@TempDir Path tempDir) throws IOException {
        String pom =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0</version>
                    <properties>
                        <maven.compiler.source>17</maven.compiler.source>
                        <maven.compiler.target>17</maven.compiler.target>
                    </properties>
                </project>
                """;
        Files.writeString(tempDir.resolve("pom.xml"), pom);

        String result = service.checkJavaCompatibility(tempDir.toString(), "21");
        assertTrue(result.contains("17"));
        assertTrue(result.contains("21"));
        assertTrue(result.contains("compatible"));
    }

    @Test
    void testGradleJavaVersionDetection(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("build.gradle"),
                """
                plugins { id 'java' }
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(17)
                    }
                }
                sourceCompatibility = '17'
                """);
        Files.writeString(tempDir.resolve("settings.gradle"), "");

        String result = service.checkJavaCompatibility(tempDir.toString(), "21");
        assertNotNull(result);
        assertTrue(result.contains("17"));
    }

    @Test
    void testBreakingChangesWarning(@TempDir Path tempDir) throws IOException {
        String pom =
                """
                <?xml version="1.0"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0</version>
                </project>
                """;
        Files.writeString(tempDir.resolve("pom.xml"), pom);

        // Check from Java 8 (default for POM without version) to Java 21
        String result = service.checkJavaCompatibility(tempDir.toString(), "21");
        assertNotNull(result);
        // Should warn about breaking changes in Java 17 and 21
        assertTrue(result.contains("breaking_change"));
    }

    @Test
    void testNonLtsWarning(@TempDir Path tempDir) throws IOException {
        String pom =
                """
                <?xml version="1.0"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0</version>
                    <properties>
                        <maven.compiler.release>21</maven.compiler.release>
                    </properties>
                </project>
                """;
        Files.writeString(tempDir.resolve("pom.xml"), pom);

        String result = service.checkJavaCompatibility(tempDir.toString(), "24");
        assertNotNull(result);
        assertTrue(result.contains("non_lts") || result.contains("not an LTS"));
    }

    @Test
    void testFrameworkCompatibility(@TempDir Path tempDir) throws IOException {
        String pom =
                """
                <?xml version="1.0"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0</version>
                    <properties>
                        <maven.compiler.release>8</maven.compiler.release>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot</artifactId>
                            <version>3.2.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(tempDir.resolve("pom.xml"), pom);

        String result = service.checkJavaCompatibility(tempDir.toString(), null);
        assertNotNull(result);
        // Spring Boot 3.2 requires Java 17+, but project targets 8
        assertTrue(result.contains("requiresJavaVersion"));
    }
}
