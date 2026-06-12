# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Container Probes**: /health/ready (readiness) and /health/live (liveness) endpoints for Kubernetes and Docker orchestration
- **CLI Launcher Script**: `scripts/launcher.sh` with auto-discovery of Java, Maven, Gradle, SBT installations. Supports `--http` flag for Streamable HTTP mode
- **MCP Registry Manifest**: `mcp-registry.json` for ecosystem discoverability — tools, resources, prompts, transports, runtime requirements
- **MCP Client Integration Guide**: `MCP_INTEGRATION.md` with configuration snippets for 9+ clients (Claude Desktop, Cursor, Cline, Windsurf, Goose, Continue, GitHub Copilot, LangChain, LlamaIndex)
- **PR Conflict Resolution**: Synced `fix/pr-44-merge-conflict` branch with current staging (all 11 services registered)

### Changed
- Enhanced ServerCardController with /health/ready and /health/live endpoints alongside existing /health
- Updated README, TOOLS.md, and docs/INDEX.md to reflect MCP-004 enhancements


### Changed
- Upgraded Spring Boot from 3.4.x to 3.5.14
- Upgraded Spring AI from 1.0.0-M6 to 2.0.0-RC2
- Upgraded Java baseline from 17 to 21
- Upgraded dependency versions (Maven Embedder 3.9.9, Maven Shared Invoker 3.3.0)

### Added
- **Async Build Execution (MCP Tasks)**: 4 new tools — `execute_build_async` (fire-and-forget builds with immediate task handle), `get_build_task` (poll for status, progress, and partial output), `cancel_build_task` (kill running build processes), `list_build_tasks` (list active and recent tasks). Uses virtual threads for concurrent builds. Supports Maven, Gradle, and SBT. Task lifecycle: queued → running → completed/failed/cancelled. Completed tasks retained for 1 hour.
- **Tool Authorization & Audit**: 4 new tools — `check_tool_authorization` (pre-validate tool access against scope permissions), `list_available_scopes` (enumerate scopes with tool coverage), `audit_tool_access` (read OWASP MCP06 audit logs), `validate_access_token` (validate API keys configured via BUILDTOOLS_API_KEY_* env vars). Scope-based permission model with wildcard support and audit logging. Implements MCP Server Authorization specification.
- **Build Performance Profiling**: profile_build tool with timing instrumentation, phase/task breakdown, test count extraction, build history persistence (.buildtools/history/), and trend detection (SLOWER/FASTER/STABLE). analyze_build_performance tool for read-only configuration analysis with optimization suggestions (parallel builds, caching, daemon, mvnd, Coursier, configuration cache).
- **Dependency Conflict Detection**: detect_dependency_conflicts tool for Maven, Gradle, and SBT. Detects duplicate version declarations (WARNING) and direct-vs-dependencyManagement mismatches (ERROR). Resolves Maven property references. Generates structured resolution plans.
- **MCP Server Card**: GET /.well-known/mcp-server endpoint for discoverability (capabilities, transports, features, security posture). GET /health endpoint for Streamable HTTP health checks.
- **Credential Scanning**: check_credential_status tool — read-only scan of Maven settings.xml, Gradle properties, and environment variables with masked credential values.
- Comprehensive documentation suite: ARCHITECTURE.md, CONTRIBUTING.md, WORKFLOW.md, SECURITY.md, CHANGELOG.md, TOOLS.md, QUICKSTART.md
- SBT build tool support (build.sbt detection, 11 SBT lifecycle commands, security hardening)
- Dependency intelligence: check_dependency_version tool with Maven Central REST API integration
- Structured build output: analyze_build_output tool with Maven/Gradle output parsers
- Build configuration validation: validate_build_configuration tool
- detect_build_tool tool with structured JSON output and project structure hints
- Docker support with multi-stage build
- Maven Release Package workflow for tagged releases
- 307 tests across security, functionality, integration, and MCP protocol compliance
- 39 MCP tools total: build execution, version queries, output analysis, dependency management, credential scanning, conflict detection, Java version compatibility, build performance profiling, SBT project analysis, resource exposure, prompt templates, resource template management, async build tasks, tool authorization, and security auditing

### Added
- **SBOM Generation & Supply Chain Audit**: 3 new tools — `generate_sbom` (CycloneDX/SPDX SBOM generation for Maven/Gradle/SBT), `audit_supply_chain` (OSV.dev vulnerability cross-referencing with CVE severity and remediation), `check_license_compliance` (license classification: permissive/copyleft/restricted with risk assessment). Includes CycloneDX plugin detection, pre-existing SBOM discovery, dependency file parsing, and batch vulnerability lookups.
- **Test Flakiness Detection & History**: 2 new tools — `detect_flaky_tests` (multi-run test execution with flakiness scoring: STABLE/FLAKY/VERY_FLAKY, Surefire XML report parsing, fix suggestions for timing/order-dependency/thread-safety issues) and `analyze_test_history` (historical pass/fail trend analysis from `profile_build` history, degrading test identification, quarantine candidate suggestions).
- **Build Cache Health Analysis**: 2 new tools — `analyze_cache_health` (caching configuration audit with hit-rate scoring: GOOD/ADEQUATE/NEEDS_ATTENTION for Maven, Gradle, and SBT with execution log parsing) and `optimize_build_cache` (build-tool-specific optimization config snippets with exact file paths and estimated improvement percentages). Covers Maven mvnd/build-cache-extensions, Gradle config-cache/build-cache/parallel/daemon, and SBT Coursier/parallel-execution/incremental-compilation/turbo-mode.
- **ROADMAP.md**: Research-driven enhancement plan covering MCP 2026-07-28 protocol migration (P0), async build tasks (P1), and completed SBOM/test-flakiness/cache-health features (P2-P4).
- Total MCP tools: 39 (up from 31).

## [0.1.0] - 2025-05-23

### Added
- Initial release: MCP server for Maven and Gradle build tools
- MCP stdio transport with automatic build tool detection
- Seven MCP tools: get_build_tool_version, execute_build_command, list_build_tools, detect_build_tool, check_dependency_version, analyze_build_output, validate_build_configuration
- Multi-build-tool architecture with SPI (BuildTool interface)
- BuildToolProvider registry with auto-detection priority (Maven, Gradle, SBT)
- Security hardening: shell injection blocking, dangerous flag blocking, path canonicalization, command length limits
- Maven support: 7 lifecycle phases via MavenInvoker (out-of-process) and MavenEmbedder (in-process version queries)
- Gradle support: 12 tasks via CLI ProcessBuilder with Gradle wrapper auto-detection
- Client configuration docs for Claude Desktop, Cursor, Cline/Roo Code, Windsurf, Goose, Continue
- CI/CD pipeline on GitHub Actions (JDK 21, 23, 25 matrix, JaCoCo coverage)

[Unreleased]: https://github.com/thepragmatik/mcp-server-jvm-build-tools/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/thepragmatik/mcp-server-jvm-build-tools/releases/tag/v0.1.0
