# One MCP Server for ALL Your JVM Build Tools

[![CI](https://github.com/thepragmatik/mcp-server-jvm-build-tools/actions/workflows/ci.yml/badge.svg)](https://github.com/thepragmatik/mcp-server-jvm-build-tools/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange)](https://adoptium.net/)
[![AI](https://img.shields.io/badge/built_by-AI_%2B_human_review-8A2BE2)]()
[![MCP](https://img.shields.io/badge/MCP-2024--11--05%2B-green)](https://spec.modelcontextprotocol.io)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.14-brightgreen)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring_AI-2.0.0--RC2-blue)](https://spring.io/projects/spring-ai)
[![Transport](https://img.shields.io/badge/transport-stdio%20%7C%20HTTP-lightgrey)]()
[![Smithery](https://smithery.ai/badge/mcp-server-jvm-build-tools)](https://smithery.ai/server/mcp-server-jvm-build-tools)

> **Transparency note:** This project is built with AI assistance — every line is reviewed, tested, and approved by a human. Think of it as pair-programming with a very caffeinated robot that never sleeps. If that's not your thing, we totally get it. If it is — welcome aboard. 🤖 + 🧠


## What's New (June 2026)

- **SBOM Generation & Supply Chain Audit**: generate_sbom, audit_supply_chain, check_license_compliance tools — CycloneDX/SPDX SBOMs for Maven/Gradle/SBT, CVE cross-referencing via OSV.dev, license compliance
- **Test Flakiness Detection & History**: detect_flaky_tests and analyze_test_history tools — multi-run flakiness scoring, Surefire XML parsing, trend analysis, quarantine candidates
- **Build Cache Health Analysis**: analyze_cache_health and optimize_build_cache tools — caching audit for Maven/Gradle/SBT, hit-rate scoring, optimization snippets
- **Build Performance Profiling**: profile_build and analyze_build_performance tools — timing instrumentation, trend analysis, optimization suggestions for 30-60% faster builds
- **Dependency Conflict Detection**: Scan Maven/Gradle/SBT builds for version conflicts with severity classification and resolution plans
- **MCP Server Card**: /.well-known/mcp-server endpoint for discoverability — metadata, capabilities, transports, security posture
- **Streamable HTTP Transport**: Deploy as a web service with health checks alongside stdio
- **SBT Output Parser**: Full structured output parsing for Scala/SBT builds
- **Prompt Templates**: Built-in templates for build and test, dependency audit, and failure diagnosis
- **Resource Exposure**: Navigate build configs, dependencies, and outputs as MCP resources
- **Dependency Intelligence**: Version checking, upgrade classification, build-tool-specific syntax
- **Credential Scanning**: Read-only Maven/Gradle credential status checks (masked, safe)
- **Tool Schema Enhancements**: Enum constraints, shared JSON utilities, improved error handling
- **Container Probes**: /health/ready and /health/live endpoints for Kubernetes and Docker orchestration
- **CLI Launcher**: `scripts/launcher.sh` with auto-discovery of Java, Maven, Gradle, SBT
- **MCP Registry Manifest**: `mcp-registry.json` for ecosystem discoverability
- **MCP Client Integration Guide**: `MCP_INTEGRATION.md` with configs for 9+ clients

## Table of Contents

- [Using with Agentic AI Solutions](#using-with-agentic-ai-solutions)
- [Why Use This Server](#why-this-server)
- [Supported Build Tools](#supported-build-tools)
- [All Available Tools](#all-available-tools)
- [Quick Start](#quick-start)
- [Installation](#installation)
- [Examples](#examples)
- [Security](#security)
- [CI/CD](#cicd)
- [Contributing](#contributing)
- [License](#license)

Run Maven, Gradle, and SBT builds through any MCP-compatible LLM client (Claude Desktop, Goose, Continue, etc.). One server, three build tools, auto-detection of your project type — no switching servers, no manual config per project.

```
You:     "Build and test this project"
Claude:  → execute_build_command(projectDir="...", command="clean test")
Server:  → detects pom.xml → mvn clean test ✓
You:     "Now build the Gradle project next door"
Claude:  → execute_build_command(projectDir=".../other", command="build")
Server:  → detects build.gradle.kts → gradle build ✓
```

## Using with Agentic AI Solutions

### How AI Agents Use Build Tools

AI coding agents are transforming how developers work — they can write code, refactor modules, and manage entire projects autonomously. But most agents stop at the code level. This MCP server gives agents **hands-on access to your build pipeline**: they can compile, test, package, check dependency versions, and detect project types — all without you running a single terminal command.

An agent equipped with build tools can:

- **Detect** which build system a project uses (Maven or Gradle) automatically
- **Compile and test** code it just wrote, catching errors in the same session
- **Parse build failures**, fix the root cause, and retry — a true closed-loop workflow
- **Inspect dependency versions** to suggest upgrades or resolve conflicts
- **Package artifacts** for deployment, all from within the agent conversation

### MCP Client Compatibility

This server uses standard MCP stdio transport and has been verified via automated protocol compliance testing:

| Test | Result |
|---|---|
| `initialize` handshake | ✅ PASS |
| `tools/list` discovery (31 tools) | ✅ PASS |
| `tools/call` get_build_tool_version | ✅ PASS |
| `tools/call` list_build_tools | ✅ PASS |
| `tools/call` detect_build_tool | ✅ PASS |

**Status: MCP stdio compliant.** Compatible with any MCP client supporting stdio transport (Claude Desktop, Cursor, Cline, Windsurf, Goose, Continue, GitHub Copilot agent mode, and others).

### MCP Client Configuration

#### Claude Desktop

Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "/path/to/java",
      "args": [
        "-jar",
        "/path/to/mcp-server-jvm-build-tools.jar"
      ],
      "env": {
        "MAVEN_HOME": "/opt/maven"
      }
    }
  }
}
```

Set `MAVEN_HOME` in your environment or in the `env` block above. Gradle works with the wrapper or a system Gradle on PATH — no extra config needed.

#### Cursor

Create `.cursor/mcp.json` in your project root:

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/mcp-server-jvm-build-tools.jar"
      ],
      "env": {
        "MAVEN_HOME": "/opt/maven"
      }
    }
  }
}
```

Restart Cursor after adding the config. The build tools will appear in the agent's tool palette automatically.

#### Cline / Roo Code

Add to `cline_mcp_settings.json` (VS Code user settings or `.vscode/` in your workspace):

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/mcp-server-jvm-build-tools.jar"
      ],
      "env": {
        "MAVEN_HOME": "/opt/maven"
      }
    }
  }
}
```

Cline and Roo Code share the same MCP configuration format. Both extensions will pick up the server on next activation.

#### Windsurf

Configure in Windsurf's Cascade MCP settings (Settings → Cascade → MCP Servers) or via `.windsurfrules`:

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/mcp-server-jvm-build-tools.jar"
      ],
      "env": {
        "MAVEN_HOME": "/opt/maven"
      }
    }
  }
}
```

The MCP server integrates directly with Cascade's agentic workflows.

#### Goose / Continue

Both Goose and Continue support the standard MCP stdio transport. Use the same command/args configuration as Claude Desktop above.

For **Goose**, add to your Goose MCP config (usually `~/.config/goose/mcp.json` or via the Goose UI).

For **Continue**, add to `~/.continue/config.json` under the `mcpServers` key:

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/mcp-server-jvm-build-tools.jar"],
      "env": {
        "MAVEN_HOME": "/opt/maven"
      }
    }
  }
}
```

### Example: Agent-Driven Build & Fix Workflow

Here's a realistic multi-step workflow showing how an AI agent uses the server to build, detect errors, and fix them — all autonomously:

```
User:   "Build my Maven project and fix any compilation errors"

Agent:  get_build_tool_version(buildToolName="maven")
        → "Apache Maven 3.9.9"

Agent:  detect_build_tool(projectDir="/home/dev/my-app")
        → Detected: maven (found pom.xml)

Agent:  execute_build_command(
          buildToolHome="/opt/maven",
          projectDir="/home/dev/my-app",
          command="clean compile"
        )
        → [ERROR] Compilation failure:
          PaymentService.java:[42,18] cannot find symbol: class PaymentGateway

Agent:  check_dependency_version(
          projectDir="/home/dev/my-app",
          groupId="com.example", artifactId="payment-lib"
        )
        → com.example:payment-lib:2.1.0 (compile scope)

        (Agent reads PaymentService.java, finds missing import)
        (Agent adds: import com.example.payment.PaymentGateway;)

Agent:  execute_build_command(
          buildToolHome="/opt/maven",
          projectDir="/home/dev/my-app",
          command="compile"
        )
        → BUILD SUCCESS

Agent:  "Compilation fixed! The PaymentService.java file was missing an import
        for PaymentGateway from the payment-lib dependency (v2.1.0).
        I've added the import and the project now compiles cleanly."
```

The agent detected the project type, ran the build, parsed the error, checked the dependency version to confirm the class should exist, fixed the source file, and rebuilt — all without you touching a terminal.

## Why Use This Server

- **Multi build tool** — runs Maven, Gradle, and SBT builds through a single server and a single API
- **Auto-detection** — scans for pom.xml, build.gradle(.kts), settings.gradle(.kts), and build.sbt with no manual config
- **Unified API** — same execute_build_command call for every build tool, no context-switching
- **Dependency tools** — check_dependency_version for fast Maven Central lookups
- **Security hardening** — shell injection blocking, dangerous flag blocking, path canonicalization, input validation
- **Gradle wrapper support** — auto-detects and uses gradlew; falls back to system Gradle
- **SBT support** — build.sbt detection and standard SBT lifecycle commands
- **Structured output** — analyze_build_output parses build results into structured data

## Supported Build Tools

| Tool | Detection | Home Required | Fallback |
|---|---|---|---|
| Maven | `pom.xml` | Yes (`buildToolHome` or `MAVEN_HOME`) | — |
| Gradle | `build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts` | No | `gradlew` in project → `gradle` on PATH |
| SBT | `build.sbt` | No | `sbt` on PATH |

| Feature | Maven | Gradle | SBT |
|---|---|---|---|
| Execute builds | ✓ 7 lifecycle phases | ✓ 12 tasks | ✓ |
| Version query | ✓ (embedder, no external process) | ✓ (CLI) | ✓ (CLI) |
| Trust-based execution | ✓ | ✓ | ✓ |
| Shell injection protection | ✓ | ✓ | ✓ |
| Path canonicalization | ✓ | ✓ | ✓ |
| Multi-module projects | ✓ | ✓ (`:subproject:task`) | ✓ |

When multiple build markers exist (e.g., a project with both `pom.xml` and `build.gradle`), the server auto-detects Maven first. You can always explicitly specify which tool to use.

## All Available Tools

### `get_build_tool_version`
Get the installed version of any registered build tool.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `buildToolName` | string | Yes | `"maven"`, `"gradle"`, or `"sbt"` |

### `execute_build_command`
Execute a build command with automatic tool detection.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `buildToolName` | string | No | `"maven"`, `"gradle"`, or `"sbt"`. Omit to auto-detect from project files. |
| `buildToolHome` | string | No | Path to build tool installation. Required for Maven, optional for Gradle/SBT (uses wrapper or PATH). |
| `projectDir` | string | Yes | Path to the project directory containing build files. |
| `command` | string | Yes | Build command. Maven: `clean compile`, `test`, `package`, etc. Gradle: `build`, `test`, `clean`, etc. SBT: `compile`, `test`, `package`, etc. |

**Supported Maven commands:** `clean`, `compile`, `test`, `package`, `install`, `deploy`, `validate`, `dependency:tree` (+ safe flags: `-D`, `-f`, `-P`, `-q`, `-X`, `-T`, `-B`, `-U`, `--batch-mode`, `--non-recursive`)

**Supported Gradle commands:** `clean`, `build`, `test`, `compileJava`, `compileTestJava`, `jar`, `assemble`, `check`, `publishToMavenLocal`, `dependencies`, `projects`, `tasks` (+ safe flags: `-x`, `--exclude-task`, `--parallel`, `--configure-on-demand`, `--build-cache`)

**Supported SBT commands:** `compile`, `test`, `run`, `package`, `clean`, `assembly`, `publishLocal`, `publish`, `update`, `doc`, `console`

### `list_build_tools`
List all registered build tools and their supported commands. Returns formatted string like:
```
maven: clean, compile, test, package, install, deploy, validate
gradle: clean, build, test, compileJava, compileTestJava, jar, assemble, check, publishToMavenLocal, dependencies, projects, tasks
sbt: compile, test, run, package, clean, assembly, publishLocal, publish, update, doc, console
```

### `detect_build_tool`
Auto-detect which build tool a project uses by scanning for build files in the project directory.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory to scan for build files. |

**Returns:** JSON object with detected tools, matched marker files, wrapper availability, and project structure hints. Example: `{"status":"success","projectDir":"/path/to/project","detections":[{"tool":"maven","matchedFiles":["pom.xml"],"wrappers":[],"hints":["POM-based project"]}]}`

**Detection order:** Checks for `pom.xml` first (Maven), then `build.gradle`/`build.gradle.kts` (Gradle), then `build.sbt` (SBT).

### `check_dependency_version`
Look up the latest version of a Maven Central dependency.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `groupId` | string | Yes | Maven group ID (e.g., `com.google.guava`) |
| `artifactId` | string | Yes | Maven artifact ID (e.g., `guava`) |
| `currentVersion` | string | No | Current version to compare against |
| `versionPreference` | string | No | `RELEASE` (default), `LATEST`, `SNAPSHOT`, or `ALL` |
| `projectDir` | string | No | Project directory for build-tool context |

**Example:**
```
check_dependency_version(groupId="com.google.guava", artifactId="guava")
→ {"groupId":"com.google.guava","artifactId":"guava","latestVersion":"33.4.0","stability":"STABLE"}
```


### `analyze_build_output`
Execute a build command and return structured JSON output with parsed test results, compile errors, and warnings instead of raw text. Supports Maven, Gradle, and SBT.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `buildToolName` | string | No | `"maven"` or `"gradle"`. Omit to auto-detect from project directory. |
| `buildToolHome` | string | No | Path to build tool installation. Optional for Gradle (uses wrapper or PATH). |
| `projectDir` | string | Yes | Path to the project directory containing build files. |
| `command` | string | Yes | Build command to execute (e.g., `"clean test"` for Maven, `"test"` for Gradle). |

**Returns:** JSON with `{success, tool, command, duration, testSummary: {total, passed, failed, errors, skipped}, errors: [{file, line, severity, message}], warnings, errorCount, warningCount}`.

### `validate_build_configuration`
Validate build configuration files (pom.xml, build.gradle, build.gradle.kts) for correctness without executing the build. Checks XML well-formedness, required elements, and plugin version consistency.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory containing build files. |

**Returns:** JSON with `{valid, tool, file, issues: [{severity, path, line, message, suggestion}]}`. Use before executing builds to catch configuration errors early.


### `prompt_build_and_test`
Prompt template: guides the LLM through a structured build-and-test workflow with verification steps.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory. |
| `buildToolName` | string | No | Build tool to use. Omit to auto-detect. |

### `prompt_dependency_audit`
Prompt template: guides the LLM through auditing project dependencies for outdated versions, vulnerabilities, and upgrade paths.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory. |
| `buildToolName` | string | No | Build tool to use. Omit to auto-detect. |

### `prompt_build_diagnosis`
Prompt template: guides the LLM through diagnosing build failures by analyzing error output and suggesting fixes.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory. |
| `buildOutput` | string | Yes | The raw build output/error log to diagnose. |
| `buildToolName` | string | No | Build tool that produced the output. |

### `list_build_resources`
List available build resources (build configurations, output files, reports) in the project as MCP resources.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory. |

**Returns:** JSON array of resource URIs with metadata (type, tool, path, lastModified).

### `read_build_resource`
Read the contents of a specific build resource identified by its URI.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uri` | string | Yes | Resource URI (e.g., `build://pom.xml`, `build://build.gradle`). |

**Returns:** The resource content and metadata.

### `list_dependency_resources`
List available dependency resources (dependency trees, version reports, Maven Central metadata) as MCP resources.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory. |
| `buildToolName` | string | No | Build tool to use. Omit to auto-detect. |

**Returns:** JSON array of dependency resource URIs with metadata.

### `read_dependency_resource`
Read the contents of a specific dependency resource identified by its URI.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uri` | string | Yes | Resource URI (e.g., `dependency://tree`). |

**Returns:** The dependency resource content and metadata.

### `list_resource_templates`
List available MCP resource templates that can be resolved for a project. Templates provide reusable patterns for accessing build configuration, dependency metadata, and project structure as structured resources.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory. |

**Returns:** JSON array of template URIs with metadata (name, type, description, requiredParams).

### `resolve_resource_template`
Resolve a resource template into concrete MCP resources. Applies template parameters and returns the resolved resource content.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uri` | string | Yes | Template URI to resolve (e.g., `template://build-config`). |
| `projectDir` | string | Yes | Path to the project directory. |

**Returns:** The resolved resource content and metadata.

### `detect_sbt_modules`
Detect SBT sub-modules in a multi-module SBT project by parsing build.sbt.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the SBT project directory. |

**Returns:** JSON with module names, paths, and inter-module dependencies.

### `detect_sbt_test_frameworks`
Detect which test frameworks are configured in an SBT project (ScalaTest, Specs2, MUnit, etc.).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the SBT project directory. |

**Returns:** JSON with detected test frameworks, versions, and configuration details.

### `analyze_sbt_build`
Execute an SBT build command and return structured JSON output with parsed results, errors, and test summaries.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the SBT project directory. |
| `command` | string | Yes | SBT command to execute (e.g., `compile`, `test`, `package`). |

**Returns:** JSON with `{success, tool, command, duration, output, errors, warnings}`.

### `check_java_compatibility`
Check Java version compatibility of a project. Detects the current Java version from Maven (pom.xml), Gradle (build.gradle/build.gradle.kts), or SBT (build.sbt) configuration, and validates it against the minimum version requirements for common frameworks (Spring Boot, Hibernate, Micronaut, etc.). Catalogs breaking changes for major version upgrades (17→21→25).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory. |
| `targetVersion` | string | No | Target Java version to check against. If omitted, checks against known framework minimums. |

**Returns:** JSON with `{currentVersion, targetVersion, compatible, frameworkRequirements: [{framework, requiredVersion}], breakingChanges: [{from, to, impact, description}], upgradeSteps: [...]}`.

**Example:**
```
check_java_compatibility(projectDir="/home/dev/my-app")
→ {
    "currentVersion": "17",
    "compatible": false,
    "frameworkRequirements": [{"framework":"Spring Boot 3.5","requiredVersion":"21"},...],
    "breakingChanges": [...]
  }
```

### `check_credential_status`
Check build tool credential configuration status for Maven and Gradle. Scans ~/.m2/settings.xml, ~/.gradle/gradle.properties, and environment variables for configured credentials. All sensitive values are masked in the output.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | No | Project directory for build-tool-specific context. |
| `scope` | string | No | `"maven"`, `"gradle"`, or `"all"` (default). Limits which credential sources to check. |

**Returns:** JSON with `{status, summary: {totalServers, totalMirrors, totalProxies, credentialsFound}, maven: {servers, mirrors, proxies, activeProfiles}, gradle: {credentials, repositories}, environmentVariables: {found, count}, gaps: [...], recommendations: [...]}`. All passwords are masked (e.g., `"****xyz"`), never exposed in plaintext.

### `detect_dependency_conflicts`
Scan a JVM project for dependency version conflicts across Maven, Gradle, and SBT build files. Detects duplicate dependencies with different versions, conflicts between direct declarations and dependency management/BOM versions, and transitive override risks.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory containing build files. |
| `scope` | string | No | Build tool scope: `"maven"`, `"gradle"`, `"sbt"`, or `"all"` (default). |

**Returns:** JSON with `{project, filesAnalyzed, conflictCount, conflicts: [{groupId, artifactId, severity, versions: [{version, source, scope}], affectedBuildTool, suggestion}], summary: {errorCount, warningCount, message}, resolutionPlan: {action, steps}}`.

**Severity levels:** `ERROR` for direct-vs-managed version mismatches (resolvable by removing version from direct declaration), `WARNING` for duplicate declarations with different versions.

**Example:**
```
detect_dependency_conflicts(projectDir="/home/dev/my-app")
→ {
    "conflictCount": 2,
    "conflicts": [
      {"groupId":"com.google.guava","artifactId":"guava","severity":"WARNING",
       "versions":[{"version":"31.0-jre","source":"dependency"},
                   {"version":"33.0-jre","source":"dependency"}]},
      {"groupId":"org.slf4j","artifactId":"slf4j-api","severity":"ERROR",
       "versions":[{"version":"1.7.36","source":"dependency"},
                   {"version":"2.0.9","source":"dependencyManagement"}]}
    ]
  }
```

### `profile_build`
Execute a build command with full timing instrumentation. Tracks wall-clock time vs tool-reported time, extracts phase/task breakdown, parses test counts, and persists build history for trend analysis.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `buildToolName` | string | No | `"maven"`, `"gradle"`, or `"sbt"`. Omit to auto-detect. |
| `buildToolHome` | string | No | Path to build tool installation. |
| `projectDir` | string | Yes | Path to the project directory. |
| `command` | string | Yes | Build command to profile. |

**Returns:** JSON with `{success, tool, command, durationSeconds, durationFormatted, phases: [{name, durationSeconds}], testSummary: {total, failed, errors, skipped}, comparison: {trend, recentAvgSeconds, buildsTracked}, suggestions}`.

**History:** Build results persist to `.buildtools/history/` for trend analysis across sessions.

**Example:**
```
profile_build(projectDir="/home/dev/my-app", command="clean test")
→ {
    "tool": "maven", "command": "clean test", "success": true,
    "durationSeconds": 45.3, "durationFormatted": "45s",
    "phases": [{"name":"maven-clean-plugin:clean","durationSeconds":0.5},...],
    "testSummary": {"total":42,"failed":0,"errors":0,"skipped":0},
    "comparison": {"trend":"FASTER","changePercent":-12.5,"buildsTracked":8},
    "suggestions": ["Add -T4 flag to use 4 threads"]
  }
```

### `analyze_build_performance`
Analyze build performance from configuration and historical data without executing a build. Examines build files for missing optimization settings (parallel, caching, daemon) and provides actionable suggestions.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory. |
| `buildToolName` | string | No | Build tool to analyze. Omit to auto-detect. |

**Returns:** JSON with `{tool, projectDir, suggestions, suggestionCount, optimizationPotential: {level, estimatedImprovement}, totalTrackedBuilds}`.

**Analyzes:** Maven fork mode and build cache plugins; Gradle parallel/caching/daemon/configuration-cache settings; SBT Coursier integration; historical build trends.


### `generate_sbom`
Generate a CycloneDX or SPDX Software Bill of Materials for a JVM project. Detects existing SBOM plugins and configuration, discovers pre-generated SBOM files, and provides instructions for manual plugin setup when needed.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory. |
| `format` | string | No | SBOM format: `cyclonedx` (default) or `spdx`. |
| `buildToolName` | string | No | Build tool. Omit to auto-detect. |

**Returns:** JSON with `{success, format, sbom, dependencyCount, pluginDetected, instructions}`. When the SBOM plugin is not configured, returns plugin setup instructions for Maven, Gradle, or SBT.

### `audit_supply_chain`
Audit project dependencies for known vulnerabilities by cross-referencing against OSV.dev (Open Source Vulnerabilities database). Supports batch lookups for efficiency.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory. |
| `buildToolName` | string | No | Build tool. Omit to auto-detect. |

**Returns:** JSON with `{totalDependencies, vulnerabilitiesFound, vulnerabilities: [{dependency, cveId, severity, fixedVersion, summary}], severityBreakdown, remediationRecommendations}`.

### `check_license_compliance`
Check dependency licenses for compliance with organizational policies. Classifies licenses into permissive, copyleft, restricted, and unknown categories.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory. |
| `buildToolName` | string | No | Build tool. Omit to auto-detect. |

**Returns:** JSON with `{totalDependencies, licenseCounts: {permissive, copyleft, restricted, unknown}, dependencies: [{groupId, artifactId, version, license, category}], riskAssessment: {level, summary}}`.

### `detect_flaky_tests`
Run tests multiple times to detect non-deterministic failures. Parses Surefire XML reports to track pass/fail across iterations and computes flakiness scores per test method.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory. |
| `iterations` | integer | No | Number of test runs (default: 5). |
| `testFilter` | string | No | Optional test class/method filter (e.g., `"*ServiceTest"`). |
| `buildToolName` | string | No | Build tool. Omit to auto-detect. |

**Returns:** JSON with `{iterations, flakyTests: [{className, methodName, score, status, passRuns, failRuns, suggestion}], stableTests, summary: {total, flaky, veryFlaky, stable}}`.

**Flakiness scores:** `0` = STABLE (passes every run), `> 0` = FLAKY (fails at least once), `> 0.5` = VERY FLAKY (fails most runs). Suggestions include timing fixes, order-dependency resolution, and thread-safety checks.

### `analyze_test_history`
Analyze historical test pass/fail trends from build history persisted by `profile_build`. Identifies degrading tests and suggests quarantine candidates.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory. |

**Returns:** JSON with `{trends: [{className, methodName, totalRuns, passRate, trend, degradationRisk}], quarantineCandidates: [...], overallTestHealth: {total, stable, degrading}}`.

### `analyze_cache_health`
Audit build caching configuration and effectiveness across Maven, Gradle, and SBT. Checks cache-related settings in build files and properties, parses execution logs for cache hit/miss statistics, and scores the overall caching health.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory. |
| `buildToolName` | string | No | Build tool to analyze. Omit to auto-detect. |

**Returns:** JSON with `{tool, cacheScore, scoreLevel, findings: [{area, status, detail}], cacheHitRate, configurationGaps: [...], rawStats}`.

**Score levels:** `GOOD` (>70%), `ADEQUATE` (>40%), `NEEDS_ATTENTION` (≤40%).

### `optimize_build_cache`
Generate build-tool-specific cache optimization configuration snippets. Provides exact file paths, content to add, and estimated improvement percentages.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `projectDir` | string | Yes | Path to the project directory. |
| `buildToolName` | string | No | Build tool. Omit to auto-detect. |

**Returns:** JSON with `{optimizations: [{area, priority, recommendation, configFile, config, estimatedImprovement}], currentConfig, estimatedTotalImprovement}`.

**Covers:** Maven (mvnd, build cache extensions, parallel builds), Gradle (caching, parallel, daemon, configuration cache), SBT (Coursier, parallel execution, incremental compilation, turbo mode).

### Server Card Endpoint
When running in Streamable HTTP mode, the server exposes discoverability endpoints:

- `GET /.well-known/mcp-server` — MCP server metadata (name, version, capabilities, transports, features, security posture, registry info)
- `GET /health` — Health check (`{"status":"UP","version":"0.1.1-SNAPSHOT","transport":"streamable-http"}`)

Compatible with the MCP Server Card Working Group proposal and MCP Registry discoverability mechanisms.

## Quick Start

1. **Build the JAR:**
   ```bash
   git clone https://github.com/thepragmatik/mcp-server-jvm-build-tools.git
   cd mcp-server-jvm-build-tools
   mvn clean package -DskipTests
   ```

2. **Configure your MCP client** — see [Using with Agentic AI Solutions](#using-with-agentic-ai-solutions) above for client-specific configuration examples, or [Installation](#installation) below.

3. **Use the launcher script** (recommended):
   ```bash
   ./scripts/launcher.sh              # stdio mode (default)
   ./scripts/launcher.sh --http       # Streamable HTTP mode
   ./scripts/launcher.sh --help       # show options
   ```
   The launcher auto-discovers Java, Maven, Gradle, and SBT on your system.

4. **Start building:**
   ```
   Get Maven version → get_build_tool_version("maven")
   Compile my project → execute_build_command(projectDir="/path/to/project", command="clean compile")
   Build Gradle project → execute_build_command(projectDir="/path/to/gradle-project", command="build")
   Detect project type → detect_build_tool(projectDir="/path/to/project")
   Check dependency      → check_dependency_version(groupId="com.example", artifactId="lib")
   ```

## Installation

### Prerequisites

- Java 21 or later
- Apache Maven (to build the JAR)
- An MCP-compatible client: Claude Desktop, Goose, Continue, Cursor, Cline, Windsurf, etc.

### Option 1: JAR + Claude Desktop

Build the JAR:
```bash
git clone https://github.com/thepragmatik/mcp-server-jvm-build-tools.git
cd mcp-server-jvm-build-tools
mvn clean package -DskipTests
# JAR is at target/mcp-server-jvm-build-tools.jar
```

See [MCP Client Configuration](#mcp-client-configuration) above for Claude Desktop and other client-specific setup instructions.

For a comprehensive integration guide covering all supported MCP clients with exact configuration snippets and troubleshooting, see [MCP_INTEGRATION.md](MCP_INTEGRATION.md). For MCP Registry discoverability, see [mcp-registry.json](mcp-registry.json).

### Option 2: Docker

```bash
docker build -t mcp-server-jvm-build-tools .
docker run -i --rm \
  -v /path/to/your/projects:/projects \
  -v /opt/maven:/opt/maven \
  -e MAVEN_HOME=/opt/maven \
  mcp-server-jvm-build-tools
```

The image includes Maven out of the box. Mount your project directories and Maven installation as volumes.

### Option 3: Other MCP Clients (Goose, Continue, Cursor, Cline, Windsurf)

Any MCP client that supports stdio transport. See [MCP Client Configuration](#mcp-client-configuration) for detailed setup instructions for each supported client.

## Examples

### Example 1: Maven — Build and Test

```
User:   "Build my project and run the tests"
LLM:    get_build_tool_version(buildToolName="maven")
        → "Apache Maven 3.9.9 ..."

LLM:    execute_build_command(
          buildToolHome="/opt/maven",
          projectDir="/home/me/my-app",
          command="clean test"
        )
        → [BUILD SUCCESS] Tests run: 42, Failures: 0
```

### Example 2: Gradle with Wrapper

```
User:   "Compile the Gradle project"
LLM:    execute_build_command(
          projectDir="/home/me/gradle-app",
          command="compileJava"
        )
        → Server auto-detects build.gradle.kts
        → Uses ./gradlew wrapper
        → BUILD SUCCESSFUL
```

### Example 3: Auto-Detection Across Projects

```
User:   "Build all my projects"
LLM:    list_build_tools()
        → maven: clean, compile, test, ...
           gradle: clean, build, test, ...
           sbt: compile, test, package, ...

LLM:    execute_build_command(projectDir="/home/me/maven-app", command="package")
        → detects pom.xml → uses Maven

LLM:    execute_build_command(projectDir="/home/me/gradle-app", command="build")
        → detects build.gradle → uses Gradle
```

### Example 4: Maven with Custom Flags

```
User:   "Install skipping tests, running 4 threads"
LLM:    execute_build_command(
          buildToolName="maven",
          buildToolHome="/opt/maven",
          projectDir="/home/me/my-app",
          command="clean install -DskipTests -T4"
        )
```

### Example 5: Dependency Version Check

```
User:   "What version of Guava is this project using?"
LLM:    detect_build_tool(projectDir="/home/me/my-app")
        → maven (pom.xml found)

LLM:    check_dependency_version(
          projectDir="/home/me/my-app",
          groupId="com.google.guava", artifactId="guava"
        )
        → com.google.guava:guava:33.3.1-jre
```

## Security

The server enforces multiple layers of defense:

| Layer | What It Protects Against |
|---|---|
| **Command allowlist** | Only predefined build tasks execute. Unknown commands rejected before process spawn (8 Maven phases/plugins, 12 Gradle tasks, 11 SBT tasks). |
| **Shell metacharacter blocking** | Attempts at command chaining (`&&`, `||`, `;`), piping (`|`), command substitution (`$()`, backticks), and redirection (`>`, `<`) are rejected. |
| **Dangerous flag blocking** | Gradle flags that enable arbitrary code execution (`--init-script`/`-I`, `--build-file`/`-b`, `--project-dir`/`-p`, `--include-build`, `--system-prop`, `-D`) are blocked. |
| **Path canonicalization** | All paths are resolved via `toRealPath()` to prevent directory traversal (`../../etc/passwd`). |
| **Input validation** | Commands are length-limited (500 chars). Non-existent paths are rejected before execution. |
| **Process isolation** | Maven builds use `MavenInvoker` (out-of-process). Gradle builds use `ProcessBuilder` with `--no-daemon`. |

**What the server does NOT restrict:** Shell injection attacks. The server trusts the LLM operator to use build tools appropriately (e.g., `mvn clean` is allowed). It defends against malicious input injection, not against intentional build operations.

**Tested against:** Shell injection (`&&`, `|`, `;`, `$()`, backticks), path traversal (`../`), blocked plugin goals (`exec:exec`), Unicode/zero-width attacks, null-byte injection, denial-of-service via extremely long inputs.

307+ tests covering security, functionality, and integration. See `MavenSecurityTest.java`, `MavenInvokerTest.java`, `GradleServiceTest.java`, `SbtBuildToolTest.java`, `DependencyServiceTest.java`, `BuildOutputParserTest.java`, `BuildConfigurationValidationTest.java`, and `BuildConfigValidatorTest.java`.

## CI/CD

Every PR runs on 3 JDK versions (21, 23, 25) with test coverage reporting (48% instruction, 33% branch, 46% line). CI file: `.github/workflows/ci.yml`.

## Contributing

Use GitHub Issues. See [CONTRIBUTING.md](CONTRIBUTING.md) for the full contributor guide, [WORKFLOW.md](WORKFLOW.md) for the development workflow and branch strategy (`feat/*`, `fix/*` → staging → main), and [ARCHITECTURE.md](ARCHITECTURE.md) for the internal architecture and extension guide.

## License

Apache License 2.0. See [LICENSE](LICENSE).

---

**Repository:** [github.com/thepragmatik/mcp-server-jvm-build-tools](https://github.com/thepragmatik/mcp-server-jvm-build-tools)
**Built with:** Spring Boot 3.5.14, Spring AI 2.0.0-RC2, MCP SDK 2.0.0-RC1 (bundled), Maven Embedder 3.9.9