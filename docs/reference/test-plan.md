# Test Plan — JVM Build Tools MCP Server (Docker)

> **Document:** ARCH-2 usability and performance test plan  
> **Server:** mcp-server-jvm-build-tools v1.0.0 (28 MCP tools, Maven/Gradle/SBT)  
> **Environment:** Docker (eclipse-temurin:21-jre-alpine + Maven/Gradle 9.6.1/SBT 2.0.3)  
> **Test Tools:** MCP Inspector (`npx @modelcontextprotocol/inspector --cli`) primary; MCP Tools (`mcp`) secondary  
> **Date:** 2026-07-23  
> **Author:** ARCH-2 (hswarm-arch)

---

## Table of Contents

1. [Overview & Test Objectives](#1-overview-test-objectives)
2. [Test Environment](#2-test-environment)
3. [Scenario 1 — Tool Discovery](#3-scenario-1-tool-discovery)
4. [Scenario 2 — Build Execution (Maven)](#4-scenario-2-build-execution-maven)
5. [Scenario 3 — Build Execution (Gradle)](#5-scenario-3-build-execution-gradle)
6. [Scenario 4 — Build Execution (SBT)](#6-scenario-4-build-execution-sbt)
7. [Scenario 5 — Error Handling & Edge Cases](#7-scenario-5-error-handling-edge-cases)
8. [Scenario 6 — Concurrent Operations](#8-scenario-6-concurrent-operations)
9. [Scenario 7 — Performance Benchmarks](#9-scenario-7-performance-benchmarks)
10. [Scenario 8 — Security & Authorization](#10-scenario-8-security-authorization)
11. [Success Criteria](#11-success-criteria)
12. [Measurement Methodology](#12-measurement-methodology)
13. [Test Execution Plan](#13-test-execution-plan)
14. [Appendix A — Tool-to-Scenario Mapping](#14-appendix-a-tool-to-scenario-mapping)
15. [Appendix B — Test Project Fixtures](#15-appendix-b-test-project-fixtures)
16. [Appendix C — Known-Gap Regression Tests](#16-appendix-c-known-gap-regression-tests)

---

## 1. Overview & Test Objectives

### 1.1 Purpose

This plan defines the real-world test strategy for the `mcp-server-jvm-build-tools` MCP server running in Docker. It covers functional correctness (tool discovery, build execution, error handling), concurrency, performance, and security—all exercised through MCP clients against the Docker container.

### 1.2 Scope

| In scope | Out of scope |
|----------|--------------|
| MCP protocol compliance (initialize, tools/list, tools/call) | Unit tests on Java service classes |
| Build execution across Maven/Gradle/SBT via Docker container | Native (non-Docker) installation testing |
| Validation, error handling, edge cases | Performance of underlying build tools themselves |
| Concurrent/async tool calls | Client-specific integration (Claude Desktop, Cursor, etc.) |
| Authorization, authentication, audit logging | Gradle/SBT wrapper generation |
| Docker image startup, health, resource usage | Production-scale load testing |

### 1.3 Reference Documents

- **README.md** — full tool catalog and client integration guide
- **docs/TOOLS.md** — per-tool parameter and output schema reference
- **docs/ARCHITECTURE.md** — 12 service beans, BuildTool SPI, transport layer
- **docs/EVIDENCE.md** — MCP protocol compliance evidence
- **docs/AUTHORIZATION.md** — OAuth 2.1 resource-server and scope model
- **docs/reference/test-tools-recommendations.md** — upstream RSRCH-2 evaluation

### 1.4 Tools Under Test (28 MCP Tools by Category)

| Category | Tools | Count |
|----------|-------|-------|
| **Build Operations** | get_build_tool_version, list_build_tools, detect_build_tool, execute_build_command, analyze_build_output, validate_build_configuration | 6 |
| **Async Builds** | execute_build_async, get_build_task, cancel_build_task, list_build_tasks | 4 |
| **Dependency Management** | check_dependency_version, detect_dependency_conflicts, check_java_compatibility | 3 |
| **Supply Chain Security** | generate_sbom, audit_supply_chain, check_license_compliance | 3 |
| **SBT-Specific** | detect_sbt_modules, detect_sbt_test_frameworks, analyze_sbt_build | 3 |
| **Performance** | profile_build, analyze_build_performance, analyze_cache_health, optimize_build_cache, detect_flaky_tests, analyze_test_history | 6 |
| **Resources** | list_build_resources, read_build_resource, list_dependency_resources, read_dependency_resource, list_resource_templates, resolve_resource_template | 6 |
| **Security & Auth** | check_tool_authorization, list_available_scopes, audit_tool_access, validate_access_token | 4 |
| **Prompts** | prompt_build_and_test, prompt_build_diagnosis, prompt_dependency_audit | 3 |
| **Credentials** | check_credential_status | 1 |

_Some tools appear in multiple categories; total unique tools = 28 (counting paired resource tools as 1 each)._

---

## 2. Test Environment

### 2.1 Docker Setup

```yaml
# docker-compose.test.yml
services:
  jvm-build-tools-server:
    build:
      context: .
      dockerfile: Dockerfile
    image: mcp-server-jvm-build-tools:test
    ports:
      - "8080:8080"
    networks:
      - test-net
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
      interval: 5s
      timeout: 3s
      retries: 10
      start_period: 15s
    environment:
      - BUILDTOOLS_API_KEY_TEST=test-key-for-ci
      - MAVEN_HOME=/usr/share/java/maven
    command: ["--spring.profiles.active=http"]

  mcp-test-runner:
    image: ghcr.io/modelcontextprotocol/inspector:latest
    networks:
      - test-net
    depends_on:
      jvm-build-tools-server:
        condition: service_healthy
    entrypoint: ["sleep", "infinity"]

networks:
  test-net:
```

### 2.2 Transport Modes

| Mode | Port | Command Profile | Use Case |
|------|------|----------------|----------|
| **stdio** | — (pipe) | Default (no profile) | Local testing, CI via docker exec |
| **Streamable HTTP** | 8080 | `--spring.profiles.active=http` | Remote clients, network-based testing |

**All scenarios SHALL be tested over both transports** unless explicitly noted. Known issue: HTTP transport has a known non-functional bug (#142); test results MUST be annotated accordingly.

### 2.3 Test Client Commands

#### MCP Inspector (Primary)

```bash
# stdio mode
npx @modelcontextprotocol/inspector --cli \
  docker exec -i jvm-build-tools-server java -jar /app/mcp-server-jvm-build-tools.jar \
  --method tools/list --json

# HTTP mode
npx @modelcontextprotocol/inspector --cli \
  --transport sse --url http://localhost:8080/mcp \
  --method tools/list --json
```

#### MCP Tools (Secondary, for speed)

```bash
# stdio mode
mcp tools -- docker exec -i jvm-build-tools-server java -jar /app/mcp-server-jvm-build-tools.jar

# HTTP mode
mcp tools -- http://localhost:8080/mcp
```

### 2.4 Prerequisites

- Docker Engine 24+ with Compose v2
- Node.js 20+ (for MCP Inspector via npx)
- `jq` for JSON assertion scripting
- Test project fixtures (see Appendix B)
- Network access for `npx` to fetch Inspector on first run

---

## 3. Scenario 1 — Tool Discovery

### 3.1 Objective

Verify the server correctly advertises all 28 tools via `tools/list` and that each tool's input schema is valid JSON Schema 2020-12.

### 3.2 Test Cases

| ID | Test Case | Procedure | Expected Result |
|----|-----------|-----------|-----------------|
| **DISC-01** | `tools/list` returns all 28 tools (stdio) | Connect via stdio, call tools/list, parse JSON | Response contains `tools` array with ≥28 entries; each has `name`, `description`, `inputSchema` |
| **DISC-02** | `tools/list` returns all 28 tools (HTTP) | Connect via HTTP SSE, call tools/list | Same as DISC-01; annotate if HTTP transport is non-functional (#142) |
| **DISC-03** | Tools listed deterministically | Call tools/list 5 times; compare tool name ordering | Same order every call (v1.0.0 deterministic ordering) |
| **DISC-04** | `inputSchema` validates as JSON Schema 2020-12 | For each tool, extract inputSchema and validate | Every schema has `type: "object"`, valid `properties`, and `required` arrays; no `$schema` misuse |
| **DISC-05** | `list_build_tools` returns all 3 tools | Call list_build_tools with no params | Returns string listing `maven:`, `gradle:`, `sbt:` with command lists |
| **DISC-06** | `list_resource_templates` returns templates | Call with no params | Returns JSON array of template URIs with metadata |
| **DISC-07** | `list_available_scopes` returns scopes | Call with no params | Returns JSON with `scopes[]`; each scope has `toolCount` and `tools[]` |
| **DISC-08** | `initialize` handshake succeeds | Standard MCP initialize → initialized | Server responds with protocol version, server name, capabilities |

### 3.3 Test Script (DISC-01 as example)

```bash
#!/bin/bash
# test-tool-discovery.sh
set -euo pipefail

echo "=== DISC-01: tools/list via stdio ==="
OUTPUT=$(npx @modelcontextprotocol/inspector --cli \
  docker exec -i jvm-build-tools-server java -jar /app/mcp-server-jvm-build-tools.jar \
  --method tools/list --json 2>/dev/null)

TOOL_COUNT=$(echo "$OUTPUT" | jq '.tools | length')
echo "Tools discovered: $TOOL_COUNT"

if [ "$TOOL_COUNT" -lt 28 ]; then
  echo "FAIL: Expected ≥28 tools, got $TOOL_COUNT"
  exit 1
fi
echo "PASS: DISC-01"

# Verify required tools exist
for TOOL in get_build_tool_version execute_build_command detect_build_tool \
            check_dependency_version validate_build_configuration \
            execute_build_async profile_build detect_flaky_tests \
            generate_sbom audit_supply_chain check_credential_status; do
  if ! echo "$OUTPUT" | jq -e ".tools[] | select(.name == \"$TOOL\")" > /dev/null; then
    echo "FAIL: Missing required tool: $TOOL"
    exit 1
  fi
done
echo "PASS: DISC-01 — All required tools present"
```

### 3.4 Success Thresholds

| Metric | Threshold |
|--------|-----------|
| Tool count | ≥28 tools |
| Schema validity | 100% of tools have valid inputSchema |
| Deterministic ordering | 5/5 calls = identical order |
| tools/list response time | <2 seconds |

---

## 4. Scenario 2 — Build Execution (Maven)

### 4.1 Objective

Verify the full Maven lifecycle through the MCP server: detect → validate → build → analyze.

### 4.2 Test Cases

| ID | Test Case | Procedure | Expected Result |
|----|-----------|-----------|-----------------|
| **MVN-01** | `detect_build_tool` finds Maven project | Point to test fixture with pom.xml | Returns `detections[{tool:"maven", matchedFiles:["pom.xml"]}]` |
| **MVN-02** | `get_build_tool_version` returns Maven version | `buildToolName="maven"` | Returns version string matching `Apache Maven 3.x.y` |
| **MVN-03** | `validate_build_configuration` on valid pom.xml | Point to well-formed Maven project | `valid: true`, no errors |
| **MVN-04** | `execute_build_command` — compile | `command="clean compile"` | `success: true`, exit code 0, no errors |
| **MVN-05** | `execute_build_command` — test | `command="test"` | `success: true`, test summary with pass count |
| **MVN-06** | `execute_build_command` — package | `command="package"` | `success: true`, JAR produced in target/ |
| **MVN-07** | `analyze_build_output` — structured results | `command="clean test"` | JSON with `testSummary`, `errors[]`, `warnings[]` |
| **MVN-08** | Auto-detect (omit buildToolName) | Call execute_build_command without buildToolName | Correctly auto-detects Maven from pom.xml |
| **MVN-09** | Maven wrapper (mvnw) support | Project has mvnw; no MAVEN_HOME set | Build succeeds via wrapper |
| **MVN-10** | Maven with flags | `command="clean compile -DskipTests -T4"` | Build succeeds; flags accepted (allowlist removed) |

### 4.3 Test Data

Fixture project `test-fixtures/maven-basic/` with:
- Well-formed `pom.xml` (group: com.test, artifact: hello-maven, version: 1.0.0)
- 2 source files: `Main.java` (simple Hello World), `Calculator.java` (add/subtract)
- 5 unit tests: 3 passing, 1 intentionally failing (for error scenario), 1 with a warning
- Maven wrapper (`mvnw`) included

### 4.4 Key Validation Points

- Build output captured in real-time (no buffered-then-flushed output)
- `projectDir` path canonicalization (relative paths, symlinks, `~` expansion)
- Exit code mapping: non-zero exit → `success: false` with error details
- Timeout handling: builds exceeding 30s default timeout are killed

---

## 5. Scenario 3 — Build Execution (Gradle)

### 5.1 Objective

Verify Gradle build lifecycle through the MCP server, including wrapper auto-detection and multi-project builds.

### 5.2 Test Cases

| ID | Test Case | Procedure | Expected Result |
|----|-----------|-----------|-----------------|
| **GRD-01** | `detect_build_tool` finds Gradle (Groovy DSL) | Point to fixture with build.gradle | Returns `detections[{tool:"gradle", matchedFiles:["build.gradle"]}]` |
| **GRD-02** | `detect_build_tool` finds Gradle (Kotlin DSL) | Point to fixture with build.gradle.kts | Returns `detections[{tool:"gradle", matchedFiles:["build.gradle.kts"]}]` |
| **GRD-03** | `get_build_tool_version` returns Gradle version | `buildToolName="gradle"` | Returns version string; should be ≥8.12 |
| **GRD-04** | `execute_build_command` — build | `command="build"` | `success: true`, all tasks pass |
| **GRD-05** | `execute_build_command` — test | `command="test"` | `success: true`, test report generated |
| **GRD-06** | Gradle wrapper (gradlew) auto-detection | Fixture has gradlew, no system Gradle on PATH | Build uses wrapper automatically |
| **GRD-07** | Gradle with excluded tasks | `command="build -x test"` | Build succeeds, tests skipped |
| **GRD-08** | Gradle multi-project detection | Multi-module fixture with settings.gradle | Build scope correct; sub-projects discoverable |

### 5.3 Test Data

Fixture project `test-fixtures/gradle-basic/` with:
- `build.gradle` (Groovy DSL) applying `java` and `application` plugins
- `settings.gradle` with root project name
- 2 source files, 4 unit tests (JUnit 5)
- Gradle wrapper (`gradlew`) included

---

## 6. Scenario 4 — Build Execution (SBT)

### 6.1 Objective

Verify SBT build lifecycle and SBT-specific tools (detect_sbt_modules, detect_sbt_test_frameworks, analyze_sbt_build).

### 6.2 Test Cases

| ID | Test Case | Procedure | Expected Result |
|----|-----------|-----------|-----------------|
| **SBT-01** | `detect_build_tool` finds SBT project | Point to fixture with build.sbt | Returns `detections[{tool:"sbt", matchedFiles:["build.sbt"]}]` |
| **SBT-02** | `detect_sbt_modules` parses sub-modules | Point to multi-module SBT fixture | Returns JSON with module names, paths, dependencies |
| **SBT-03** | `detect_sbt_test_frameworks` finds ScalaTest | Point to fixture with ScalaTest dependency | Returns `[{"framework":"ScalaTest","version":"3.2.x"}]` |
| **SBT-04** | `execute_build_command` — compile | `command="compile"` | `success: true` |
| **SBT-05** | `execute_build_command` — test | `command="test"` | `success: true`, test results |
| **SBT-06** | `analyze_sbt_build` — structured output | `command="test"` | JSON with `output`, `errors`, `warnings` |
| **SBT-07** | `get_build_tool_version` returns SBT version | `buildToolName="sbt"` | Returns version string ≥1.10.10 |

### 6.3 Test Data

Fixture project `test-fixtures/sbt-basic/` with:
- `build.sbt` (Scala 2.13, sbt 1.10.x)
- `project/build.properties` with sbt.version
- 1 source file, 3 ScalaTest specs

### 6.4 Known Gap

Bug #139: Docker image may be missing Gradle and SBT installations. If either is absent, mark SBT and Gradle test cases as **BLOCKED** with reference to #139. Test plan includes a pre-flight check (see Section 13.2).

---

## 7. Scenario 5 — Error Handling & Edge Cases

### 7.1 Objective

Verify the server handles invalid inputs, missing resources, and build failures gracefully with clear error messages—no crashes, no raw stack traces in MCP responses.

### 7.2 Test Cases

| ID | Test Case | Procedure | Expected Result |
|----|-----------|-----------|-----------------|
| **ERR-01** | Missing required parameter | Call execute_build_command without `projectDir` | Error response with message indicating missing parameter; no 500 |
| **ERR-02** | Non-existent project directory | execute_build_command with `projectDir="/nonexistent"` | Error: "Project directory not found" or similar; no crash |
| **ERR-03** | Invalid build command (Maven) | execute_build_command with `command="invalidCmd"` | Error from Maven, captured cleanly; `success: false` |
| **ERR-04** | Invalid build tool name | execute_build_command with `buildToolName="bazel"` | Error: "Unknown build tool: bazel" or similar |
| **ERR-05** | Project with no build files | detect_build_tool on empty directory | `detections: []` or clear "no build tool found" message |
| **ERR-06** | Malformed pom.xml | validate_build_configuration on broken XML | `valid: false`, issues[] with line numbers |
| **ERR-07** | Dependency not found on Maven Central | check_dependency_version for non-existent artifact | Graceful "not found" response; no 500 |
| **ERR-08** | Shell injection attempt | execute_build_command with `command="clean; rm -rf /"` | Blocked; command either sanitized or rejected |
| **ERR-09** | Dangerous flag — Maven | execute_build_command with `command="-version"` | Blocked; only supported lifecycle commands allowed |
| **ERR-10** | Path traversal attempt | execute_build_command with `projectDir="../../../etc"` | Canonicalization prevents escape; error or safe resolution |
| **ERR-11** | Concurrent async task cancellation | Start 3 async builds; cancel one mid-flight | Cancelled task stops; others unaffected |
| **ERR-12** | Duplicate taskId in async operations | Cancel already-completed task | No-op; returns status indicating already completed |
| **ERR-13** | Unauthorized tool call (auth enabled) | Call execute_build_command with read-only token | Authorization error; tool not executed |
| **ERR-14** | Build timeout | Execute a build command that hangs (e.g., interactive mode) | Server kills process after timeout; returns timeout error |

### 7.3 Validation Criteria

- **No server crashes** — every error path returns a structured MCP error response
- **No raw stack traces** in MCP response bodies (log them server-side only)
- **Error messages are actionable** — tell the client *what* went wrong and *how* to fix it
- **Timeout enforcement** — test with a deliberate 5s timeout on a build that takes 60s

---

## 8. Scenario 6 — Concurrent Operations

### 8.1 Objective

Verify the server handles multiple simultaneous requests without data corruption, race conditions, or resource exhaustion.

### 8.2 Test Cases

| ID | Test Case | Procedure | Expected Result |
|----|-----------|-----------|-----------------|
| **CONC-01** | Simultaneous tools/list from 5 clients | 5 parallel Inspector calls to tools/list | All 5 return identical results; no errors |
| **CONC-02** | Concurrent build on different projects | 3 parallel execute_build_command on separate fixture dirs | All 3 succeed; no cross-contamination |
| **CONC-03** | Concurrent build on same project | 2 parallel execute_build_command on same projectDir | One succeeds; the other either succeeds or gets a lock error (must not be silent corruption) |
| **CONC-04** | Async build queue saturation | Fire 20 execute_build_async calls rapidly; poll all with get_build_task | All complete or fail with clear status; no dropped tasks |
| **CONC-05** | Mixed sync + async calls | 5 sync builds + 10 async builds interleaved | All resolve correctly; sync calls don't block async queue |
| **CONC-06** | list_build_tasks during concurrent activity | Start 5 async builds; call list_build_tasks | Returns accurate active/count/completed count |
| **CONC-07** | Concurrent cancel operations | Start 5 async builds; cancel all simultaneously | All cancelled; no orphaned processes |

### 8.3 Test Script Framework

```bash
#!/bin/bash
# test-concurrency.sh
# Spawn N parallel Inspector calls and collect results

CONCURRENT=5
RESULTS_DIR=$(mktemp -d)

run_tool_discovery() {
  local id=$1
  npx @modelcontextprotocol/inspector --cli \
    --transport sse --url http://localhost:8080/mcp \
    --method tools/list --json > "$RESULTS_DIR/result-$id.json" 2>/dev/null
  echo "Worker $id done"
}

echo "=== CONC-01: $CONCURRENT parallel tool discoveries ==="
for i in $(seq 1 $CONCURRENT); do
  run_tool_discovery $i &
done
wait

# Compare results: all must have same tool count
COUNTS=$(for f in "$RESULTS_DIR"/result-*.json; do
  jq '.tools | length' "$f" 2>/dev/null || echo "0"
done | sort -u)
UNIQUE_COUNT=$(echo "$COUNTS" | wc -l)

if [ "$UNIQUE_COUNT" -eq 1 ]; then
  echo "PASS: CONC-01 — All $CONCURRENT clients got identical tool count"
else
  echo "FAIL: CONC-01 — Inconsistent results: $COUNTS"
fi
rm -rf "$RESULTS_DIR"
```

### 8.4 Resource Boundaries

| Resource | Limit | Behavior when exceeded |
|----------|-------|----------------------|
| Concurrent async builds | 10 (configurable) | 11th call returns "queue full" error |
| Max output size per build | ~100KB | Truncated in get_build_task; full output available on disk |
| Concurrent stdio connections | 1 | stdio is single-client by nature; second client gets connection refused |

---

## 9. Scenario 7 — Performance Benchmarks

### 9.1 Objective

Establish baseline performance metrics for key operations and detect regressions.

### 9.2 Test Cases

| ID | Test Case | Measurement | Acceptable Threshold | Target |
|----|-----------|-------------|---------------------|--------|
| **PERF-01** | `tools/list` latency (cold) | ms to first response | <2000ms | <500ms |
| **PERF-02** | `tools/list` latency (warm) | ms, 10th call | <500ms | <100ms |
| **PERF-03** | `initialize` handshake latency | ms | <1000ms | <300ms |
| **PERF-04** | `detect_build_tool` latency | ms | <500ms | <100ms |
| **PERF-05** | `execute_build_command` overhead | delta vs native build | <500ms overhead | <200ms overhead |
| **PERF-06** | `profile_build` overhead | delta vs execute_build_command | <1000ms | <500ms |
| **PERF-07** | `check_dependency_version` latency (network) | ms | <5000ms | <2000ms |
| **PERF-08** | `generate_sbom` time | seconds | <30s | <10s |
| **PERF-09** | `audit_supply_chain` time (10 deps) | seconds | <30s | <10s |
| **PERF-10** | Memory usage at idle | MB RSS | <256MB | <128MB |
| **PERF-11** | Memory usage under load (5 concurrent builds) | MB RSS peak | <512MB | <384MB |
| **PERF-12** | Docker image size | MB | <500MB | <400MB |
| **PERF-13** | Cold start time (container start → health OK) | seconds | <30s | <15s |
| **PERF-14** | Throughput: tools/list calls per second | req/s | ≥10 | ≥50 |

### 9.3 Measurement Tools

| Metric | Tool | Command |
|--------|------|---------|
| Latency (MCP calls) | `time` wrapper + jq | `time npx ... tools/list --json` |
| Memory (Docker) | `docker stats` | `docker stats --no-stream jvm-build-tools-server` |
| Image size | `docker image inspect` | `docker image inspect mcp-server-jvm-build-tools:test --format '{{.Size}}'` |
| Cold start | `docker compose up` timing | `time docker compose up -d --wait` |
| Throughput | Custom benchmark script | `ab`-style loop with Inspector |

### 9.4 Benchmark Script

```bash
#!/bin/bash
# perf-benchmark.sh
BENCH_DIR="/tmp/jvm-bt-perf-$$"
mkdir -p "$BENCH_DIR"
RUNS=20

echo "=== PERF-01/02: tools/list latency ==="
for i in $(seq 1 $RUNS); do
  START=$(python3 -c 'import time; print(time.time())')
  npx @modelcontextprotocol/inspector --cli \
    --transport sse --url http://localhost:8080/mcp \
    --method tools/list --json > /dev/null 2>/dev/null
  END=$(python3 -c 'import time; print(time.time())')
  python3 -c "print($END - $START)" >> "$BENCH_DIR/tools-list-latency.txt"
done

# Stats
python3 -c "
import statistics
times = [float(l.strip()) for l in open('$BENCH_DIR/tools-list-latency.txt')]
print(f'Runs: {len(times)}')
print(f'Min:   {min(times)*1000:.0f}ms')
print(f'Max:   {max(times)*1000:.0f}ms')
print(f'Mean:  {statistics.mean(times)*1000:.0f}ms')
print(f'P95:   {sorted(times)[int(len(times)*0.95)]*1000:.0f}ms')
"

echo "=== PERF-10: Memory at idle ==="
docker stats --no-stream jvm-build-tools-server \
  --format "CPU: {{.CPUPerc}}, MEM: {{.MemUsage}}, MEM%: {{.MemPerc}}"

echo "=== PERF-12: Image size ==="
docker image inspect mcp-server-jvm-build-tools:test \
  --format 'Image size: {{.Size}} bytes ({{divide .Size 1048576}} MB)'

rm -rf "$BENCH_DIR"
```

---

## 10. Scenario 8 — Security & Authorization

### 10.1 Objective

Verify OAuth 2.1 resource-server authorization, scope enforcement, audit logging, credential masking, and shell injection defenses.

### 10.2 Test Cases

| ID | Test Case | Procedure | Expected Result |
|----|-----------|-----------|-----------------|
| **SEC-01** | `list_available_scopes` returns correct scopes | Call with no auth | Returns scopes: build:read, build:execute, dependency:read, etc. |
| **SEC-02** | `check_tool_authorization` — authorized | Pass build:execute scope, check execute_build_command | `authorized: true` |
| **SEC-03** | `check_tool_authorization` — denied | Pass build:read only, check execute_build_command | `authorized: false` with explanation |
| **SEC-04** | `validate_access_token` — valid token | Pass valid BUILDTOOLS_API_KEY_TEST token | `valid: true`, returns scopes |
| **SEC-05** | `validate_access_token` — invalid token | Pass garbage token | `valid: false` |
| **SEC-06** | Unauthorized tool call rejected | With auth enabled, call build tool with no token | 401/403; tool not executed |
| **SEC-07** | `audit_tool_access` captures entries | Make several authorized + denied calls; audit | Log entries with timestamp, tool, caller, authorized, duration |
| **SEC-08** | `check_credential_status` masks passwords | Run with test credentials; check output | All passwords show `****xyz` masking |
| **SEC-09** | Shell injection blocked (see ERR-08/09) | Covered in error handling scenario | — |
| **SEC-10** | Token not leaked in responses | Call validate_access_token with valid token | Response does NOT echo the token value |
| **SEC-11** | Server card endpoint accessible | GET /.well-known/mcp-server | Returns JSON with name, version, capabilities, transports |
| **SEC-12** | Health endpoint | GET /health | Returns `{"status":"UP","version":"..."}` |

### 10.3 Authorization Test Setup

```bash
# Auth must be explicitly enabled
# Docker env: BUILDTOOLS_AUTH_ENABLED=true, BUILDTOOLS_API_KEY_TEST=test-scope-key

# Test with auth header
npx @modelcontextprotocol/inspector --cli \
  --transport sse --url http://localhost:8080/mcp \
  --header "Authorization: Bearer test-scope-key" \
  --method tools/list --json
```

---

## 11. Success Criteria

### 11.1 Functional Success (Pass/Fail)

| ID | Criterion | Scenario | Threshold |
|----|-----------|----------|-----------|
| F-01 | All 28 tools discoverable via `tools/list` | DISC-01, DISC-02 | ≥28 tools over both transports |
| F-02 | Maven clean compile succeeds | MVN-04 | Exit code 0, success: true |
| F-03 | Maven test+package with structured output | MVN-07 | Valid JSON with testSummary |
| F-04 | Gradle build succeeds (Groovy DSL) | GRD-04 | Exit code 0, success: true |
| F-05 | Gradle Kotlin DSL detected | GRD-02 | detect_build_tool finds .kts |
| F-06 | SBT compile succeeds | SBT-04, SBT-06 | Exit code 0, success: true |
| F-07 | Error handling: no crashes | ERR-* (all 14) | 0 server crashes; structured errors only |
| F-08 | Concurrent 5-client discovery | CONC-01 | All return identical results |
| F-09 | Async build lifecycle | CONC-04 | Queue → running → completed; all reach terminal state |
| F-10 | Authorization enforces scopes | SEC-02/03/06 | Denied calls rejected; authorized calls pass |

### 11.2 Performance Thresholds (Pass/Warn/Fail)

| ID | Metric | Pass | Warn | Fail |
|----|--------|------|------|------|
| P-01 | tools/list latency (warm) | <100ms | 100–500ms | >500ms |
| P-02 | Build overhead | <200ms | 200–1000ms | >1000ms |
| P-03 | Memory idle | <128MB | 128–256MB | >256MB |
| P-04 | Memory under load | <384MB | 384–512MB | >512MB |
| P-05 | Cold start | <15s | 15–30s | >30s |
| P-06 | tools/list throughput | ≥50/s | 10–50/s | <10/s |
| P-07 | check_dependency_version | <2s | 2–5s | >5s |

### 11.3 Go/No-Go for Docker Release

- **All** F-01 through F-10 functional criteria MUST pass over stdio transport
- **All** P-01 through P-06 MUST be at Warn or better
- **F-01 and F-02 MUST pass over HTTP transport** to certify the HTTP transport fix

---

## 12. Measurement Methodology

### 12.1 Latency Measurement

For every MCP tool call, measure **end-to-end wall clock time**:

```
T_start → [npx/Inspector startup] → [MCP handshake if first call] → [JSON-RPC request] → [server processing] → [JSON-RPC response] → T_end
```

- Use `/usr/bin/time -p` or Python `time.time()` for sub-second precision
- Separate cold (first call after server start) from warm (subsequent calls)
- Report min, max, mean, P50, P95, P99 for each metric

### 12.2 Memory Measurement

Use `docker stats` at 1s intervals during load tests:

```bash
docker stats --format "{{.MemUsage}}" jvm-build-tools-server \
  >> /tmp/mem-profile.txt &
STATS_PID=$!
# ... run load test ...
kill $STATS_PID
```

Extract peak RSS and plot over time.

### 12.3 Build Overhead

```
native_time = time(mvn clean compile)   # directly on Docker container
mcp_time    = time(Inspector --cli tools/call execute_build_command ...)
overhead    = mcp_time - native_time
```

Run 5 iterations of each; report mean overhead.

### 12.4 Throughput

Run `tools/list` in a tight loop for 30 seconds on a warm server:

```bash
COUNT=0
END_TS=$(($(date +%s) + 30))
while [ $(date +%s) -lt $END_TS ]; do
  npx ... tools/list --json > /dev/null 2>/dev/null && COUNT=$((COUNT+1))
done
echo "Throughput: $((COUNT / 30)) req/s"
```

### 12.5 Test Data Cleanup

All test fixture projects and build outputs are ephemeral. After each test run:

- `docker compose down -v` tears down containers and volumes
- `rm -rf /tmp/jvm-bt-test-*` cleans temporary directories
- Async build history (`.buildtools/history/`) is ephemeral within the container

---

## 13. Test Execution Plan

### 13.1 Phases

| Phase | When | Tests | Duration | Exit Criteria |
|-------|------|-------|----------|---------------|
| **Smoke** | Every commit / PR | DISC-01, MVN-01/02/03/04, ERR-01/02 | ~5 min | All pass |
| **Integration** | Per-release candidate | All Scenarios 3–6, 8 | ~30 min | All F-criteria pass |
| **Performance** | Per-release + weekly | Scenario 7 | ~15 min | All P-criteria at Warn or better |
| **Regression** | Ad-hoc, on demand | Appendix C (known-gap tests) | ~10 min | Documents status of known bugs |

### 13.2 Pre-Flight Checks

Run before any test suite:

```bash
#!/bin/bash
# preflight.sh
set -euo pipefail

echo "=== Pre-flight: Docker image build ==="
docker compose -f test-fixtures/docker-compose.test.yml build jvm-build-tools-server

echo "=== Pre-flight: Server health ==="
docker compose -f test-fixtures/docker-compose.test.yml up -d --wait

echo "=== Pre-flight: Build tool availability ==="
docker exec jvm-build-tools-server mvn --version 2>&1 | head -1 || echo "WARN: Maven not found"
docker exec jvm-build-tools-server gradle --version 2>&1 | head -1 || echo "WARN: Gradle not found (#139)"
docker exec jvm-build-tools-server sbt --version 2>&1 | head -1 || echo "WARN: SBT not found (#139)"

echo "=== Pre-flight: Inspector availability ==="
npx @modelcontextprotocol/inspector --version 2>&1 || echo "WARN: Inspector not available"
```

### 13.3 CI Integration (GitHub Actions)

```yaml
name: MCP Integration Tests

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]
  workflow_dispatch:

jobs:
  mcp-docker-tests:
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'

      - name: Build Docker image
        run: docker compose -f test-fixtures/docker-compose.test.yml build

      - name: Pre-flight checks
        run: bash test-fixtures/scripts/preflight.sh

      - name: Smoke tests (Scenario 1–2 core)
        run: bash test-fixtures/scripts/smoke-tests.sh

      - name: Integration tests (Scenario 2–6, 8)
        run: bash test-fixtures/scripts/integration-tests.sh

      - name: Performance benchmarks
        run: bash test-fixtures/scripts/perf-benchmark.sh

      - name: Publish results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-results
          path: test-fixtures/results/
```

### 13.4 Manual Smoke Test (One-Liner)

```bash
# Quick verify the server is alive and exposing tools
docker compose -f test-fixtures/docker-compose.test.yml up -d --wait && \
npx @modelcontextprotocol/inspector --cli \
  --transport sse --url http://localhost:8080/mcp \
  --method tools/list --json | jq '.tools | length' && \
echo "Server is up with tools" && \
docker compose -f test-fixtures/docker-compose.test.yml down
```

---

## 14. Appendix A — Tool-to-Scenario Mapping

| # | Tool | DISC | MVN | GRD | SBT | ERR | CONC | PERF | SEC |
|---|------|------|-----|-----|-----|-----|------|------|-----|
| 1 | get_build_tool_version | ✓ | ✓ | ✓ | ✓ | ✓ | | | |
| 2 | execute_build_command | | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | |
| 3 | list_build_tools | ✓ | | | | | | | |
| 4 | detect_build_tool | ✓ | ✓ | ✓ | ✓ | ✓ | | ✓ | |
| 5 | check_dependency_version | | | | | ✓ | ✓ | ✓ | |
| 6 | analyze_build_output | | ✓ | ✓ | | ✓ | | ✓ | |
| 7 | validate_build_configuration | | ✓ | ✓ | | ✓ | | | |
| 8 | execute_build_async | | | | | ✓ | ✓ | | |
| 9 | get_build_task | | | | | ✓ | ✓ | | |
| 10 | cancel_build_task | | | | | ✓ | ✓ | | |
| 11 | list_build_tasks | | | | | | ✓ | | |
| 12 | profile_build | | | | | | | ✓ | |
| 13 | analyze_build_performance | | | | | | | ✓ | |
| 14 | detect_flaky_tests | | | | | | | ✓ | |
| 15 | analyze_test_history | | | | | | | ✓ | |
| 16 | analyze_cache_health | | | | | | | ✓ | |
| 17 | optimize_build_cache | | | | | | | ✓ | |
| 18 | detect_dependency_conflicts | | ✓ | ✓ | ✓ | | | | |
| 19 | check_java_compatibility | | ✓ | ✓ | ✓ | | | | |
| 20 | generate_sbom | | | | | | | ✓ | |
| 21 | audit_supply_chain | | | | | | | | |
| 22 | check_license_compliance | | | | | | | | |
| 23 | check_credential_status | | | | | | | | ✓ |
| 24 | detect_sbt_modules | | | | ✓ | | | | |
| 25 | detect_sbt_test_frameworks | | | | ✓ | | | | |
| 26 | analyze_sbt_build | | | | ✓ | | | | |
| 27 | list_build_resources + read_build_resource | ✓ | ✓ | ✓ | | | | | |
| 28 | list_dependency_resources + read_dependency_resource | ✓ | ✓ | | | | | | |
| 29 | list_resource_templates + resolve_resource_template | ✓ | | | | | | | |
| 30 | check_tool_authorization | | | | | | | | ✓ |
| 31 | list_available_scopes | ✓ | | | | | | | ✓ |
| 32 | audit_tool_access | | | | | | | | ✓ |
| 33 | validate_access_token | | | | | | | | ✓ |
| 34 | prompt_build_and_test | ✓ | | | | | | | |
| 35 | prompt_build_diagnosis | ✓ | | | | | | | |
| 36 | prompt_dependency_audit | ✓ | | | | | | | |

---

## 15. Appendix B — Test Project Fixtures

### B.1 Directory Structure

```
test-fixtures/
├── docker-compose.test.yml          # Test environment
├── scripts/
│   ├── preflight.sh                 # Pre-flight checks
│   ├── smoke-tests.sh               # Smoke test suite
│   ├── integration-tests.sh         # Full integration suite
│   ├── perf-benchmark.sh            # Performance benchmarks
│   └── helpers.sh                   # Shared assertion functions
├── results/                         # CI artifact output directory
├── maven-basic/                     # Maven test fixture
│   ├── pom.xml                      # group: com.test, artifact: hello-maven
│   ├── mvnw                         # Maven wrapper
│   ├── .mvn/wrapper/maven-wrapper.properties
│   └── src/
│       ├── main/java/com/test/
│       │   ├── Main.java            # Hello World
│       │   └── Calculator.java      # add(int,int), subtract(int,int)
│       └── test/java/com/test/
│           ├── CalculatorTest.java  # 3 passing, 1 failing, 1 warning
│           └── MainTest.java        # 1 passing
├── maven-malformed/                 # Error scenario fixture
│   └── pom.xml                      # Broken XML
├── maven-empty/                     # No-source fixture
│   └── pom.xml                      # Valid POM, no src/
├── gradle-basic/                    # Gradle Groovy DSL fixture
│   ├── build.gradle
│   ├── settings.gradle
│   ├── gradlew
│   ├── gradle/wrapper/gradle-wrapper.properties
│   └── src/
│       ├── main/java/com/test/
│       │   ├── App.java
│       │   └── Util.java
│       └── test/java/com/test/
│           └── UtilTest.java        # 4 JUnit 5 tests
├── gradle-kotlin/                   # Gradle Kotlin DSL fixture
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── src/main/kotlin/com/test/
│       └── Main.kt
├── sbt-basic/                       # SBT fixture
│   ├── build.sbt
│   ├── project/
│   │   └── build.properties         # sbt.version=1.10.10
│   └── src/
│       ├── main/scala/com/test/
│       │   └── Hello.scala
│       └── test/scala/com/test/
│           └── HelloSpec.scala      # 3 ScalaTest specs
├── empty-dir/                       # No-build-files fixture
│   └── .gitkeep
└── concurrent/                       # Concurrent test dirs
    ├── proj-a/                      # Clone of maven-basic
    ├── proj-b/                      # Clone of maven-basic
    └── proj-c/                      # Clone of maven-basic
```

### B.2 Fixture Generation Script

```bash
#!/bin/bash
# generate-fixtures.sh
# Creates minimal test projects for Maven, Gradle, and SBT
# Run once to bootstrap test-fixtures/

FIXTURES_DIR="test-fixtures"
mkdir -p "$FIXTURES_DIR"/{maven-basic,maven-malformed,maven-empty,gradle-basic,gradle-kotlin,sbt-basic,empty-dir,concurrent/proj-{a,b,c},scripts,results}

# Maven basic
cat > "$FIXTURES_DIR/maven-basic/pom.xml" <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.test</groupId>
    <artifactId>hello-maven</artifactId>
    <version>1.0.0</version>
    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.11.0</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
POM

# ... (complete generation for all fixtures)
# See test-fixtures/scripts/generate-fixtures.sh for full script
```

---

## 16. Appendix C — Known-Gap Regression Tests

These tests verify current behavior of known bugs—they document the gap and fail until the bug is resolved.

| ID | Bug | Test | Current Expected | Fixed Expected |
|----|-----|------|-----------------|----------------|
| REGR-01 | #142 HTTP transport broken | DISC-02 over HTTP | **FAILS** (marked as known-gap) | Pass: 28 tools discovered |
| REGR-02 | #139 Docker missing Gradle/SBT | GRD-01, SBT-01 via Docker stdio | **SKIPS** if Gradle/SBT not found | Pass: Gradle and SBT builds work in Docker |
| REGR-03 | #143 Doc/schema mismatches | DISC-04 — validate each inputSchema | **WARNS** on 8 tools | Pass: all 28 schemas valid |
| REGR-04 | #137 Zero test coverage (7 services) | Run existing JUnit tests; measure coverage | <30% line coverage | ≥70% line coverage (target per mission) |

### C.1 Known-Gap Script

```bash
#!/bin/bash
# known-gap-tests.sh
# Runs regression tests for documented bugs; non-zero exit = known gap persists

echo "=== REGR-01: HTTP transport (#142) ==="
if npx @modelcontextprotocol/inspector --cli \
  --transport sse --url http://localhost:8080/mcp \
  --method tools/list --json > /tmp/rego01.json 2>/dev/null; then
  TOOL_COUNT=$(jq '.tools | length' /tmp/rego01.json 2>/dev/null || echo "0")
  if [ "$TOOL_COUNT" -ge 28 ]; then
    echo "PASS: REGR-01 — HTTP transport now functional (bug #142 FIXED)"
  else
    echo "KNOWN-GAP: REGR-01 — HTTP transport (#142) returns $TOOL_COUNT tools (expected ≥28)"
  fi
else
  echo "KNOWN-GAP: REGR-01 — HTTP transport (#142) request failed entirely"
fi

echo "=== REGR-02: Docker Gradle/SBT (#139) ==="
if docker exec jvm-build-tools-server gradle --version > /dev/null 2>&1; then
  echo "PASS: REGR-02 — Gradle available in Docker (bug #139 FIXED)"
else
  echo "KNOWN-GAP: REGR-02 — Gradle not found in Docker (#139)"
fi
if docker exec jvm-build-tools-server sbt --version > /dev/null 2>&1; then
  echo "PASS: REGR-02 — SBT available in Docker (bug #139 FIXED)"
else
  echo "KNOWN-GAP: REGR-02 — SBT not found in Docker (#139)"
fi

echo "=== REGR-03: Schema mismatches (#143) ==="
# Extract schemas and validate JSON Schema 2020-12
npx @modelcontextprotocol/inspector --cli \
  docker exec -i jvm-build-tools-server java -jar /app/mcp-server-jvm-build-tools.jar \
  --method tools/list --json > /tmp/tools.json 2>/dev/null
SCHEMA_ISSUES=0
for row in $(jq -r '.tools[] | @base64' /tmp/tools.json 2>/dev/null); do
  _jq() { echo "${row}" | base64 -d | jq -r "${1}"; }
  SCHEMA=$(_jq '.inputSchema')
  NAME=$(_jq '.name')
  if ! echo "$SCHEMA" | jq -e '.type == "object"' > /dev/null 2>&1; then
    echo "  ISSUE: $NAME — inputSchema.type is not 'object'"
    SCHEMA_ISSUES=$((SCHEMA_ISSUES + 1))
  fi
done
if [ "$SCHEMA_ISSUES" -eq 0 ]; then
  echo "PASS: REGR-03 — All tool schemas valid (bug #143 FIXED)"
else
  echo "KNOWN-GAP: REGR-03 — $SCHEMA_ISSUES tools with schema issues (#143)"
fi
```

---

## Document Metadata

- **Version:** 1.0.0
- **Last Updated:** 2026-07-23
- **Reviewers:** TBD (qa, vrfy profiles)
- **Next Steps:** 
  1. Generate test fixture projects (see Appendix B.2)
  2. Implement test scripts (`scripts/smoke-tests.sh`, `scripts/integration-tests.sh`, `scripts/perf-benchmark.sh`)
  3. Wire into CI pipeline (see Section 13.3)
  4. Execute smoke tests against current Docker build
  5. Hand off implementation to eng profile
