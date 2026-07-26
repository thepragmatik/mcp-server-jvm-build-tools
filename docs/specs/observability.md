# Observability — Metrics Collection Design Spec

**Date:** 2026-07-26  
**Target Release:** v1.2.0  
**Status:** Draft  
**Priority:** P1 (HIGH value, LOW effort)  
**Author:** Architecture profile (Phase 1 research), mcp-server-jvm-build-tools

---

## 1. Overview / Motivation

The server currently has **domain-specific instrumentation** in several services but
**no unified metrics collection**:

- `BuildPerformanceService` — build profiling (per-build timing, phase breakdown, history).
- `BuildCacheService` — cache health analysis (configuration scoring).
- `ToolAuthorizationService` — audit logging for tool invocations.

What's missing:
- **System-wide metrics** — tool call counts, error rates, request latencies, active
  connections — the standard SRE/DevOps signals that operators need to monitor health.
- **Prometheus exposition** — an endpoint that Prometheus can scrape for alerting and
  dashboards.
- **Cross-cutting instrumentation** — metrics that span all services without coupling
  them to Micrometer directly.

Micrometer is the de-facto standard for JVM observability. Because the server already
uses **Spring Boot 4.1.0** (which bundles Micrometer and Actuator), adding Prometheus
metrics requires minimal effort — primarily configuration and annotation.

### Key Design Goals

1. **Add Micrometer metrics** — counters, timers, and gauges for tool calls, build
   operations, cache hits, errors, and latency.
2. **Expose via Spring Boot Actuator** — `/actuator/prometheus` endpoint for Prometheus
   scraping, `/actuator/metrics` for ad-hoc inspection.
3. **Zero-coupling instrumentation** — use Micrometer annotations (`@Timed`, `@Counted`)
   and `MeterBinder` beans to avoid hard dependencies in service code.
4. **Label strategy** — rich enough for dashboards, safe enough to avoid PII/cardinality
   explosion.
5. **Integrate with existing services** — wire into `BuildPerformanceService`,
   `BuildCacheService`, `BuildToolsService`, `ToolAuthorizationService` without
   invasive refactoring.

---

## 2. Design Decisions

### Decision 1: Micrometer + Actuator + Prometheus

**Chosen: Micrometer core + Micrometer registry Prometheus via Actuator.**

Spring Boot 4.1.0 already includes:
- `micrometer-core` (meter registry, `@Timed`, `@Counted` AOP).
- `spring-boot-starter-actuator` (actuator endpoints).

We add:
- `micrometer-registry-prometheus` (Prometheus format serialization).

| Component | Purpose |
|-----------|---------|
| `MeterRegistry` (auto-configured) | Central meter registry |
| `@Timed` on service methods | Record duration + count per tool |
| `MeterBinder` beans | JVM, system, and custom metrics not tied to a single service |
| `PrometheusMeterRegistry` | Format metrics for Prometheus scrape |
| `/actuator/prometheus` | Prometheus-compatible exposition endpoint |

This is the **lowest-effort, highest-compatibility** approach. No new dependencies
beyond the Prometheus registry jar. No changes to the application's request flow.

### Decision 2: What metrics to collect

| Metric | Type | Tags | Source |
|--------|------|------|--------|
| `buildtools.tool.calls` | Counter | `tool_name`, `tool_service`, `status` (success/error) | Per-tool method via `@Timed` |
| `buildtools.tool.duration` | Timer | `tool_name`, `tool_service` | Per-tool method via `@Timed` |
| `buildtools.build.duration` | Timer | `tool` (maven/gradle/sbt), `command`, `success` | `BuildToolsService.executeBuildCommand` |
| `buildtools.build.total` | Counter | `tool`, `command`, `result` (success/failure) | `BuildToolsService` |
| `buildtools.build.errors` | Counter | `tool`, `error_type` | Output parser |
| `buildtools.cache.hit.rate` | Gauge | `tool` | `BuildCacheService` scoring |
| `buildtools.cache.score` | Gauge | `tool`, `category` | `BuildCacheService` |
| `buildtools.auth.requests` | Counter | `auth_type` (api_key/jwt/none), `result` (allowed/denied) | `OAuthResourceServerFilter` + `ToolAuthorizationService` |
| `buildtools.async.tasks` | Gauge | `status` (running/queued/completed) | `AsyncBuildService` (task queue) |
| `buildtools.tools.registered` | Gauge | — | Startup registration count |
| `jvm.*` | Various | — | Micrometer's built-in JVM metrics |
| `process.*` | Various | — | Micrometer's built-in process metrics |

### Decision 3: Instrumentation approach — annotations + MeterBinder

**Chosen: Hybrid approach.**

1. **`@Timed` annotations** on existing `@Tool` methods for tool-call metrics.
   - Requires `TimedAspect` bean registration.
   - Each `@Tool` method gets `@Timed(value = "buildtools.tool.calls",
     extraTags = {"tool_service", "BuildToolsService"})`.
2. **MeterBinder beans** for custom metrics that span multiple services or need
   programmatic construction (gauges for cache hit rates, async task counts).
3. **Manual MeterRegistry injection** only where `@Timed` doesn't suffice:
   `BuildCacheService` (Gauge for cache score), `AsyncBuildService` (Gauge for
   queue depth).

**No Micrometer imports in service code except where MeterBinder is used.** The
`@Timed` annotation is a Micrometer annotation, but it's purely declarative — the
aspect bean does the actual work.

### Decision 4: Endpoint exposure — Actuator Prometheus

**Chosen: Spring Boot Actuator's `/actuator/prometheus` endpoint.**

```properties
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.prometheus.enabled=true
management.endpoint.metrics.enabled=true
```

This is consistent with the existing health endpoints (`/health`, `/health/ready`,
`/health/live`) already exposed by `ServerCardController`. No new controller is
needed.

The `metrics` endpoint (`/actuator/metrics`) provides ad-hoc inspection without
Prometheus, while `prometheus` (`/actuator/prometheus`) provides the full exposition
format.

### Decision 5: Label strategy — avoid PII and high cardinality

**Allowed labels (safe):**
- `tool_name` — MCP tool name (e.g., `execute_build_command`). Bounded set (~30 values).
- `tool_service` — service class name (e.g., `BuildToolsService`). Bounded set (~15 values).
- `tool` — build tool name (`maven`, `gradle`, `sbt`). 3 values.
- `command` — build command (`clean`, `test`, `package`). Bounded set (~20 values).
- `result` — `success`/`failure`. 2 values.
- `status` — `completed`/`failed`/`cancelled`. 3 values.
- `error_type` — error category (`compilation`, `test`, `timeout`, `auth`, `unknown`). ~10 values.
- `auth_type` — `api_key`, `jwt`, `none`. 3 values.
- `cache_category` — `build_cache`, `config_cache`, `incremental_compilation`. ~5 values.

**Forbidden labels (PII or high-cardinality risk):**
- `project_dir` — user-provided file paths vary infinitely.
- `user_id` — no user model; not collected.
- `request_ip` — IP addresses are high-cardinality PII.
- `command_raw` — raw build commands contain project-specific paths.
- `error_message` — full error messages contain file paths and source code.

### Decision 6: Actuator security

The `/actuator/prometheus` and `/actuator/metrics` endpoints follow the same security
model as existing health endpoints:

- In **stdio mode** (default): no HTTP surface, no issue.
- In **HTTP mode** (`--http`): the Prometheus endpoint is **NOT** behind the OAuth
  resource-server filter (it lives outside `/mcp/**`). For production deployments,
  restrict access via the reverse proxy or set:
  ```properties
  management.endpoints.web.exposure.exclude=prometheus
  ```
  and expose Prometheus through a separate management port:
  ```properties
  management.server.port=8081
  ```

---

## 3. Metric Definitions

### 3.1 Tool Call Metrics

```java
// Applied to each @Tool method in every service
@Timed(
    value = "buildtools.tool.calls",
    extraTags = {"service", "BuildToolsService"},
    histogram = true,
    percentiles = {0.5, 0.95, 0.99},
    Description = "Total tool invocations and duration"
)
```

**Exposed Prometheus metrics:**

```
# HELP buildtools_tool_calls_seconds Tool invocation duration
# TYPE buildtools_tool_calls_seconds histogram
buildtools_tool_calls_seconds_count{tool_name="execute_build_command",service="BuildToolsService",exception="none",} 142.0
buildtools_tool_calls_seconds_sum{tool_name="execute_build_command",service="BuildToolsService",exception="none",} 1234.5

# HELP buildtools_tool_calls_total Total tool invocations
# TYPE buildtools_tool_calls_total counter
buildtools_tool_calls_total{tool_name="check_dependency_version",service="DependencyService",status="success",} 87.0
buildtools_tool_calls_total{tool_name="check_dependency_version",service="DependencyService",status="error",} 3.0
```

### 3.2 Build Execution Metrics

```java
// Inside BuildToolsService.executeBuildCommand()
MeterRegistry registry; // injected

Timer.Sample sample = Timer.start(registry);
try {
    String result = tool.executeCommand(home, dir, command);
    sample.stop(Timer.builder("buildtools.build.duration")
        .tag("tool", tool.getName())
        .tag("command", command.split(" ")[0])
        .tag("success", "true")
        .register(registry));
    Counter.builder("buildtools.build.total")
        .tag("tool", tool.getName())
        .tag("command", command.split(" ")[0])
        .tag("result", "success")
        .register(registry).increment();
    return result;
} catch (Exception e) {
    sample.stop(Timer.builder("buildtools.build.duration")
        .tag("tool", tool.getName())
        .tag("command", command.split(" ")[0])
        .tag("success", "false")
        .register(registry));
    Counter.builder("buildtools.build.total")
        .tag("tool", tool.getName())
        .tag("command", command.split(" ")[0])
        .tag("result", "failure")
        .register(registry).increment();
    throw e;
}
```

**Exposed Prometheus metrics:**

```
# HELP buildtools_build_duration_seconds Build execution duration
# TYPE buildtools_build_duration_seconds timer
buildtools_build_duration_seconds_count{tool="maven",command="clean",success="true"} 12.0
buildtools_build_duration_seconds_sum{tool="maven",command="clean",success="true"} 45.6

# HELP buildtools_build_total Total build executions
# TYPE buildtools_build_total counter
buildtools_build_total{tool="gradle",command="build",result="success"} 23.0
buildtools_build_total{tool="gradle",command="build",result="failure"} 1.0
```

### 3.3 Build Error Metrics

```java
// Inside each BuildOutputParser.parse()
Counter.builder("buildtools.build.errors")
    .tag("tool", toolName)
    .tag("error_type", classifyError(error.message))
    .register(registry)
    .increment();

private String classifyError(String message) {
    if (message.contains("cannot find symbol")) return "compilation";
    if (message.contains("expected")) return "compilation";
    if (message.contains("test") && (message.contains("FAILED") || message.contains("fail"))) return "test";
    if (message.contains("timeout") || message.contains("Timed out")) return "timeout";
    if (message.contains("permission") || message.contains("denied")) return "auth";
    return "unknown";
}
```

**Exposed Prometheus metrics:**

```
# HELP buildtools_build_errors_total Build errors by type
# TYPE buildtools_build_errors_total counter
buildtools_build_errors_total{tool="maven",error_type="compilation"} 5.0
buildtools_build_errors_total{tool="gradle",error_type="test"} 12.0
buildtools_build_errors_total{tool="sbt",error_type="unknown"} 1.0
```

### 3.4 Cache Health Metrics — MeterBinder

```java
@Component
public class CacheMetricsBinder implements MeterBinder {

    private final BuildCacheService cacheService;

    @Override
    public void bindTo(MeterRegistry registry) {
        // Cache hit rate gauge (updated on each analyzeCacheHealth call)
        Gauge.builder("buildtools.cache.hit.rate", cacheService,
                svc -> svc.getLastHitRate("gradle"))
            .tag("tool", "gradle")
            .description("Last reported build cache hit rate")
            .register(registry);

        // Cache score gauge
        Gauge.builder("buildtools.cache.score", cacheService,
                svc -> svc.getLastScore("maven"))
            .tag("tool", "maven")
            .tag("category", "overall")
            .description("Cache health score (0-100)")
            .register(registry);

        // Async task queue depth
        Gauge.builder("buildtools.async.tasks", asyncBuildService,
                svc -> svc.getActiveTaskCount())
            .tag("status", "running")
            .register(registry);
    }
}
```

**Exposed Prometheus metrics:**

```
# HELP buildtools_cache_hit_rate Last reported build cache hit rate
# TYPE buildtools_cache_hit_rate gauge
buildtools_cache_hit_rate{tool="gradle"} 0.85

# HELP buildtools_cache_score Cache health score (0-100)
# TYPE buildtools_cache_score gauge
buildtools_cache_score{tool="maven",category="overall"} 55.0

# HELP buildtools_async_tasks Async task queue depth
# TYPE buildtools_async_tasks gauge
buildtools_async_tasks{tool="gradle",status="running"} 2.0
```

### 3.5 Auth Metrics

```java
// Inside OAuthResourceServerFilter.doFilter()
Counter.builder("buildtools.auth.requests")
    .tag("auth_type", token != null ? "jwt" : "none")
    .tag("result", isValid ? "allowed" : "denied")
    .register(registry)
    .increment();
```

```
# HELP buildtools_auth_requests_total Authorization requests
# TYPE buildtools_auth_requests_total counter
buildtools_auth_requests_total{auth_type="jwt",result="allowed"} 45.0
buildtools_auth_requests_total{auth_type="jwt",result="denied"} 2.0
buildtools_auth_requests_total{auth_type="none",result="denied"} 10.0
```

### 3.6 Tool Registration

```java
// During application startup, after MethodToolCallbackProvider registers tools
Gauge.builder("buildtools.tools.registered", () -> toolCount)
    .description("Number of currently registered MCP tools")
    .register(registry);
```

```
# HELP buildtools_tools_registered Number of currently registered MCP tools
# TYPE buildtools_tools_registered gauge
buildtools_tools_registered 28.0
```

---

## 4. Instrumentation Approach

### 4.1 `@Timed` on Tool Methods

Add `@Timed` annotation to each `@Tool` method across all 15+ service classes.
The `buildtools.tool.calls` metric captures both count and duration for every tool.

**Example (BuildToolsService):**

```java
@Timed(
    value = "buildtools.tool.calls",
    extraTags = {"service", "BuildToolsService"},
    percentiles = {0.5, 0.95, 0.99},
    description = "Tool invocation count and duration"
)
@Tool(name = "execute_build_command", description = "...")
public String executeBuildCommand(...) { ... }
```

**How many annotations to add:** ~30 (one per active `@Tool` method). This is mechanical
and can be done in a single pass.

### 4.2 Manual Instrumentation (selectively)

Where `@Timed` doesn't expose enough labels (build success/failure, cache scores):
inject `MeterRegistry` directly and use `Timer.Sample` and `Counter.builder()`.

Manual instrumentation is needed in:
- `BuildToolsService.executeBuildCommand()` — build-specific duration + status tags.
- `BuildToolsService.analyzeBuildOutput()` — error-type classification.
- `BuildCacheService.analyzeCacheHealth()` — cache score gauge (via `MeterBinder`).
- `OAuthResourceServerFilter.doFilter()` — auth request counters.
- `AsyncBuildService` — task queue depth gauges.

### 4.3 TimedAspect Registration

```java
// New file: src/main/java/com/pragmatik/buildtools/observability/MetricsConfig.java
@Configuration
public class MetricsConfig {
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
```

Without this bean, `@Timed` annotations have no effect.

### 4.4 MeterBinder Beans

```java
// New file: src/main/java/com/pragmatik/buildtools/observability/BuildMetricsBinder.java
@Component
public class BuildMetricsBinder implements MeterBinder {
    // Bind cache, async task, and tool registration gauges
}

// New file: src/main/java/com/pragmatik/buildtools/observability/JvmMetricsBinder.java
// (Optional — Micrometer auto-configures JVM metrics with:
//  management.metrics.jvm.enabled=true)
```

---

## 5. Prometheus Endpoint Configuration

### 5.1 application.properties

```properties
# ─── Actuator ──────────────────────────────────────────────
management.endpoints.web.base-path=/actuator
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=when-authorized
management.endpoint.health.probes.enabled=true
management.endpoint.prometheus.enabled=true
management.endpoint.metrics.enabled=true

# ─── Micrometer ────────────────────────────────────────────
management.metrics.export.prometheus.enabled=true
management.metrics.tags.application=${spring.application.name:mcp-server-jvm-build-tools}

# JVM metrics enabled by default via spring-boot-starter-actuator
management.metrics.jvm.enabled=true

# HTTP request metrics (optional)
management.metrics.web.server.auto-time-requests=false
```

### 5.2 Security

The `/actuator/prometheus` endpoint is accessible without authentication by default.
For production deployments:

```properties
# Option A: Exclude from public exposure, expose on management port
management.endpoints.web.exposure.exclude=prometheus
management.server.port=8081

# Option B: Keep on main port but protect via IP allowlist or token
# (Configure at the reverse proxy level)
```

### 5.3 Sample Prometheus Scrape Config

```yaml
scrape_configs:
  - job_name: 'mcp-server-jvm-build-tools'
    scrape_interval: 15s
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8080']
    # In production with management port:
    #   targets: ['localhost:8081']
```

---

## 6. Integration with Existing Services

### 6.1 BuildPerformanceService

The existing `BuildPerformanceService` already collects per-build timing data and
persists it in `.buildtools/history/`. The new metrics are **complementary**, not
redundant:

| Metrics Type | BuildPerformanceService | Micrometer/Prometheus |
|-------------|------------------------|----------------------|
| Per-build duration | ✅ Full detail, persisted | ✅ Histogram, aggregated |
| Phase breakdown | ✅ Yes, persisted | ❌ Not collected (too detailed) |
| Trend analysis | ✅ Cross-build trends | ❌ Prometheus doesn't store long-term |
| Real-time dashboards | ❌ | ✅ Prometheus + Grafana |
| Alert triggers | ❌ | ✅ Prometheus alert rules |
| Historical data > 30d | ✅ Keeps last 20 builds | ❌ Prometheus retention is storage-based |

**Integration:** `BuildPerformanceService.profileBuild()` increments the
`buildtools.build.total` counter. No other changes needed.

### 6.2 BuildCacheService

The existing `BuildCacheService` calculates a cache health score (0-100) per tool.
The `BuildMetricsBinder` registers a `Gauge` that reads the last score from
`BuildCacheService`. The cache service needs a trivial getter addition:

```java
// In BuildCacheService
private final Map<String, Double> lastScores = new ConcurrentHashMap<>();

public double getLastScore(String tool) {
    return lastScores.getOrDefault(tool, 0.0);
}

// Update in analyzeCacheHealth():
lastScores.put(tool.getName(), (double) score);
```

### 6.3 BuildToolsService

The main build execution service is the primary target for instrumentation:
- `@Timed` on all 6 `@Tool` methods.
- Manual `Timer.Sample` in `executeBuildCommand()` for the `buildtools.build.duration`
  timer with build-specific tags.
- Manual `Counter` in error handling for `buildtools.build.errors`.

### 6.4 ToolAuthorizationService / OAuthResourceServerFilter

Auth metrics are collected at the filter level (OAuthResourceServerFilter) rather
than in the service, because the service only sees already-authenticated requests.
The filter sees all requests, including unauthenticated ones.

---

## 7. Test Scenarios

### Scenario 1: Prometheus endpoint returns valid data
```
GET /actuator/prometheus
Expect: 200 OK
  Content-Type: text/plain; version=0.0.4
  Body contains: buildtools_tool_calls_seconds_count{...,} 0.0
  Body contains: jvm_memory_used_bytes{area="heap",...}
  Body contains: JVM, system, and Micrometer metrics in Prometheus text format
```

### Scenario 2: Tool call counter increments
```
Step 1: GET /actuator/prometheus → note buildtools_tool_calls_total value
Step 2: Call get_build_tool_version(buildToolName="maven")
Step 3: GET /actuator/prometheus → buildtools_tool_calls_total incremented by 1
```

### Scenario 3: Build duration timer records
```
Step 1: Call execute_build_command(projectDir="/tmp/maven-project", command="clean compile")
Step 2: GET /actuator/prometheus
Expect: buildtools_build_duration_seconds_count{tool="maven",command="clean",success="true"} ≥ 1
        buildtools_build_duration_seconds_sum{tool="maven",command="clean",success="true"} > 0
```

### Scenario 4: Error counter on build failure
```
Step 1: Call execute_build_command with a project that has compilation errors
Step 2: GET /actuator/prometheus
Expect: buildtools_build_errors_total{tool="maven",error_type="compilation"} incremented
        buildtools_build_total{tool="maven",command="compile",result="failure"} incremented
```

### Scenario 5: Cache health gauge
```
Step 1: Call analyze_cache_health(projectDir="/tmp/gradle-project")
Step 2: GET /actuator/prometheus
Expect: buildtools_cache_score{tool="gradle",category="overall"} presents the cache score
```

### Scenario 6: Auth metrics recorded
```
Step 1: POST /mcp/tools/call (with valid bearer token)
Step 2: POST /mcp/tools/call (with invalid bearer token)
Step 3: GET /actuator/prometheus
Expect: buildtools_auth_requests_total{auth_type="jwt",result="allowed"} incremented once
        buildtools_auth_requests_total{auth_type="jwt",result="denied"} incremented once
```

### Scenario 7: Tool registration gauge
```
Step 1: Start server
Step 2: GET /actuator/prometheus
Expect: buildtools_tools_registered equals the number of @Tool methods (~28)
```

### Scenario 8: Metrics endpoint (ad-hoc inspection)
```
GET /actuator/metrics/buildtools.tool.calls
Expect: 200 OK
  Content-Type: application/json
  Body: {"name": "buildtools.tool.calls", "measurements": [...], "availableTags": [...]}
```

### Scenario 9: Management port isolation
```
Step 1: Configure management.server.port=8081
Step 2: Start server
Step 3: GET /actuator/prometheus on port 8080 → 404 (or not routed)
Step 4: GET /actuator/prometheus on port 8081 → 200 with metrics
```

### Scenario 10: No PII in labels
```
Step 1: Execute builds with various project paths
Step 2: Examine /actuator/prometheus output
Expect: No project_dir, user_id, request_ip, command_raw, or error_message labels present
```

---

## 8. Implementation Plan

### Files to Create

| File | Purpose |
|------|---------|
| `src/main/java/com/pragmatik/buildtools/observability/MetricsConfig.java` | `@Configuration` — `TimedAspect` bean + `MeterRegistryCustomizer` |
| `src/main/java/com/pragmatik/buildtools/observability/BuildMetricsBinder.java` | `MeterBinder` — cache, async, tool-registration gauges |
| `src/test/java/com/pragmatik/buildtools/observability/MetricsIntegrationTest.java` | Prometheus endpoint integration test |

### Files to Modify

| File | Change |
|------|--------|
| `pom.xml` | Add `micrometer-registry-prometheus` dependency |
| `src/main/resources/application.properties` | Add actuator and Micrometer configuration |
| `BuildToolsService.java` | Add `@Timed` to 6 `@Tool` methods; manual Timer/Counter in `executeBuildCommand()` |
| `DependencyService.java` | Add `@Timed` to `check_dependency_version` |
| `PromptService.java` | Add `@Timed` to 3 `@Tool` methods |
| `BuildResourceService.java` | Add `@Timed` to 2 `@Tool` methods |
| `DependencyResourceService.java` | Add `@Timed` to 2 `@Tool` methods |
| `ResourceTemplateService.java` | Add `@Timed` to 2 `@Tool` methods |
| `SbtProjectService.java` | Add `@Timed` to 3 `@Tool` methods |
| `BuildAuthService.java` | Add `@Timed` to `check_credential_status` |
| `DependencyConflictService.java` | Add `@Timed` to `detect_dependency_conflicts` |
| `JavaVersionService.java` | Add `@Timed` to `check_java_compatibility` |
| `BuildPerformanceService.java` | Add `@Timed` to 2 `@Tool` methods |
| `ToolAuthorizationService.java` | Add `@Timed` to 4 `@Tool` methods |
| `BuildCacheService.java` | Add `@Timed` to 2 `@Tool` methods; add `getLastScore()` |
| `OAuthResourceServerFilter.java` | Add auth request counter |
| `docs/reference/tools.md` | Document metrics endpoint and available metrics |

### Dependencies to Add

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

No version needed — managed by Spring Boot 4.1.0 BOM.

---

## 9. Suggested Grafana Dashboard Metrics

| Panel | Metric | Type |
|-------|--------|------|
| Tool call rate (per tool) | `rate(buildtools_tool_calls_total[5m])` | Bar chart |
| Tool call duration (p95) | `histogram_quantile(0.95, rate(buildtools_tool_calls_seconds_bucket[5m]))` | Time series |
| Build duration (p95, per tool) | `histogram_quantile(0.95, rate(buildtools_build_duration_seconds_bucket[5m]))` | Time series |
| Build success rate | `rate(buildtools_build_total{result="success"}[5m]) / rate(buildtools_build_total[5m]) * 100` | Gauge |
| Error rate by type | `rate(buildtools_build_errors_total[5m])` | Stacked area |
| Cache health score | `buildtools_cache_score` | Gauge |
| Auth request rate | `rate(buildtools_auth_requests_total[5m])` | Time series |
| Active async tasks | `buildtools_async_tasks` | Gauge |
| JVM heap usage | `jvm_memory_used_bytes{area="heap"}` | Area |
| JVM GC pauses | `rate(jvm_gc_pause_seconds_sum[5m])` | Time series |

---

## 10. Acceptance Criteria

- [ ] `/actuator/prometheus` returns valid Prometheus exposition format.
- [ ] `/actuator/metrics/buildtools.tool.calls` returns the tool-call metric with tags.
- [ ] All ~30 `@Tool` methods are annotated with `@Timed`.
- [ ] Build duration timer tracks `tool`, `command`, and `success` labels.
- [ ] Build errors counter classifies errors into `compilation`, `test`, `timeout`, `auth`.
- [ ] Cache health gauge is registered and updates on `analyze_cache_health` calls.
- [ ] Auth request counter works for both API key and JWT authentication.
- [ ] No PII or high-cardinality labels in any metric.
- [ ] `TimedAspect` bean registered without errors.
- [ ] `micrometer-registry-prometheus` dependency added with no version conflicts.
- [ ] Prometheus endpoint can be isolated on a management port.
- [ ] All 10 test scenarios pass.
- [ ] No breaking changes to existing service contracts.
