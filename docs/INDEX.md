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

## MCP Tools Overview (24 tools)

### Build Execution
- `get_build_tool_version` — version query for any registered build tool
- `execute_build_command` — build with auto-detection of Maven/Gradle/SBT
- `list_build_tools` — list all registered tools and commands
- `detect_build_tool` — scan project directory for build markers

### Output Analysis
- `analyze_build_output` — execute build + parse into structured JSON

### Configuration Validation
- `validate_build_configuration` — static analysis of build files

### Dependency Management
- `check_dependency_version` — Maven Central version lookups
- `detect_dependency_conflicts` — version conflict detection across build files

### Credential Scanning
- `check_credential_status` — read-only Maven/Gradle credential audit

### Performance Profiling
- `profile_build` — execute build with full timing instrumentation
- `analyze_build_performance` — read-only configuration and trend analysis

### Java Version Compatibility
- `check_java_compatibility` — validate project config against target Java version

### SBT-Specific
- `detect_sbt_modules` — detect SBT sub-modules
- `detect_sbt_test_frameworks` — detect test frameworks (ScalaTest, Specs2, MUnit)
- `analyze_sbt_build` — execute SBT build with structured output

### Prompt Templates
- `prompt_build_and_test` — structured build-and-test workflow template
- `prompt_dependency_audit` — dependency audit workflow template
- `prompt_build_diagnosis` — build failure diagnosis template

### MCP Resources
- `list_build_resources` / `read_build_resource` — build configuration resources
- `list_dependency_resources` / `read_dependency_resource` — dependency resources
- `list_resource_templates` — list available resource templates
- `resolve_resource_template` — resolve and apply a resource template

### Server Card Endpoints
- `GET /.well-known/mcp-server` — discoverability (Streamable HTTP mode)
- `GET /health` — health check endpoint

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
