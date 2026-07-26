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

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
            String json =
                    """
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
            String json =
                    """
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
            assertThat(entries.get(0).severity()).isEqualTo("NONE");
            assertThat(entries.get(0).cvssScore()).isEqualTo(0.0);
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
}
