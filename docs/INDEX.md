# mcp-server-jvm-build-tools — Documentation Index

Central documentation hub for the MCP server that gives AI agents hands-on access to JVM build tools (Maven, Gradle, SBT).

## Quick Links

| Document | Purpose |
|----------|---------|
| [README.md](../README.md) | Project overview, features, quick start, client config |
| [CHANGELOG.md](../CHANGELOG.md) | Version history and feature releases |
| [ARCHITECTURE.md](../ARCHITECTURE.md) | Internal architecture, component design, extension guide |
| [TOOLS.md](../TOOLS.md) | Complete MCP tool reference — parameters, schemas, examples |
| [QUICKSTART.md](../QUICKSTART.md) | Five-minute setup guide |
| [CONFIGURATION.md](../CONFIGURATION.md) | Environment variables, build tool paths, transport config |
| [TROUBLESHOOTING.md](../TROUBLESHOOTING.md) | Common issues and solutions |
| [WORKFLOW.md](../WORKFLOW.md) | Branch strategy, PR workflow, release process |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | Contributor guide and coding standards |
| [SECURITY.md](../SECURITY.md) | Security model, threat analysis, hardening |
| [FAQ.md](../FAQ.md) | Frequently asked questions |
| [MCP_INTEGRATION.md](../MCP_INTEGRATION.md) | Client integration guide with configs for 9+ clients |
| [mcp-registry.json](../mcp-registry.json) | MCP Registry manifest for ecosystem discoverability |
| [scripts/launcher.sh](../scripts/launcher.sh) | CLI launcher with auto-discovery |

## MCP Tools Overview (39 tools)

### Build Execution (4)
- `get_build_tool_version` — version query for any registered build tool
- `execute_build_command` — build with auto-detection of Maven/Gradle/SBT
- `list_build_tools` — list all registered tools and commands
- `detect_build_tool` — scan project directory for build markers

### Build Analysis (4)
- `analyze_build_output` — execute build + parse into structured JSON
- `validate_build_configuration` — static analysis of build files
- `profile_build` — execute build with full timing instrumentation
- `analyze_build_performance` — read-only configuration and trend analysis

### Dependency Management (2)
- `check_dependency_version` — Maven Central version lookups
- `detect_dependency_conflicts` — version conflict detection across build files

### Credential Scanning (1)
- `check_credential_status` — read-only Maven/Gradle credential audit

### Java Version Compatibility (1)
- `check_java_compatibility` — validate project config against target Java version

### SBT Project Analysis (3)
- `detect_sbt_modules` — detect SBT sub-modules
- `detect_sbt_test_frameworks` — detect test frameworks (ScalaTest, Specs2, MUnit)
- `analyze_sbt_build` — execute SBT build with structured output

### Prompt Templates (3)
- `prompt_build_and_test` — structured build-and-test workflow template
- `prompt_dependency_audit` — dependency audit workflow template
- `prompt_build_diagnosis` — build failure diagnosis template

### Resource Access (6)
- `list_build_resources` / `read_build_resource` — build configuration resources
- `list_dependency_resources` / `read_dependency_resource` — dependency resources
- `list_resource_templates` — list available resource templates
- `resolve_resource_template` — resolve and apply a resource template

### Tool Authorization (4)
- `check_tool_authorization` — check if a tool is authorized for given permission scopes
- `list_available_scopes` — list all available permission scopes with tool coverage
- `audit_tool_access` — read recent tool invocation audit log entries
- `validate_access_token` — validate an MCP access token and return granted scopes

### Async Build Tasks (4)
- `execute_build_async` — start a long-running build in the background
- `get_build_task` — poll an async build task for status, progress, and output
- `cancel_build_task` — cancel a running async build task
- `list_build_tasks` — list all active and recent async build tasks

### Test Analysis (2)
- `detect_flaky_tests` — run tests N times to detect flaky test methods
- `analyze_test_history` — analyze historical test pass/fail trends

### Build Cache Health (2)
- `analyze_cache_health` — audit caching configuration and effectiveness
- `optimize_build_cache` — generate cache optimization config snippets

### Supply Chain (3)
- `generate_sbom` — generate a CycloneDX or SPDX SBOM for a project
- `audit_supply_chain` — audit dependencies for known CVEs via OSV.dev
- `check_license_compliance` — classify dependency licenses and flag restrictions

### Server Card Endpoints
- `GET /.well-known/mcp-server` — discoverability (Streamable HTTP mode)
- `GET /health` — health check endpoint
- `GET /health/ready` — readiness probe (Kubernetes, Docker)
- `GET /health/live` — liveness probe (container orchestration)

## Transport Modes

- **stdio** — default, works with Claude Desktop, Cursor, Cline, Goose, Continue, Windsurf
- **Streamable HTTP** — web service with health checks, SSE, CORS

## Build Tools

| Tool | Detection File | Execution Method |
|------|---------------|-----------------|
| Maven | pom.xml | MavenInvoker / MavenEmbedder |
| Gradle | build.gradle, build.gradle.kts | ProcessBuilder + wrapper auto-detect |
| SBT | build.sbt | ProcessBuilder |

## Version

Current: 0.1.1-SNAPSHOT (Java 21+, Spring Boot 3.5.14, Spring AI 2.0.0-RC2)
