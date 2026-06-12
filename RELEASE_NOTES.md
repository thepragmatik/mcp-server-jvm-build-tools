# Release Notes — v0.2.0

**Release Date:** June 12, 2026
**Previous Release:** [v0.1.0](https://github.com/thepragmatik/mcp-server-jvm-build-tools/releases/tag/v0.1.0)

---

## Overview

v0.2.0 is a major expansion of the MCP Server for JVM Build Tools. This release grows from 7 tools to 28 MCP tools across 12 services, adds SBT support alongside Maven and Gradle, introduces HTTP transport with SSE, delivers build performance profiling and dependency conflict detection, adds build cache health analysis, test flakiness detection, SBOM generation and supply chain auditing, tool authorization, and MCP tasks for async build execution, and upgrades the foundation to Spring Boot 3.5.14, Spring AI 2.0.0-RC2, and Java 21.

**43 commits** since v0.1.0. **375 tests** across **22 test classes**.

---

## Foundation Upgrade

- **Spring Boot** upgraded from 3.4.x to 3.5.14
- **Spring AI** upgraded from 1.0.0-M6 to 2.0.0-RC2
- **Java baseline** upgraded from 17 to 21
- **Maven Embedder** 3.9.9, **Maven Shared Invoker** 3.3.0
- PRs: [#34](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/34), [#28](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/28)

---

## New Features

### SBT Build Tool Support ([#44](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/44))
- **detect_sbt_modules** — multi-module SBT project detection
- **detect_sbt_test_frameworks** — auto-detect ScalaTest, specs2, MUnit, uTest, ScalaCheck, JUnit, Weaver
- **analyze_sbt_build** — structural analysis of plugins, Scala version, resolvers, settings
- 11 SBT lifecycle commands via execute_build_command with security hardening

### Build Performance Profiling ([#53](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/53))
- **profile_build** — timing-instrumented build execution with phase/task breakdown
- **analyze_build_performance** — read-only configuration analysis with optimization suggestions
- Trend detection: SLOWER / FASTER / STABLE comparison against historical runs

### Dependency Conflict Detection ([#50](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/50))
- **detect_dependency_conflicts** — cross-build-tool dependency conflict analysis
- Detects duplicate version declarations (WARNING) and dependencyManagement mismatches (ERROR)
- Resolves Maven property references, generates structured resolution plans

### Java Version Compatibility Checker ([#56](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/56))
- **check_java_version** — inspect project Java source/target/release configuration
- Reports compatibility status, LTS alignment, and upgrade recommendations

### Credential Management ([#48](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/48))
- **check_credential_status** — read-only credential scan of Maven settings.xml, Gradle properties, env vars
- Masked credential values in output, BuildAuthService for repo auth management

### Dependency Intelligence ([#45](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/45), [#33](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/33))
- **check_dependency_version** — multi-build-tool extraction with Maven Central REST API
- MCP resource exposure for dependency data via list/read_dependency_resource

### Resource Templates ([#46](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/46))
- Parameterized URI templates for MCP resources, dynamic exposure based on project context

### Prompt Templates
- **prompt_build_and_test**, **prompt_dependency_audit**, **prompt_build_diagnosis**
- **list_build_resources / read_build_resource** — structured build resource access

### Streamable HTTP Transport ([#47](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/47))
- SSE streaming support, CORS configuration, TransportLoggingFilter
- TransportConfig for transport mode selection

### MCP Server Card and Discoverability ([#50](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/50))
- GET /.well-known/mcp-server — MCP-004 compliant server card
- GET /health, /health/ready, /health/live — container orchestration probes

### CLI Launcher and Registry
- scripts/launcher.sh — auto-discovers Java, Maven, Gradle, SBT with --http flag
- mcp-registry.json — ecosystem discoverability manifest

### Documentation Suite ([#38](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/38), [#51](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/51))
- ARCHITECTURE.md, CONTRIBUTING.md, WORKFLOW.md, SECURITY.md, TOOLS.md
- QUICKSTART.md, CONFIGURATION.md, FAQ.md, TROUBLESHOOTING.md
- MCP_INTEGRATION.md for 9+ MCP clients, docs/INDEX.md

### Tool Authorization ([#57](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/57))
- **check_tool_authorization** — verify tool access against role-based scopes
- **list_available_scopes** — enumerate all registered authorization scopes
- **audit_tool_access** — audit trail of tool invocations per scope
- Role-based scope enforcement with empty-scope-safe defaults

### MCP Tasks Extension ([#58](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/58))
- **execute_build_async** — non-blocking long-running build execution
- **get_build_task** — poll async task status and results
- **cancel_build_task** — cancel a running async build task
- **list_build_tasks** — list all active and recent build tasks
- Background task queue with progress tracking and configurable concurrency limits

### SBOM Generation & Supply Chain Audit ([#59](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/59))
- **generate_sbom** — CycloneDX-format SBOM from build dependencies
- **audit_supply_chain** — scan dependencies against known vulnerability databases
- **check_license_compliance** — audit dependency licenses against allow/block lists
- Reports with severity ratings, CVE references, and remediation suggestions

### Test Flakiness Detection ([#60](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/60))
- **detect_flaky_tests** — statistical flakiness detection from test history
- **analyze_test_history** — trend analysis of test outcomes over time
- Supports Maven Surefire and Gradle test report XML parsing

### Build Cache Health Analysis ([#61](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/61))
- **analyze_cache_health** — cache hit/miss ratio analysis with optimization guidance
- **optimize_build_cache** — read-only cache configuration recommendations
- Supports both Maven (local + remote cache) and Gradle (build cache, configuration cache)

### Docker Support
- Multi-stage Docker build for containerized deployment

---

## Fixes

- **BuildAuthService registration** ([#49](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/49)) — registered in ToolCallbackProvider
- **Compilation errors** resolved on staging branch
- **JSON Schema enum constraints** added via @Schema(allowableValues) for better MCP client UX

---

## Breaking Changes

- **Java 21 required** (was Java 17). Projects running the server must use JDK 21+.
- **Spring AI 2.0.0-RC2** API changes — migration from FunctionCallback to MethodToolCallbackProvider.

---

## Statistics

| Metric | v0.1.0 | v0.2.0 |
|--------|--------|--------|
| MCP Tools | 7 | 39 |
| Build Systems | Maven, Gradle | Maven, Gradle, SBT |
| Tests | baseline | 375 |
| Documentation Files | 2 | 15+ |
| Java Version | 17 | 21 |
| Spring Boot | 3.4.x | 3.5.14 |
| Spring AI | 1.0.0-M6 | 2.0.0-RC2 |

---

## All PRs in This Release

| PR | Title |
|----|-------|
| [#61](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/61) | feat: build cache health analysis and optimization |
| [#60](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/60) | feat: test flakiness detection and history analysis |
| [#59](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/59) | feat: SBOM generation and supply chain audit |
| [#58](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/58) | feat: MCP tasks extension for async build execution |
| [#57](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/57) | feat: tool authorization |
| [#55](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/55) | chore: release |
| [#56](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/56) | feat: Java version compatibility checker |
| [#54](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/54) | docs: build performance profiling |
| [#53](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/53) | feat: build performance profiling |
| [#51](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/51) | docs: June 2026 features |
| [#50](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/50) | feat: dependency conflicts, server card |
| [#49](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/49) | fix: BuildAuthService registration |
| [#48](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/48) | feat: credential management, JSON Schema |
| [#47](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/47) | feat: streamable HTTP transport |
| [#46](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/46) | feat: resource template service |
| [#45](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/45) | feat: multi-build-tool dependency extraction |
| [#44](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/44) | feat: SBT project analysis tools |
| [#38](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/38) | docs: comprehensive documentation update |
| [#35](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/35) | docs: stack versions |
| [#34](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/34) | feat: upgrade foundation |
| [#33](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/33) | feat: dependency intelligence |
| [#31](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/31) | test: BuildToolProvider unit tests |
| [#28](https://github.com/thepragmatik/mcp-server-jvm-build-tools/pull/28) | feat: upgrade foundation |

---

**Upgrading:** JDK 21+ required. Docker image bundles JDK 21.
