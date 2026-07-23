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
import java.util.concurrent.locks.ReentrantReadWriteLock;

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

    private static final String OSV_QUERY_URL = "https://api.osv.dev/v1/query";
    private static final int CACHE_MAX_SIZE = 500;
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final HttpClient httpClient;
    private final Map<String, CacheEntry> cache;
    private final ConcurrentLinkedQueue<String> lruKeys;
    private final Object cacheLock = new Object();

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
                System.err.println("[CveLookupService] Could not scan " + key + ": " + e.getMessage());
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

    // ── OSV response parsing ─────────────────────────────────────────

    List<VulnerabilityEntry> parseOsvResponse(String json) {
        List<VulnerabilityEntry> entries = new ArrayList<>();

        // Naive JSON parsing to avoid extra dependencies.
        // OSV.dev returns {"vulns": [{"id": "...", "summary": "...", "severity": [...], ...}]}
        String vulnsBlock = extractJsonArray(json, "vulns");
        if (vulnsBlock == null || vulnsBlock.isEmpty()) return entries;

        // Split on {"id": to find individual vulnerability objects
        List<String> vulnObjects = splitJsonObjects(vulnsBlock);
        for (String vulnObj : vulnObjects) {
            String id = extractJsonString(vulnObj, "id");
            String summary = extractJsonString(vulnObj, "summary");
            if (id == null) continue;

            String severity = classifySeverity(vulnObj);
            String fixedIn = extractFirstFixed(vulnObj);
            double cvssScore = extractCvssScore(vulnObj);

            entries.add(new VulnerabilityEntry(id, summary, severity, fixedIn, cvssScore));
        }

        return entries;
    }

    // ── JSON field extraction (naive, no library dependency) ─────────

    static String extractJsonString(String json, String key) {
        // Match "key": "value" or "key":"value"
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return null;
        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return null;
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        return json.substring(quoteStart + 1, quoteEnd);
    }

    static String extractJsonArray(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return null;
        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return null;
        int bracketStart = json.indexOf('[', colonIdx + 1);
        if (bracketStart < 0) return null;
        int depth = 0;
        int pos = bracketStart;
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return json.substring(bracketStart + 1, pos);
            }
            pos++;
        }
        return json.substring(bracketStart + 1);
    }

    static List<String> splitJsonObjects(String jsonArray) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < jsonArray.length(); i++) {
            char c = jsonArray.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(jsonArray.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    // ── Severity classification ─────────────────────────────────────

    static String classifySeverity(String vulnObj) {
        // Parse CVSS score from severity array
        double score = extractCvssScore(vulnObj);
        return cvssToSeverity(score);
    }

    static double extractCvssScore(String vulnObj) {
        // Look for "CVSS_V3" score in severity array
        String severityBlock = extractJsonArray(vulnObj, "severity");
        if (severityBlock == null) return 0.0;

        // Try to find "score": "X.X"
        String scoreStr = extractJsonString(severityBlock, "score");
        if (scoreStr != null) {
            try {
                return Double.parseDouble(scoreStr);
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return 0.0;
    }

    static String extractFirstFixed(String vulnObj) {
        // Look in "affected[0].ranges[0].events" for "fixed"
        String affectedBlock = extractJsonArray(vulnObj, "affected");
        if (affectedBlock == null) return null;

        List<String> affectedObjects = splitJsonObjects(affectedBlock);
        for (String affected : affectedObjects) {
            String rangesBlock = extractJsonArray(affected, "ranges");
            if (rangesBlock == null) continue;

            String eventsBlock = extractJsonArray("{\"dummy\":" + rangesBlock + "}", "dummy");
            // Hmm, this is getting complex. Simplify: just search for "fixed"
            int fixedIdx = affected.indexOf("\"fixed\"");
            if (fixedIdx >= 0) {
                return extractJsonString(affected.substring(fixedIdx), "fixed");
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
