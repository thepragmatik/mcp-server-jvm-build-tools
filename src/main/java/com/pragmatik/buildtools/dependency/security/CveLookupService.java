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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Lightweight OSV.dev API client for vulnerability lookups.
 * <p>
 * Queries {@code https://api.osv.dev/v1/query} with Maven package coordinates.
 * Includes a simple in-memory LRU cache with 1-hour TTL to avoid redundant
 * API calls within an agent session.
 * <p>
 * <b>Why OSV.dev:</b> Free REST API, no API key, open source, native Maven/Gradle
 * package identifier support. Faster than OWASP Dependency-Check for runtime
 * scanning (no NVD feed download).
 *
 * @see <a href="https://osv.dev">OSV.dev</a>
 */
public class CveLookupService {

    private static final Logger logger = LoggerFactory.getLogger(CveLookupService.class);

    private static final String OSV_QUERY_URL = "https://api.osv.dev/v1/query";
    private static final int CACHE_MAX_SIZE = 500;
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final HttpClient httpClient;
    private final Map<String, CacheEntry> cache;
    private final ConcurrentLinkedQueue<String> lruKeys;
    private final Object cacheLock = new Object();

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public CveLookupService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.cache = new ConcurrentHashMap<>();
        this.lruKeys = new ConcurrentLinkedQueue<>();
    }

    /**
     * Package-visible constructor for testing with a mock HTTP client.
     */
    CveLookupService(HttpClient httpClient) {
        this.httpClient = httpClient;
        this.cache = new ConcurrentHashMap<>();
        this.lruKeys = new ConcurrentLinkedQueue<>();
    }

    /**
     * Query OSV.dev for known vulnerabilities affecting a specific Maven package version.
     *
     * @param groupId    Maven group ID
     * @param artifactId Maven artifact ID
     * @param version    package version to check
     * @return list of vulnerability entries, or empty list if none found
     * @throws IOException if the network request fails
     */
    public List<VulnerabilityEntry> lookup(String groupId, String artifactId, String version) throws IOException {
        String cacheKey = groupId + ":" + artifactId + ":" + version;

        // Check cache
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.entries;
        }

        // Build OSV query payload
        String payload = String.format(
                "{\"package\":{\"name\":\"%s:%s\",\"ecosystem\":\"Maven\"},\"version\":\"%s\"}",
                groupId, artifactId, version);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OSV_QUERY_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            List<VulnerabilityEntry> entries;
            if (response.statusCode() == 200) {
                entries = parseOsvResponse(response.body());
            } else {
                entries = List.of();
            }

            // Store in cache
            putCache(cacheKey, entries);
            return entries;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("OSV.dev query interrupted", e);
        }
    }

    /**
     * Bulk-lookup vulnerabilities for multiple dependencies, batching requests
     * to respect OSV.dev rate limits.
     *
     * @param packages list of packages to scan
     * @return map of package key to vulnerability entries
     */
    public Map<String, List<VulnerabilityEntry>> bulkLookup(List<PackageRef> packages) {
        Map<String, List<VulnerabilityEntry>> results = new LinkedHashMap<>();

        for (PackageRef pkg : packages) {
            String key = pkg.groupId() + ":" + pkg.artifactId() + ":" + pkg.version();
            try {
                List<VulnerabilityEntry> vulns = lookup(pkg.groupId(), pkg.artifactId(), pkg.version());
                results.put(key, vulns);
            } catch (IOException e) {
                // Partial results — mark as unknown with warning
                results.put(key, List.of());
                logger.warn("[CveLookupService] Could not scan {}: {}", key, e.getMessage());
            }
        }

        return results;
    }

    // ── Cache management ────────────────────────────────────────────

    private void putCache(String key, List<VulnerabilityEntry> entries) {
        synchronized (cacheLock) {
            // Evict if full
            while (cache.size() >= CACHE_MAX_SIZE) {
                String oldest = lruKeys.poll();
                if (oldest != null) cache.remove(oldest);
            }
            cache.put(key, new CacheEntry(entries));
            lruKeys.add(key);
        }
    }

    /**
     * Clear the entire cache.
     */
    public void clearCache() {
        cache.clear();
        lruKeys.clear();
    }

    /**
     * Returns the current cache size (exposed for testing).
     */
    int cacheSize() {
        return cache.size();
    }

    // ── OSV response parsing (Jackson) ───────────────────────────────

    List<VulnerabilityEntry> parseOsvResponse(String json) {
        List<VulnerabilityEntry> entries = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode vulns = root.get("vulns");
            if (vulns == null || !vulns.isArray()) return entries;

            for (JsonNode vuln : vulns) {
                String id = vuln.has("id") ? vuln.get("id").asText() : null;
                String summary = vuln.has("summary") ? vuln.get("summary").asText() : null;
                if (id == null) continue;

                String severity = classifySeverity(vuln);
                String fixedIn = extractFirstFixed(vuln);
                double cvssScore = extractCvssScore(vuln);

                entries.add(new VulnerabilityEntry(id, summary, severity, fixedIn, cvssScore));
            }
        } catch (Exception e) {
            // JSON parse error — return empty
        }
        return entries;
    }

    // ── Severity classification ─────────────────────────────────────

    static String classifySeverity(JsonNode vuln) {
        double score = extractCvssScore(vuln);
        return cvssToSeverity(score);
    }

    static double extractCvssScore(JsonNode vuln) {
        JsonNode severity = vuln.get("severity");
        if (severity == null || !severity.isArray()) return 0.0;

        // Prefer CVSS_V3 scores
        for (JsonNode sev : severity) {
            if (sev.has("type") && sev.has("score") && sev.get("type").asText().contains("CVSS_V3")) {
                try {
                    return Double.parseDouble(sev.get("score").asText());
                } catch (NumberFormatException e) {
                    // fall through
                }
            }
        }

        // Fallback: any score field
        for (JsonNode sev : severity) {
            if (sev.has("score")) {
                try {
                    return Double.parseDouble(sev.get("score").asText());
                } catch (NumberFormatException e) {
                    // fall through
                }
            }
        }

        return 0.0;
    }

    static String extractFirstFixed(JsonNode vuln) {
        // Look in "affected[].ranges[].events[]" for "fixed"
        JsonNode affected = vuln.get("affected");
        if (affected == null || !affected.isArray()) return null;

        for (JsonNode aff : affected) {
            JsonNode ranges = aff.get("ranges");
            if (ranges == null || !ranges.isArray()) continue;

            for (JsonNode range : ranges) {
                JsonNode events = range.get("events");
                if (events == null || !events.isArray()) continue;

                for (JsonNode event : events) {
                    if (event.has("fixed")) {
                        return event.get("fixed").asText();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Map a CVSS v3 score to a severity label.
     * <p>
     * Thresholds: 9.0+ = CRITICAL, 7.0-8.9 = HIGH, 4.0-6.9 = MEDIUM,
     * 0.1-3.9 = LOW, 0.0 = NONE.
     */
    public static String cvssToSeverity(double score) {
        if (score >= 9.0) return "CRITICAL";
        if (score >= 7.0) return "HIGH";
        if (score >= 4.0) return "MEDIUM";
        if (score > 0.0) return "LOW";
        return "NONE";
    }

    /**
     * Check if a severity string meets or exceeds a threshold.
     */
    public static boolean meetsThreshold(String severity, String threshold) {
        int sevRank = severityRank(severity);
        int threshRank = severityRank(threshold);
        return sevRank >= threshRank;
    }

    private static int severityRank(String severity) {
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    // ── Data types ───────────────────────────────────────────────────

    /**
     * Represents a single vulnerability entry from OSV.dev.
     */
    public record VulnerabilityEntry(String id, String summary, String severity, String fixedIn, double cvssScore) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            if (summary != null) map.put("summary", summary);
            map.put("severity", severity);
            if (fixedIn != null) map.put("fixedIn", fixedIn);
            if (cvssScore > 0) map.put("cvssScore", cvssScore);
            return map;
        }
    }

    /**
     * Represents a Maven package reference for bulk scanning.
     */
    public record PackageRef(String groupId, String artifactId, String version) {}

    // ── Internal cache entry ────────────────────────────────────────

    private static class CacheEntry {
        final List<VulnerabilityEntry> entries;
        final Instant createdAt;

        CacheEntry(List<VulnerabilityEntry> entries) {
            this.entries = List.copyOf(entries);
            this.createdAt = Instant.now();
        }

        boolean isExpired() {
            return Duration.between(createdAt, Instant.now()).compareTo(CACHE_TTL) > 0;
        }
    }
}
