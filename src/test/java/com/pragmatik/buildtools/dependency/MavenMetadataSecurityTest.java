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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pragmatik.buildtools.build.BuildToolProvider;
import com.pragmatik.buildtools.dependency.security.CveLookupService;
import com.pragmatik.buildtools.security.ModelOutputPolicy;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MavenMetadataSecurityTest {
    private static final String METADATA = """
            <metadata><versioning><latest>1.2.0</latest><release>1.2.0</release>
            <versions><version>1.0.0</version><version>1.2.0</version></versions>
            </versioning></metadata>
            """;
    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger redirectHits = new AtomicInteger();

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void rejectsCoordinatesBeforeAnyNetworkRequestOrReflection() throws Exception {
        serve(200, METADATA);
        DependencyService service = service(Duration.ofSeconds(2));
        for (String group :
                new String[] {"../private", "a..b", "a/b", "secret@private.invalid", "a%2Fb", "x".repeat(257)}) {
            String result = service.checkDependencyVersion(group, "artifact", null, null, null, false);
            assertThat(result).contains("Invalid Maven group or artifact ID").doesNotContain(group);
        }
        for (String artifact : new String[] {"../private", "a/b", "token?secret", "a%2Fb", "x".repeat(129)}) {
            String result = service.checkDependencyVersion("org.example", artifact, null, null, null, false);
            assertThat(result).contains("Invalid Maven group or artifact ID").doesNotContain(artifact);
        }
        assertThat(requests).hasValue(0);
    }

    @Test
    void validCoordinatesUseOnlyExpectedMetadataPath() throws Exception {
        serve(200, METADATA);
        String result = service(Duration.ofSeconds(2))
                .checkDependencyVersion("org.example", "good-artifact", null, null, null, false);
        assertThat(result).contains("1.2.0");
        assertThat(requests).hasValue(1);
    }

    @Test
    void optInVersionCheckReturnsOnlyAggregateSecurityThroughModelPolicy() throws Exception {
        serve(200, METADATA);
        AtomicInteger osvRequests = new AtomicInteger();
        CveLookupService lookup = new CveLookupService() {
            @Override
            public List<VulnerabilityEntry> lookup(String groupId, String artifactId, String version) {
                assertThat(groupId).isEqualTo("org.example");
                assertThat(artifactId).isEqualTo("artifact");
                assertThat(version).isEqualTo("1.0.0");
                osvRequests.incrementAndGet();
                return List.of(
                        new VulnerabilityEntry("SYNTHETIC_SECRET", "private.user@example.invalid", "HIGH", null, 7.5));
            }
        };
        String privateResult = new DependencyService(
                        new BuildToolProvider(), metadataClient(Duration.ofSeconds(2)), lookup)
                .checkDependencyVersion("org.example", "artifact", "1.0.0", null, null, true);
        String safe = new ModelOutputPolicy().protect("check_dependency_version", privateResult);

        assertThat(requests).hasValue(1);
        assertThat(osvRequests).hasValue(1);
        assertThat(safe)
                .contains(
                        "\"securityStatus\":\"complete\"",
                        "\"cveCount\":1",
                        "\"highestSeverity\":\"HIGH\"",
                        "\"latestVersion\":\"1.2.0\"")
                .doesNotContain("SYNTHETIC_SECRET", "private.user@example.invalid", "org.example", "artifact");
    }

    @Test
    void refusesRedirectWithoutForwardingCoordinates() throws Exception {
        serve(302, "");
        String result = service(Duration.ofSeconds(2))
                .checkDependencyVersion("org.example", "artifact", null, null, null, false);
        assertThat(result).contains("Maven Central metadata request failed").doesNotContain("org.example");
        assertThat(requests).hasValue(1);
        assertThat(redirectHits).hasValue(0);
    }

    @Test
    void rejectsOversizedResponseWithoutEchoingIt() throws Exception {
        serve(200, "PRIVATE_EMAIL@example.invalid" + "x".repeat(MavenMetadataClient.MAX_RESPONSE_BYTES));
        String result = service(Duration.ofSeconds(2))
                .checkDependencyVersion("org.example", "artifact", null, null, null, false);
        assertThat(result)
                .contains("Maven Central metadata request failed")
                .doesNotContain("PRIVATE_EMAIL", "org.example");
        assertThat(requests).hasValue(1);
    }

    @Test
    void requestDeadlineBoundsSlowResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/maven2/org/example/artifact/maven-metadata.xml", exchange -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(450);
                byte[] bytes = METADATA.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        long start = System.nanoTime();
        String result = service(Duration.ofMillis(100))
                .checkDependencyVersion("org.example", "artifact", null, null, null, false);
        assertThat(result).contains("Maven Central metadata request failed");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void rejectsDtdAndExternalEntityWithoutReadingTheFile() throws Exception {
        String canary = "PRIVATE_SECRET";
        String xml = "<!DOCTYPE metadata [<!ENTITY xxe SYSTEM \"file:///tmp/" + canary
                + "\">]><metadata><versioning><latest>&xxe;</latest></versioning></metadata>";
        serve(200, xml);
        String result = service(Duration.ofSeconds(2))
                .checkDependencyVersion("org.example", "artifact", null, null, null, false);
        assertThat(result).contains("Maven Central metadata is invalid").doesNotContain(canary);
        assertThatThrownBy(() -> MavenMetadataParser.parse(xml)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedXmlWithFixedError() throws Exception {
        serve(200, "<metadata><versioning><latest>PRIVATE_EMAIL@example.invalid");
        assertThat(service(Duration.ofSeconds(2))
                        .checkDependencyVersion("org.example", "artifact", null, null, null, false))
                .contains("Maven Central metadata is invalid")
                .doesNotContain("PRIVATE_EMAIL");
    }

    @Test
    void rejectsMalformedUtf8WithFixedError() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/maven2/org/example/artifact/maven-metadata.xml", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = {(byte) 0xc3, (byte) 0x28};
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        assertThat(service(Duration.ofSeconds(2))
                        .checkDependencyVersion("org.example", "artifact", null, null, null, false))
                .contains("Maven Central metadata request failed")
                .doesNotContain("org.example");
    }

    private DependencyService service(Duration timeout) {
        return new DependencyService(new BuildToolProvider(), metadataClient(timeout));
    }

    private MavenMetadataClient metadataClient(Duration timeout) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/maven2/");
        return new MavenMetadataClient(base, client, timeout);
    }

    private void serve(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/maven2/org/example/artifact/maven-metadata.xml", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders()
                    .add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/redirected");
            exchange.sendResponseHeaders(status, status == 302 ? -1 : bytes.length);
            if (status != 302) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/maven2/org/example/good-artifact/maven-metadata.xml", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 302 ? -1 : bytes.length);
            if (status != 302) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/redirected", exchange -> {
            redirectHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }
}
