# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- Upgraded Spring Boot from 3.4.x to 3.5.14
- Upgraded Spring AI from 1.0.0-M6 to 2.0.0-RC2
- Upgraded Java baseline from 17 to 21
- Upgraded dependency versions (Maven Embedder 3.9.9, Maven Shared Invoker 3.3.0)

### Added
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
- 270+ tests across security, functionality, and integration
- 22 MCP tools total: build execution, version queries, output analysis, dependency management, credential scanning, conflict detection, project analysis, resource exposure, and prompt templates

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
