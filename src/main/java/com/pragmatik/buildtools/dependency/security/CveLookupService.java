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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
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
    private static final String OSV_BATCH_URL = "https://api.osv.dev/v1/querybatch";
    private static final int CACHE_MAX_SIZE = 500;
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final Pattern PACKAGE_PART = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");
    private static final Pattern VERSION_PART = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+~()-]*");
    private static final int MAX_GROUP_ID_LENGTH = 256;
    private static final int MAX_ARTIFACT_ID_LENGTH = 128;
    private static final int MAX_VERSION_LENGTH = 128;
    private static final Set<String> DYNAMIC_VERSIONS =
            Set.of("LATEST", "RELEASE", "latest.release", "latest.integration");
    public static final int MAX_SCAN_PACKAGES = 500;
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient DEFAULT_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final OsvTransport transport;
    private final Map<String, CacheEntry> cache;
    private final ConcurrentLinkedQueue<String> lruKeys;
    private final Object cacheLock = new Object();

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public CveLookupService() {
        // A redirect must not forward dependency coordinates to another host.
        this(DEFAULT_HTTP_CLIENT);
    }

    /**
     * Package-visible constructor for testing with a mock HTTP client.
     */
    CveLookupService(HttpClient httpClient) {
        this((uri, payload) -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            CompletableFuture<HttpResponse<byte[]>> exchange =
                    httpClient.sendAsync(request, info -> new BoundedBodySubscriber());
            try {
                HttpResponse<byte[]> response = exchange.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                return new OsvResponse(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
            } catch (TimeoutException e) {
                exchange.cancel(true);
                throw new IOException("OSV query timed out", e);
            } catch (ExecutionException e) {
                throw new IOException("OSV query failed", e);
            } catch (InterruptedException e) {
                exchange.cancel(true);
                throw e;
            }
        });
    }

    CveLookupService(OsvTransport transport) {
        this.transport = Objects.requireNonNull(transport);
        this.cache = new ConcurrentHashMap<>();
        this.lruKeys = new ConcurrentLinkedQueue<>();
    }

    @FunctionalInterface
    interface OsvTransport {
        OsvResponse post(URI uri, String payload) throws IOException, InterruptedException;
    }

    record OsvResponse(int statusCode, String body) {}

    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("OSV response exceeds size limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            body.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }

    private record OsvPackage(String name, String ecosystem) {}

    private record OsvQuery(OsvPackage pkg, String version) {
        // OSV uses the JSON key "package", which is reserved in Java.
        @com.fasterxml.jackson.annotation.JsonProperty("package")
        public OsvPackage pkg() {
            return pkg;
        }
    }

    private record OsvBatch(List<OsvQuery> queries) {}

    private static OsvQuery query(PackageRef pkg) {
        return new OsvQuery(new OsvPackage(pkg.groupId() + ":" + pkg.artifactId(), "Maven"), pkg.version());
    }

    private static boolean valid(PackageRef pkg) {
        return pkg != null
                && matches(pkg.groupId(), PACKAGE_PART, MAX_GROUP_ID_LENGTH)
                && matches(pkg.artifactId(), PACKAGE_PART, MAX_ARTIFACT_ID_LENGTH)
                && matches(pkg.version(), VERSION_PART, MAX_VERSION_LENGTH);
    }

    /** Whether a literal Maven coordinate can be sent to OSV without transformation. */
    public static boolean supports(PackageRef pkg) {
        return valid(pkg) && !pkg.version().endsWith("+") && !DYNAMIC_VERSIONS.contains(pkg.version());
    }

    private static boolean matches(String value, Pattern pattern, int maxLength) {
        return value != null
                && value.length() <= maxLength
                && pattern.matcher(value).matches();
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
        PackageRef pkg = new PackageRef(groupId, artifactId, version);
        if (!supports(pkg)) {
            throw new IOException("Invalid package coordinates for OSV query");
        }
        String cacheKey = groupId + ":" + artifactId + ":" + version;

        // Check cache
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.entries;
        }

        // Build OSV query payload
        String payload = objectMapper.writeValueAsString(query(pkg));

        try {
            OsvResponse response = transport.post(URI.create(OSV_QUERY_URL), payload);

            if (response.statusCode() != 200 || !bounded(response)) {
                throw new IOException("OSV query incomplete");
            }
            List<VulnerabilityEntry> entries;
            try {
                entries = parseOsvResponse(response.body());
            } catch (RuntimeException e) {
                throw new IOException("OSV query response invalid", e);
            }

            // Store in cache
            putCache(cacheKey, entries);
            return entries;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("OSV.dev query interrupted", e);
        }
    }

    static final int OSV_BATCH_SIZE = 100;

    private static boolean bounded(OsvResponse response) {
        return response.body() != null && response.body().getBytes(StandardCharsets.UTF_8).length <= MAX_RESPONSE_BYTES;
    }

    /**
     * Bulk-lookup vulnerabilities for multiple dependencies using the OSV.dev
     * {@code /v1/querybatch} endpoint: packages are sent in chunks of
     * {@value #OSV_BATCH_SIZE} (OSV.dev's documented batch limit) so a scan of
     * N dependencies costs ceil(N/100) HTTP round-trips instead of N sequential
     * ones. Only verified, complete results are returned or cached. A missing
     * package key means its lookup did not complete; callers must not treat it
     * as a clean result. Failed batches are not retried sequentially.
     *
     * @param packages list of packages to scan
     * @return map of package key to vulnerability entries
     */
    public Map<String, List<VulnerabilityEntry>> bulkLookup(List<PackageRef> packages) {
        Map<String, List<VulnerabilityEntry>> results = new LinkedHashMap<>();
        if (packages == null || packages.size() > MAX_SCAN_PACKAGES) {
            return results;
        }

        // Serve cache hits first; collect the rest for batching
        List<PackageRef> pending = new ArrayList<>();
        for (PackageRef pkg : packages) {
            if (!supports(pkg)) {
                // A malformed coordinate must never reach the outbound transport or logs.
                continue;
            }
            String key = pkg.groupId() + ":" + pkg.artifactId() + ":" + pkg.version();
            CacheEntry cached = cache.get(key);
            if (cached != null && !cached.isExpired()) {
                results.put(key, cached.entries);
            } else {
                pending.add(pkg);
            }
        }

        // Batch pending packages in chunks of OSV_BATCH_SIZE
        for (int i = 0; i < pending.size(); i += OSV_BATCH_SIZE) {
            List<PackageRef> batch = pending.subList(i, Math.min(i + OSV_BATCH_SIZE, pending.size()));
            flushBatch(batch, results);
        }
        return results;
    }

    private void flushBatch(List<PackageRef> batch, Map<String, List<VulnerabilityEntry>> results) {
        if (batch.isEmpty()) return;

        String payload = objectMapper.writeValueAsString(
                new OsvBatch(batch.stream().map(CveLookupService::query).toList()));

        try {
            OsvResponse response = transport.post(URI.create(OSV_BATCH_URL), payload);

            if (response.statusCode() != 200 || !bounded(response)) {
                logger.warn("[CveLookupService] OSV batch query incomplete");
                return;
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (root == null || !root.isObject() || root.size() != 1 || root.has("next_page_token")) {
                logger.warn("[CveLookupService] OSV batch response shape mismatch");
                return;
            }
            JsonNode resArr = root.get("results");
            if (resArr == null || !resArr.isArray() || resArr.size() != batch.size()) {
                logger.warn("[CveLookupService] OSV batch response shape mismatch");
                return;
            }

            Map<String, List<VulnerabilityEntry>> completeBatch = new LinkedHashMap<>();
            for (int i = 0; i < batch.size(); i++) {
                PackageRef pkg = batch.get(i);
                String key = pkg.groupId() + ":" + pkg.artifactId() + ":" + pkg.version();
                JsonNode node = resArr.get(i);
                if (node == null || !node.isObject() || node.has("next_page_token")) {
                    logger.warn("[CveLookupService] OSV batch response incomplete");
                    return;
                }
                JsonNode vulns = node.get("vulns");
                if ((vulns != null && !vulns.isArray()) || node.size() != (vulns == null ? 0 : 1)) {
                    logger.warn("[CveLookupService] OSV batch response shape mismatch");
                    return;
                }
                List<VulnerabilityEntry> entries = parseVulnsArray(vulns);
                completeBatch.put(key, entries);
            }
            for (Map.Entry<String, List<VulnerabilityEntry>> entry : completeBatch.entrySet()) {
                String key = entry.getKey();
                List<VulnerabilityEntry> entries = entry.getValue();
                putCache(key, entries);
                results.put(key, entries);
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            logger.warn("[CveLookupService] OSV batch query incomplete");
        }
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
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject() || root.has("next_page_token")) {
                throw new IllegalArgumentException("OSV response incomplete");
            }
            JsonNode vulns = root.get("vulns");
            if ((vulns != null && !vulns.isArray()) || root.size() != (vulns == null ? 0 : 1)) {
                throw new IllegalArgumentException("OSV response shape invalid");
            }
            return parseVulnsArray(vulns);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("OSV response shape invalid", e);
        }
    }

    private List<VulnerabilityEntry> parseVulnsArray(JsonNode vulns) {
        List<VulnerabilityEntry> entries = new ArrayList<>();
        if (vulns == null) return entries;
        if (!vulns.isArray()) throw new IllegalArgumentException("OSV vulnerabilities invalid");
        for (JsonNode vuln : vulns) {
            if (vuln == null || !vuln.isObject()) {
                throw new IllegalArgumentException("OSV vulnerability invalid");
            }
            String id = vuln.has("id") ? vuln.get("id").asText() : null;
            String summary = vuln.has("summary") ? vuln.get("summary").asText() : null;
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("OSV vulnerability identifier missing");
            }

            String severity = classifySeverity(vuln);
            String fixedIn = extractFirstFixed(vuln);
            double cvssScore = extractCvssScore(vuln);

            entries.add(new VulnerabilityEntry(id, summary, severity, fixedIn, cvssScore));
        }
        return entries;
    }

    // ── Severity classification ─────────────────────────────────────

    static String classifySeverity(JsonNode vuln) {
        var score = extractCvssBaseScore(vuln);
        return score.isPresent() ? cvssToSeverity(score.getAsDouble()) : "UNKNOWN";
    }

    static double extractCvssScore(JsonNode vuln) {
        return extractCvssBaseScore(vuln).orElse(0.0);
    }

    private static java.util.OptionalDouble extractCvssBaseScore(JsonNode vuln) {
        JsonNode severity = vuln.get("severity");
        if (severity == null || !severity.isArray()) return java.util.OptionalDouble.empty();

        // The OSV schema stores a CVSS vector in score. Numeric scores and other
        // schemes are unsupported; accepting them would invent a severity.
        double highest = -1;
        for (JsonNode sev : severity) {
            if (sev.isObject()
                    && sev.path("type").isTextual()
                    && "CVSS_V3".equals(sev.path("type").asText())
                    && sev.path("score").isTextual()) {
                var score = CvssV31.baseScore(sev.path("score").asText());
                if (score.isPresent()) highest = Math.max(highest, score.getAsDouble());
            }
        }
        return highest < 0 ? java.util.OptionalDouble.empty() : java.util.OptionalDouble.of(highest);
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
