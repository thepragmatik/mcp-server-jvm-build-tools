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
package com.pragmatik.buildtools.dependency.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link CveLookupService}.
 */
@DisplayName("CveLookupService unit tests")
class CveLookupServiceTest {

    private final CveLookupService service = new CveLookupService();

    // ── CVSS severity mapping ───────────────────────────────────────

    @Nested
    @DisplayName("CVSS severity mapping")
    class CvssSeverityMapping {

        @Test
        @DisplayName("score >= 9.0 is CRITICAL")
        void criticalScore() {
            assertThat(CveLookupService.cvssToSeverity(10.0)).isEqualTo("CRITICAL");
            assertThat(CveLookupService.cvssToSeverity(9.8)).isEqualTo("CRITICAL");
            assertThat(CveLookupService.cvssToSeverity(9.0)).isEqualTo("CRITICAL");
        }

        @Test
        @DisplayName("score 7.0–8.9 is HIGH")
        void highScore() {
            assertThat(CveLookupService.cvssToSeverity(8.9)).isEqualTo("HIGH");
            assertThat(CveLookupService.cvssToSeverity(7.5)).isEqualTo("HIGH");
            assertThat(CveLookupService.cvssToSeverity(7.0)).isEqualTo("HIGH");
        }

        @Test
        @DisplayName("score 4.0–6.9 is MEDIUM")
        void mediumScore() {
            assertThat(CveLookupService.cvssToSeverity(6.9)).isEqualTo("MEDIUM");
            assertThat(CveLookupService.cvssToSeverity(5.0)).isEqualTo("MEDIUM");
            assertThat(CveLookupService.cvssToSeverity(4.0)).isEqualTo("MEDIUM");
        }

        @Test
        @DisplayName("score 0.1–3.9 is LOW")
        void lowScore() {
            assertThat(CveLookupService.cvssToSeverity(3.9)).isEqualTo("LOW");
            assertThat(CveLookupService.cvssToSeverity(0.1)).isEqualTo("LOW");
        }

        @Test
        @DisplayName("score 0.0 is NONE")
        void noneScore() {
            assertThat(CveLookupService.cvssToSeverity(0.0)).isEqualTo("NONE");
        }
    }

    // ── Severity threshold comparison ───────────────────────────────

    @Nested
    @DisplayName("Severity threshold comparison")
    class SeverityThreshold {

        @Test
        @DisplayName("CRITICAL meets CRITICAL threshold")
        void criticalMeetsCritical() {
            assertThat(CveLookupService.meetsThreshold("CRITICAL", "CRITICAL")).isTrue();
        }

        @Test
        @DisplayName("CRITICAL meets HIGH threshold")
        void criticalMeetsHigh() {
            assertThat(CveLookupService.meetsThreshold("CRITICAL", "HIGH")).isTrue();
        }

        @Test
        @DisplayName("HIGH does not meet CRITICAL threshold")
        void highDoesNotMeetCritical() {
            assertThat(CveLookupService.meetsThreshold("HIGH", "CRITICAL")).isFalse();
        }

        @Test
        @DisplayName("MEDIUM meets MEDIUM threshold")
        void mediumMeetsMedium() {
            assertThat(CveLookupService.meetsThreshold("MEDIUM", "MEDIUM")).isTrue();
        }

        @Test
        @DisplayName("MEDIUM does not meet HIGH threshold")
        void mediumDoesNotMeetHigh() {
            assertThat(CveLookupService.meetsThreshold("MEDIUM", "HIGH")).isFalse();
        }

        @Test
        @DisplayName("LOW meets all thresholds including LOW")
        void lowMeetsLow() {
            assertThat(CveLookupService.meetsThreshold("LOW", "LOW")).isTrue();
            assertThat(CveLookupService.meetsThreshold("LOW", "MEDIUM")).isFalse();
        }
    }

    // ── OSV JSON parsing ────────────────────────────────────────────

    @Nested
    @DisplayName("OSV JSON response parsing")
    class OsvJsonParsing {

        @Test
        @DisplayName("parseOsvResponse extracts vulnerability IDs")
        void extractsVulnerabilityIds() {
            String json = """
                    {"vulns":[{"id":"CVE-2024-1234","summary":"Test vuln","severity":[{"type":"CVSS_V3","score":"9.8"}],"affected":[{"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"1.0.0"},{"fixed":"1.2.0"}]}]}]}]}""";

            List<CveLookupService.VulnerabilityEntry> entries = service.parseOsvResponse(json);

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).id()).isEqualTo("CVE-2024-1234");
            assertThat(entries.get(0).summary()).isEqualTo("Test vuln");
            assertThat(entries.get(0).severity()).isEqualTo("CRITICAL");
            assertThat(entries.get(0).cvssScore()).isEqualTo(9.8);
        }

        @Test
        @DisplayName("parseOsvResponse returns empty list for no vulns")
        void emptyForNoVulns() {
            String json = "{\"vulns\":[]}";
            List<CveLookupService.VulnerabilityEntry> entries = service.parseOsvResponse(json);
            assertThat(entries).isEmpty();
        }

        @Test
        @DisplayName("parseOsvResponse returns empty list for null/missing vulns key")
        void emptyForMissingVulns() {
            String json = "{\"other\":\"data\"}";
            List<CveLookupService.VulnerabilityEntry> entries = service.parseOsvResponse(json);
            assertThat(entries).isEmpty();
        }

        @Test
        @DisplayName("parseOsvResponse handles multiple vulnerabilities")
        void handlesMultipleVulnerabilities() {
            String json = """
                    {"vulns":[{"id":"CVE-2024-AAAA","summary":"A","severity":[{"type":"CVSS_V3","score":"7.5"}]},{"id":"CVE-2024-BBBB","summary":"B","severity":[{"type":"CVSS_V3","score":"5.0"}]}]}""";

            List<CveLookupService.VulnerabilityEntry> entries = service.parseOsvResponse(json);

            assertThat(entries).hasSize(2);
            assertThat(entries.get(0).id()).isEqualTo("CVE-2024-AAAA");
            assertThat(entries.get(0).severity()).isEqualTo("HIGH");
            assertThat(entries.get(1).id()).isEqualTo("CVE-2024-BBBB");
        }

        @Test
        @DisplayName("parseOsvResponse handles vuln without CVSS score")
        void handlesVulnWithoutCvss() {
            String json = """
                    {"vulns":[{"id":"CVE-2024-NOSCORE","summary":"No score"}]}""";

            List<CveLookupService.VulnerabilityEntry> entries = service.parseOsvResponse(json);

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).severity()).isEqualTo("UNKNOWN");
            assertThat(entries.get(0).cvssScore()).isEqualTo(0.0);
        }

        @Test
        void cvssVectorIsUnknownUntilValidatedScoringExists() {
            var entries = service.parseOsvResponse("""
                    {"vulns":[{"id":"OSV-2026-1","severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"}]}]}""");
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).severity()).isEqualTo("UNKNOWN");
        }
    }

    // ── VulnerabilityEntry ──────────────────────────────────────────

    @Nested
    @DisplayName("VulnerabilityEntry")
    class VulnerabilityEntryTests {

        @Test
        @DisplayName("toMap serializes correctly")
        void toMapSerializesCorrectly() {
            CveLookupService.VulnerabilityEntry entry =
                    new CveLookupService.VulnerabilityEntry("CVE-2024-1", "Test", "HIGH", "2.0.0", 7.5);

            var map = entry.toMap();
            assertThat(map).containsEntry("id", "CVE-2024-1");
            assertThat(map).containsEntry("summary", "Test");
            assertThat(map).containsEntry("severity", "HIGH");
            assertThat(map).containsEntry("fixedIn", "2.0.0");
            assertThat(map).containsEntry("cvssScore", 7.5);
        }

        @Test
        @DisplayName("toMap omits optional fields when null")
        void toMapOmitsOptionalFields() {
            CveLookupService.VulnerabilityEntry entry =
                    new CveLookupService.VulnerabilityEntry("CVE-2024-1", null, "MEDIUM", null, 0.0);

            var map = entry.toMap();
            assertThat(map).containsEntry("id", "CVE-2024-1");
            assertThat(map).doesNotContainKey("summary");
            assertThat(map).doesNotContainKey("fixedIn");
            assertThat(map).doesNotContainKey("cvssScore");
        }
    }

    // ── OSV batch chunking ──────────────────────────────────────────

    @Nested
    @DisplayName("OSV batch chunking")
    class BatchChunking {

        @Test
        @DisplayName("batch size constant respects OSV documented limit")
        void batchSize() {
            assertThat(CveLookupService.OSV_BATCH_SIZE).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("OSV outbound payload boundary")
    class OutboundPayload {

        private final ObjectMapper json = new ObjectMapper();

        @Test
        void singleQuerySerializesOnlyMavenCoordinates() throws Exception {
            List<String> payloads = new ArrayList<>();
            CveLookupService client = new CveLookupService((uri, payload) -> {
                assertThat(uri).isEqualTo(URI.create("https://api.osv.dev/v1/query"));
                payloads.add(payload);
                return new CveLookupService.OsvResponse(200, "{\"vulns\":[]}");
            });

            assertThat(client.lookup("org.example", "example-core", "1.2.3-RC1"))
                    .isEmpty();
            assertThat(payloads).hasSize(1);
            var request = json.readTree(payloads.get(0));
            assertThat(request.get("package").get("name").asText()).isEqualTo("org.example:example-core");
            assertThat(request.get("package").get("ecosystem").asText()).isEqualTo("Maven");
            assertThat(request.get("version").asText()).isEqualTo("1.2.3-RC1");
            assertThat(request.size()).isEqualTo(2);
        }

        @Test
        void malformedOrOversizedSingleCoordinatesNeverReachTransport() {
            AtomicInteger requests = new AtomicInteger();
            CveLookupService client = new CveLookupService((uri, payload) -> {
                requests.incrementAndGet();
                return new CveLookupService.OsvResponse(200, "{}");
            });
            List<CveLookupService.PackageRef> rejected = List.of(
                    new CveLookupService.PackageRef("org.example\"},\"secret\":\"canary", "core", "1.0"),
                    new CveLookupService.PackageRef("org.example", "core\nPRIVATE_EMAIL@example.com", "1.0"),
                    new CveLookupService.PackageRef("org.example", "core", "1.0\u0000PRIVATE_SECRET"),
                    new CveLookupService.PackageRef("org.example", "core", "x".repeat(129)),
                    new CveLookupService.PackageRef("org.example", "x".repeat(129), "1.0"),
                    new CveLookupService.PackageRef("x".repeat(257), "core", "1.0"));

            for (var pkg : rejected) {
                assertThatThrownBy(() -> client.lookup(pkg.groupId(), pkg.artifactId(), pkg.version()))
                        .isInstanceOf(IOException.class)
                        .hasMessage("Invalid package coordinates for OSV query");
            }
            assertThat(requests).hasValue(0);
        }

        @Test
        void failedBatchDoesNotRetryOrTreatInvalidCoordinatesAsClean() {
            List<String> payloads = new ArrayList<>();
            CveLookupService client = new CveLookupService((uri, payload) -> {
                payloads.add(payload);
                return uri.getPath().endsWith("querybatch")
                        ? new CveLookupService.OsvResponse(503, "")
                        : new CveLookupService.OsvResponse(200, "{\"vulns\":[]}");
            });

            var results = client.bulkLookup(List.of(
                    new CveLookupService.PackageRef("com.acme", "safe-lib", "2.0+build"),
                    new CveLookupService.PackageRef("com.acme", "bad\"},\"email\":\"PRIVATE_EMAIL@example.com", "1"),
                    new CveLookupService.PackageRef("com.acme", "lib", "PRIVATE_SECRET\n1")));

            assertThat(results).isEmpty();
            assertThat(payloads).hasSize(1);
            var batch = json.readTree(payloads.get(0));
            assertThat(batch.get("queries")).hasSize(1);
            assertThat(batch.get("queries").get(0).get("package").get("name").asText())
                    .isEqualTo("com.acme:safe-lib");
            assertThat(payloads.get(0)).doesNotContain("PRIVATE_EMAIL", "PRIVATE_SECRET");
        }
    }

    @Nested
    class LookupCompleteness {
        private final CveLookupService.PackageRef pkg =
                new CveLookupService.PackageRef("org.example", "library", "1.0");

        @Test
        void officialSparseBatchRetainsVulnerabilityPresenceWithUnknownSeverity() {
            AtomicInteger requests = new AtomicInteger();
            CveLookupService client = new CveLookupService((uri, payload) -> {
                requests.incrementAndGet();
                return new CveLookupService.OsvResponse(
                        200,
                        "{\"results\":[{\"vulns\":[{\"id\":\"OSV-2026-1\",\"modified\":\"2026-01-01T00:00:00Z\"}]}]}");
            });
            var first = client.bulkLookup(List.of(pkg));
            assertThat(first.get("org.example:library:1.0")).hasSize(1);
            assertThat(first.get("org.example:library:1.0").get(0).severity()).isEqualTo("UNKNOWN");
            assertThat(client.bulkLookup(List.of(pkg))).isEqualTo(first);
            assertThat(requests).hasValue(1);
        }

        @Test
        void emptyBatchIsVerifiedClean() {
            CveLookupService client =
                    new CveLookupService((uri, payload) -> new CveLookupService.OsvResponse(200, "{\"results\":[{}]}"));
            assertThat(client.bulkLookup(List.of(pkg))).containsEntry("org.example:library:1.0", List.of());
        }

        @Test
        void failuresNeverBecomeCleanOrCached() {
            List<CveLookupService.OsvResponse> responses = List.of(
                    new CveLookupService.OsvResponse(500, "{}"),
                    new CveLookupService.OsvResponse(200, "not-json"),
                    new CveLookupService.OsvResponse(200, "x".repeat(CveLookupService.MAX_RESPONSE_BYTES + 1)),
                    new CveLookupService.OsvResponse(200, "{\"results\":[{\"vulns\":\"bad\"}]}"),
                    new CveLookupService.OsvResponse(
                            200, "{\"results\":[{\"vulns\":[],\"next_page_token\":\"more\"}]}"));
            for (var response : responses) {
                CveLookupService client = new CveLookupService((uri, payload) -> response);
                assertThat(client.bulkLookup(List.of(pkg))).isEmpty();
                assertThat(client.cacheSize()).isZero();
            }
            CveLookupService timedOut = new CveLookupService((uri, payload) -> {
                throw new java.net.http.HttpTimeoutException("synthetic timeout");
            });
            assertThat(timedOut.bulkLookup(List.of(pkg))).isEmpty();
            assertThat(timedOut.cacheSize()).isZero();
        }

        @Test
        void singleQueryFailuresThrowWithoutCaching() {
            for (var response : List.of(
                    new CveLookupService.OsvResponse(500, "{}"),
                    new CveLookupService.OsvResponse(200, "not-json"),
                    new CveLookupService.OsvResponse(200, "{\"next_page_token\":\"more\"}"),
                    new CveLookupService.OsvResponse(200, "x".repeat(CveLookupService.MAX_RESPONSE_BYTES + 1)))) {
                CveLookupService client = new CveLookupService((uri, payload) -> response);
                assertThatThrownBy(() -> client.lookup(pkg.groupId(), pkg.artifactId(), pkg.version()))
                        .isInstanceOf(IOException.class);
                assertThat(client.cacheSize()).isZero();
            }
        }

        @Test
        void packageCapFailsWithoutEgress() {
            AtomicInteger requests = new AtomicInteger();
            CveLookupService client = new CveLookupService((uri, payload) -> {
                requests.incrementAndGet();
                return new CveLookupService.OsvResponse(200, "{}");
            });
            assertThat(client.bulkLookup(java.util.Collections.nCopies(CveLookupService.MAX_SCAN_PACKAGES + 1, pkg)))
                    .isEmpty();
            assertThat(requests).hasValue(0);
        }

        @Test
        void responseSubscriberCancelsBeforeRetainingOversizedBody() {
            CveLookupService.BoundedBodySubscriber subscriber = new CveLookupService.BoundedBodySubscriber();
            AtomicInteger cancels = new AtomicInteger();
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {}

                @Override
                public void cancel() {
                    cancels.incrementAndGet();
                }
            });
            subscriber.onNext(List.of(ByteBuffer.wrap(new byte[CveLookupService.MAX_RESPONSE_BYTES + 1])));
            assertThat(cancels).hasValue(1);
            assertThat(subscriber.getBody().toCompletableFuture()).isCompletedExceptionally();
        }
    }
}
