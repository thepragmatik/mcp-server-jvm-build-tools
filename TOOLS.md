# Tools Reference — mcp-server-jvm-build-tools

Complete reference for all 24 MCP tools exposed by the server. Each tool is an `@Tool`-annotated method on a Spring service bean, automatically discovered by Spring AI's `MethodToolCallbackProvider` and exposed via MCP stdio transport.

## Table of Contents

- [get_build_tool_version](#get_build_tool_version)
- [execute_build_command](#execute_build_command)
- [list_build_tools](#list_build_tools)
- [detect_build_tool](#detect_build_tool)
- [check_dependency_version](#check_dependency_version)
- [analyze_build_output](#analyze_build_output)
- [validate_build_configuration](#validate_build_configuration)
- [check_credential_status](#check_credential_status)
- [detect_dependency_conflicts](#detect_dependency_conflicts)
- [detect_sbt_modules](#detect_sbt_modules)
- [detect_sbt_test_frameworks](#detect_sbt_test_frameworks)
- [analyze_sbt_build](#analyze_sbt_build)
- [profile_build / analyze_build_performance](#profile_build)
- [check_java_compatibility](#check_java_compatibility)
- [list_resource_templates / resolve_resource_template](#list_resource_templates)
- [prompt_build_and_test](#prompt_build_and_test)
- [prompt_dependency_audit](#prompt_dependency_audit)
- [prompt_build_diagnosis](#prompt_build_diagnosis)
- [list_build_resources / read_build_resource](#list_build_resources)
- [list_dependency_resources / read_dependency_resource](#list_dependency_resources)
- [Server Card (.well-known)](#server-card)
- [Error Handling](#error-handling)
- [Security Notes](#security-notes)

---

## get_build_tool_version

Get the installed version of any registered build tool.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `buildToolName` | string | Yes | One of: `"maven"`, `"gradle"`, `"sbt"` |

**Returns:** String — version information from the build tool.

**Example:**
```
Request:  get_build_tool_version(buildToolName="maven")
Response: "Apache Maven 3.9.9 (8e8579a9e76f...)
Maven home: /opt/maven
Java version: 21.0.6..."
```

**Implementation:**
- Maven: Uses MavenEmbedder in-process (fast, no external process)
- Gradle: Shells out to `gradle --version --no-daemon`
- SBT: Shells out to `sbt --no-colors --version`

---

## execute_build_command

Execute a build command with automatic tool detection.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `buildToolName` | string | No | `"maven"`, `"gradle"`, or `"sbt"`. Omit to auto-detect from project files. |
| `buildToolHome` | string | No | Path to build tool installation. Required for Maven, optional for Gradle/SBT. |
| `projectDir` | string | Yes | Path to the project directory containing build files. |
| `command` | string | Yes | Build command to execute. |

**Supported Commands by Tool:**

| Maven | Gradle | SBT |
|-------|--------|-----|
| `clean` | `clean` | `compile` |
| `compile` | `build` | `test` |
| `test` | `test` | `run` |
| `package` | `compileJava` | `package` |
| `install` | `compileTestJava` | `clean` |
| `deploy` | `jar` | `assembly` |
| `validate` | `assemble` | `publishLocal` |
| `dependency:tree` | `check` | `publish` |
| | `publishToMavenLocal` | `update` |
| | `dependencies` | `doc` |
| | `projects` | `console` |
| | `tasks` | |

**Safe Flags:**

| Maven | Gradle | SBT |
|-------|--------|-----|
| `-Dproperty=value` | `-x task` | `--no-colors` (auto) |
| `-f file` | `--exclude-task` | |
| `-P profile` | `--parallel` | |
| `-q`, `-X` | `--configure-on-demand` | |
| `-T threads` | `--build-cache` | |
| `-B`, `-U` | | |
| `--batch-mode` | | |
| `--non-recursive` | | |

**Returns:** String — raw stdout from the build tool.

**Examples:**
```
# Auto-detect and compile
Request:  execute_build_command(projectDir="/path/to/project", command="clean compile")
Response: "[INFO] BUILD SUCCESS"

# Explicit Gradle with wrapper
Request:  execute_build_command(buildToolName="gradle", projectDir="/path/to/project", command="build")
Response: "BUILD SUCCESSFUL in 3s"

# Maven with custom flags
Request:  execute_build_command(buildToolName="maven", buildToolHome="/opt/maven",
           projectDir="/path/to/project", command="clean install -DskipTests -T4")
Response: "[INFO] BUILD SUCCESS"

# SBT chained tasks
Request:  execute_build_command(projectDir="/path/to/project", command="clean compile test")
Response: "[success] Total time: 8 s"
```

---

## list_build_tools

List all registered build tools and their supported commands.

**Parameters:** None

**Returns:** String — newline-separated list of tools and commands.

**Example:**
```
Request:  list_build_tools()
Response: "maven: clean, compile, test, package, install, deploy, validate
           gradle: clean, build, test, compileJava, compileTestJava, jar, assemble, check, publishToMavenLocal, dependencies, projects, tasks
           sbt: compile, test, run, package, clean, assembly, publishLocal, publish, update, doc, console"
```

---

## detect_build_tool

Auto-detect which build tool(s) a project uses by scanning for build files.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `projectDir` | string | Yes | Path to the project directory to scan. |

**Returns:** JSON object with detected tools, matched files, wrappers, and hints.

**JSON Schema:**
```json
{
  "projectDir": "/path/to/project",
  "status": "success",
  "detections": [
    {
      "tool": "maven",
      "detected": true,
      "matchedFiles": ["pom.xml"],
      "wrappers": ["mvnw"],
      "hints": ["POM-based project", "standard Maven layout detected"]
    },
    {
      "tool": "gradle",
      "detected": false
    },
    {
      "tool": "sbt",
      "detected": false
    }
  ],
  "detectedTools": ["maven"],
  "toolCount": 1
}
```

**Detection Order:** Maven (pom.xml) → Gradle (build.gradle/.kts, settings.gradle/.kts) → SBT (build.sbt). When multiple tools are detected, Maven is prioritized for auto-detection.

**Detection Hints:**

| Tool | Hints Provided |
|------|---------------|
| Maven | POM-based project, standard Maven layout, wrapper available |
| Gradle | Kotlin DSL / Groovy DSL project, version catalog detected, wrapper available |
| SBT | SBT project, SBT version pinned, SBT plugins configured |

**Examples:**
```
# Maven project
Request:  detect_build_tool(projectDir="/home/me/maven-app")
Response: {"status":"success","detectedTools":["maven"],"toolCount":1,...}

# Gradle project with wrapper
Request:  detect_build_tool(projectDir="/home/me/gradle-app")
Response: {"status":"success","detectedTools":["gradle"],"toolCount":1,
           "detections":[{"tool":"gradle","detected":true,
           "matchedFiles":["build.gradle.kts","settings.gradle.kts"],
           "wrappers":["gradlew"],"hints":["Kotlin DSL project"]}]}

# Hybrid project (both pom.xml and build.gradle)
Request:  detect_build_tool(projectDir="/home/me/hybrid-app")
Response: {"status":"success","detectedTools":["maven","gradle"],"toolCount":2,
           "warning":"Multiple build tools detected. Maven is prioritized."}

# Non-JVM project
Request:  detect_build_tool(projectDir="/home/me/python-app")
Response: {"status":"success","detectedTools":[],"toolCount":0,
           "warning":"No build tool markers found."}
```

---

## check_dependency_version

Look up the latest version of a Maven Central dependency.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `groupId` | string | Yes | Maven group ID (e.g., `"com.google.guava"`) |
| `artifactId` | string | Yes | Maven artifact ID (e.g., `"guava"`) |
| `currentVersion` | string | No | Current version to compare against |
| `versionPreference` | string | No | `"RELEASE"` (default), `"LATEST"`, `"SNAPSHOT"`, or `"ALL"` |
| `projectDir` | string | No | Project directory for build-tool context |

**Returns:** JSON object with version info, stability classification, and upgrade guidance.

**JSON Schema (with currentVersion):**
```json
{
  "groupId": "com.google.guava",
  "artifactId": "guava",
  "status": "success",
  "latestVersion": "33.4.0",
  "releaseVersion": "33.4.0",
  "lastUpdated": "20250215000000",
  "currentVersion": "32.1.0",
  "upgradeAvailable": true,
  "upgradeType": "MINOR",
  "recommended": true
}
```

**JSON Schema (with projectDir):**
```json
{
  "groupId": "org.springframework.boot",
  "artifactId": "spring-boot-starter-web",
  "status": "success",
  "latestVersion": "3.5.14",
  "detectedBuildTool": "maven",
  "dependencySyntax": {
    "maven": "<dependency>\n  <groupId>org.springframework.boot</groupId>\n  <artifactId>spring-boot-starter-web</artifactId>\n  <version>3.5.14</version>\n</dependency>"
  }
}
```

**Stability Classification:**

| Classification | Examples |
|---------------|----------|
| STABLE | `3.5.14`, `2.7.0` |
| RC | `3.0.0-RC1`, `2.5.0-rc2` |
| MILESTONE | `3.0.0-M1`, `2.6.0-m2` |
| BETA | `3.0.0-beta1`, `2.0.0-b1` |
| ALPHA | `3.0.0-alpha1`, `1.0.0-a1` |
| SNAPSHOT | `3.5.15-SNAPSHOT` |

**Upgrade Type Computation:**
- **MAJOR**: First version segment differs (1.x → 2.x)
- **MINOR**: Second version segment differs (2.3.x → 2.4.x)
- **PATCH**: Third version segment differs (2.3.1 → 2.3.2)

**Examples:**
```
# Basic version lookup
Request:  check_dependency_version(groupId="com.google.guava", artifactId="guava")
Response: {"groupId":"com.google.guava","artifactId":"guava","latestVersion":"33.4.0","stability":"STABLE"}

# With current version comparison
Request:  check_dependency_version(groupId="com.google.guava", artifactId="guava", currentVersion="20.0")
Response: {...,"currentVersion":"20.0","upgradeAvailable":true,"upgradeType":"MAJOR"}

# With project context (auto-detects build tool for correct syntax)
Request:  check_dependency_version(groupId="org.slf4j", artifactId="slf4j-api",
           projectDir="/home/me/gradle-app")
Response: {...,"detectedBuildTool":"gradle","dependencySyntax":{"gradle":"implementation('org.slf4j:slf4j-api:2.0.17')"}}
```

---

## analyze_build_output

Execute a build and return structured JSON with parsed test results, errors, and warnings. Execute a build and return structured JSON with parsed test results, errors, and warnings.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `buildToolName` | string | No | `"maven"` or `"gradle"`. Omit to auto-detect. |
| `buildToolHome` | string | No | Path to build tool installation. Optional for Gradle. |
| `projectDir` | string | Yes | Path to the project directory. |
| `command` | string | Yes | Build command (e.g., `"clean test"`, `"test"`) |

**Returns:** JSON object with structured build results.

**JSON Schema:**
```json
{
  "success": true,
  "tool": "maven",
  "command": "clean test",
  "duration": "12.345s",
  "testSummary": {
    "total": 42,
    "passed": 40,
    "failed": 1,
    "errors": 1,
    "skipped": 0
  },
  "errors": [
    {
      "file": "PaymentService.java",
      "line": 42,
      "severity": "ERROR",
      "message": "cannot find symbol: class PaymentGateway"
    }
  ],
  "warnings": [
    "[WARNING] Using platform encoding (UTF-8) to copy filtered resources"
  ],
  "errorCount": 1,
  "warningCount": 1
}
```

**Parsers:**
> **Note:** SBT output parsing is supported via SbtOutputParser (integrated in BuildToolsService). SBT builds return structured JSON output similar to Maven and Gradle.

- **MavenOutputParser**: Extracts BUILD SUCCESS/FAILURE, test counts (Tests run/Failures/Errors/Skipped), compile errors with file:line, warnings
- **GradleOutputParser**: Extracts BUILD SUCCESSFUL/FAILED, test summaries, error references, warnings

**Examples:**
```
# Maven test with structured output
Request:  analyze_build_output(buildToolName="maven", buildToolHome="/opt/maven",
           projectDir="/home/me/app", command="clean test")
Response: {"success":true,"tool":"maven","command":"clean test",
           "testSummary":{"total":15,"passed":15,"failed":0,"errors":0,"skipped":0},
           "errorCount":0,"warningCount":0}

# Gradle build with failures
Request:  analyze_build_output(projectDir="/home/me/app", command="build")
Response: {"success":false,"tool":"gradle","command":"build",
           "errors":[{"file":"src/main/java/Foo.java","line":10,"severity":"ERROR",
           "message":"';' expected"}],"errorCount":1}
```

---

## validate_build_configuration

Validate build configuration files (pom.xml, build.gradle, build.gradle.kts) for correctness without executing the build.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `projectDir` | string | Yes | Path to the project directory. |

**Returns:** JSON object with validation results.

**JSON Schema:**
```json
{
  "valid": false,
  "tool": "maven",
  "projectDir": "/path/to/project",
  "file": "pom.xml",
  "issueCount": 2,
  "issues": [
    {
      "severity": "ERROR",
      "path": "pom.xml",
      "message": "Missing required element: <version>",
      "suggestion": "Add <version> element inside <project>"
    },
    {
      "severity": "WARNING",
      "path": "pom.xml",
      "message": "Duplicate dependency: junit",
      "suggestion": "Remove duplicate <dependency> declaration for junit"
    }
  ]
}
```

**What Gets Validated:**

| Check | Maven (pom.xml) | Gradle (build.gradle/.kts) |
|-------|----------------|---------------------------|
| Required elements | modelVersion, groupId, artifactId, version | plugins block structure |
| Parent POM inheritance | groupId/version can be inherited | N/A |
| XML well-formedness | Opening/closing tag matching | N/A |
| Duplicate dependencies | Detected and reported | N/A |
| Plugin version consistency | Cross-version checks planned | N/A |
| Kotlin DSL structure | N/A | Basic structure checks |
| Buildscript/plugins | N/A | Plugin presence check |
| SBT (build.sbt) | Not validated | Not validated |


**Examples:**
```
# Valid pom.xml
Request:  validate_build_configuration(projectDir="/home/me/valid-app")
Response: {"valid":true,"tool":"maven","projectDir":"/home/me/valid-app","file":"pom.xml",
           "issueCount":0,"issues":[]}

# Invalid pom.xml
Request:  validate_build_configuration(projectDir="/home/me/broken-app")
Response: {"valid":false,"tool":"maven","file":"pom.xml","issueCount":2,
           "issues":[{"severity":"ERROR","path":"pom.xml",
           "message":"Missing required element: <groupId>",
           "suggestion":"Add <groupId> element inside <project>"}]}
```

---

---

## check_credential_status

Scan project and system for configured Maven and Gradle credentials.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `projectDir` | string | No | Project directory for local config scanning |
| `scope` | string | No | `"maven"`, `"gradle"`, or `"all"` (default) |

**Returns:** JSON with Maven settings.xml analysis, Gradle properties analysis, environment variable scan, and credential gaps. All passwords are masked (only last 3 chars shown).

**Security:** Read-only. Never exposes raw passwords or tokens.

**Implementation:** `BuildAuthService.java`

---

## detect_dependency_conflicts

Scan JVM project build files for dependency version conflicts.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `projectDir` | string | Yes | Path to the project directory |
| `scope` | string | No | `"maven"`, `"gradle"`, `"sbt"`, or `"all"` (default) |

**Returns:** JSON with conflicts list, severity classification (ERROR/WARNING), version details, resolution suggestions, and resolution plan.

**Severity levels:**
- `ERROR`: Direct declaration has different version than dependencyManagement — resolve immediately
- `WARNING`: Same dependency declared multiple times with different versions — review

**Implementation:** `DependencyConflictService.java`

**Tests:** `DependencyConflictServiceTest.java` (7 test cases)

---

## detect_sbt_modules

Detect subprojects/modules in a multi-module SBT build. Parses `build.sbt` for `lazy val` or project definitions. Returns module names, base directories, aggregated status, and aggregated module list.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `projectDir` | string | Yes | Project directory path containing `build.sbt` |

**Returns:** JSON object with module detection results.

**JSON Schema:**
```json
{
  "project": "myproject",
  "projectDir": "/path/to/project",
  "buildFile": "build.sbt",
  "multiModule": true,
  "moduleCount": 3,
  "modules": [
    {"name": "core", "baseDirectory": "core", "existsOnDisk": true},
    {"name": "web", "baseDirectory": "web", "existsOnDisk": true},
    {"name": "api", "baseDirectory": "api", "existsOnDisk": true}
  ],
  "hasRootProject": true,
  "aggregatedModules": ["core", "web", "api"]
}
```

**Detection:** Matches `lazy val moduleName = project.in(file("path"))` and `lazy val moduleName = project` patterns. Also checks project/*.scala for additional Build.scala-style definitions.

**Implementation:** `SbtProjectService.java`

---

## detect_sbt_test_frameworks

Detect which test frameworks are configured in an SBT build. Parses `libraryDependencies` in `build.sbt` for known test frameworks (ScalaTest, specs2, MUnit, uTest, ScalaCheck, JUnit, Weaver). Returns detected frameworks with version information.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `projectDir` | string | Yes | Project directory path containing `build.sbt` |

**Returns:** JSON object with detected frameworks and version information.

**JSON Schema:**
```json
{
  "project": "myproject",
  "buildFile": "build.sbt",
  "testFrameworks": [
    {"groupId": "org.scalatest", "artifactId": "scalatest_2.13", "version": "3.2.19", "framework": "scalatest", "scope": "Test"}
  ],
  "frameworkCount": 1,
  "hasExplicitTestConfig": true
}
```

**Settings detection:** Also detects testFrameworks, testOptions, Test / fork, and Test / parallelExecution configuration.

**Implementation:** `SbtProjectService.java`

---

## analyze_sbt_build

Analyze an SBT build.sbt for plugins, Scala version, resolvers, and other structural information. Useful for understanding project configuration without executing SBT. Read-only analysis.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `projectDir` | string | Yes | Project directory path containing `build.sbt` |

**Returns:** JSON object with structural build information.

**JSON Schema:**
```json
{
  "project": "myproject",
  "buildFile": "build.sbt",
  "scalaVersion": "2.13.15",
  "sbtVersion": "1.10.7",
  "organization": "com.example",
  "name": "myproject",
  "detectedPlugins": [
    {"plugin": "sbt-assembly"},
    {"plugin": "sbt-scalafmt"}
  ],
  "customResolvers": ["confluent @ https://packages.confluent.io/maven/"],
  "scalacOptions": ["-Xlint", "-deprecation", "-feature"],
  "crossScalaVersions": ["2.13.15", "3.6.2"]
}
```

**What gets extracted:** Scala version, SBT version from project/build.properties, organization/name, plugins (sbt-assembly, sbt-native-packager, sbt-docker, sbt-release, sbt-scalafmt, sbt-scoverage, sbt-buildinfo, sbt-git, sbt-header, sbt-ci-release), custom resolvers, scalacOptions, crossScalaVersions, test settings.

**Implementation:** `SbtProjectService.java`

---

## profile_build / analyze_build_performance

### profile_build

Execute a build command with timing instrumentation. Returns detailed performance metrics including total duration, phase/task breakdown (for Maven), and comparison against previous builds. Use to identify slow build phases and track build performance over time.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `buildToolName` | string | No | One of `"maven"`, `"gradle"`, `"sbt"`. Omit to auto-detect. |
| `buildToolHome` | string | No | Path to build tool installation. |
| `projectDir` | string | Yes | Path to the project directory. |
| `command` | string | Yes | Build command to profile. |

**Returns:** JSON object with timing metrics, phases, trend comparison, and optimization suggestions.

**JSON Schema:**
```json
{
  "tool": "maven",
  "command": "clean test",
  "success": true,
  "durationSeconds": 12.345,
  "durationFormatted": "12.3s",
  "phaseCount": 8,
  "phases": [
    {"plugin": "maven-clean-plugin", "goal": "clean", "durationSeconds": 0.5, "estimated": true}
  ],
  "testSummary": {"total": 42, "failed": 0, "errors": 0, "skipped": 2},
  "comparison": {
    "recentAvgSeconds": 11.2,
    "earlierAvgSeconds": 14.5,
    "buildsTracked": 10,
    "trend": "FASTER",
    "recentDurations": [11.0, 12.3, 10.8, 11.5, 10.4]
  },
  "suggestions": [
    "Consider enabling build cache for faster clean builds"
  ]
}
```

### analyze_build_performance

Analyze build performance from historical data and configuration. Examines build files and past build profiles to suggest optimizations. Does NOT execute any builds — read-only analysis.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `projectDir` | string | Yes | Path to the project directory. |
| `buildToolName` | string | No | One of `"maven"`, `"gradle"`, `"sbt"`. Omit to auto-detect. |

**Returns:** JSON object with optimization suggestions and estimated improvement potential.

**Implementation:** `BuildPerformanceService.java`

---

## check_java_compatibility

Check if a JVM project and its dependencies are compatible with a target Java version. Scans Maven compiler settings, Gradle source/target compatibility, SBT javacOptions, and known framework minimum Java version requirements. Returns compatibility report with warnings for known breaking changes between versions.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `projectDir` | string | Yes | Path to the project directory. |
| `targetJavaVersion` | string | No | Target Java version (8, 11, 17, 21, 22, 23, 24, 25). If omitted, checks against configured version. |

**Returns:** JSON object with compatibility report, dependency checks, breaking changes, and recommendations.

**Key features:**
- Java lifecycle database (GA/EOL dates, LTS status for Java 8 through 25)
- Breaking change database for 17-to-21-to-25 transitions
- Framework minimum Java version database (Spring Boot, Spring Core, Jakarta Servlet, Hibernate, Tomcat, JUnit, Mockito, Logback)
- Detection from pom.xml, build.gradle/build.gradle.kts, build.sbt

**Implementation:** `JavaVersionService.java`

---

## list_resource_templates / resolve_resource_template

### list_resource_templates

List all available MCP resource templates with their URI patterns and parameter descriptions. Templates follow the `build://{projectName}/...` scheme.

**Parameters:** None

**Returns:** JSON with 5 template definitions (dependencies, config, logs, test-results, summary).

### resolve_resource_template

Resolve a resource template URI by substituting parameter values.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `templateUri` | string | Yes | Template URI pattern (e.g., `"build://{projectName}/dependencies/{buildTool}"`) |
| `paramsJson` | string | Yes | Parameter values as JSON object |

**Implementation:** `ResourceTemplateService.java`

---

## prompt_build_and_test

Get a prompt template to help an LLM guide a user through building and testing a JVM project. Returns a structured step-by-step prompt for compile, test, and analyze workflows.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `projectDir` | string | No | Project directory. If omitted, the prompt will ask. |
| `buildTool` | string | No | Build tool name. If omitted, auto-detection is used. |

**Workflow steps:** Detect build tool, validate configuration, compile, run tests, analyze results with structured JSON, report findings.

**Implementation:** `PromptService.java`

---

## prompt_dependency_audit

Get a prompt template to help an LLM audit and upgrade project dependencies. Returns step-by-step instructions for dependency checking and upgrade recommendations.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `projectDir` | string | No | Project directory. If omitted, the prompt will ask. |

**Workflow steps:** Detect build tool, extract dependencies, check each dependency on Maven Central, classify upgrades (MAJOR/MINOR/PATCH), prioritize (PATCH first), apply one at a time.

**Implementation:** `PromptService.java`

---

## prompt_build_diagnosis

Get a prompt template to help an LLM diagnose and fix build failures. Structured diagnostic workflow for compilation errors, test failures, and configuration issues.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `projectDir` | string | Yes | Project directory path. |
| `failedCommand` | string | No | The failing command. |

**Workflow:** Validate config, detect build tool, run build with analysis, categorize errors (compilation/dependency/test/config), fix highest priority first, iterate.

**Implementation:** `PromptService.java`

---

## list_build_resources / read_build_resource

### list_build_resources

List all available build resources for a project directory. Resource scheme: `build://{project}/{category}` (output, dependencies, config, test-results).

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `projectDir` | string | Yes | Path to the project directory. |

### read_build_resource

Read a specific build resource by URI.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `resourceUri` | string | Yes | Resource URI. |
| `projectDir` | string | Yes | Path to the project directory. |

**Implementation:** `BuildResourceService.java`

---

## list_dependency_resources / read_dependency_resource

### list_dependency_resources

List available dependency resources for a project directory. Returns resource URIs with dependency counts per build tool.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `projectDir` | string | Yes | Path to the project directory. |

### read_dependency_resource

Read dependency information for a specific resource URI. Returns structured dependency data extracted from build files.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `resourceUri` | string | Yes | Resource URI (build://{project}/dependencies/{buildTool}). |
| `projectDir` | string | Yes | Path to the project directory. |

**Implementation:** `DependencyResourceService.java`

---


## Server Card

When running in Streamable HTTP mode, the server exposes discoverability endpoints:

### GET /.well-known/mcp-server

Returns JSON with server metadata: name, version, description, vendor, capabilities, transports, supported build tools, requirements, features, security posture, and registry information.

### GET /health

Returns `{"status":"UP","version":"0.1.1-SNAPSHOT","transport":"streamable-http"}`.

### GET /health/ready

Readiness probe for container orchestration (Kubernetes, Docker). Returns `{"status":"READY","version":"0.1.1-SNAPSHOT","availableBuildTools":["maven","gradle","sbt"]}`.

### GET /health/live

Liveness probe for container orchestration. Returns `{"status":"ALIVE","timestamp":"..."}`.

**Implementation:** `ServerCardController.java`

**Use case:** Enables MCP client auto-discovery and MCP Registry integration without requiring a full MCP protocol connection. Health/ready/liveness endpoints enable Kubernetes pod probes and Docker health checks.


## Error Handling

All tools return structured error responses for common failure modes:

| Error Condition | Response |
|----------------|----------|
| Unknown build tool | `IllegalArgumentException` with available tools list |
| Unknown command | `IllegalArgumentException` with allowed commands list |
| Shell metacharacters | `IllegalArgumentException: "Command contains disallowed characters"` |
| Command too long | `IllegalArgumentException: "Command too long (max 500 characters)"` |
| Path not found | `IllegalArgumentException: "Cannot resolve path"` |
| Not a directory | `IllegalArgumentException: "Project directory is not valid"` |
| Missing buildToolHome (Maven) | `IllegalArgumentException: "Maven requires buildToolHome"` |
| Build failure | Raw error output from the build tool |
| Network error (DependencyService) | JSON: `{"error":true,"message":"Network error..."}` |
| Maven Central 404 | JSON: `{"error":true,"message":"Dependency not found on Maven Central"}` |

---

## Security Notes

All tools enforce the 5-layer security model:

1. **Command Allowlist** — Only predefined build tasks execute
2. **Flag Blocking** — Dangerous Gradle/SBT flags rejected
3. **Safe-Argument Pattern** — Regex blocks shell metacharacters
4. **Input Validation** — 500-char limit, path canonicalization, directory existence
5. **Process Isolation** — Out-of-process Maven, --no-daemon Gradle

For full details, see [SECURITY.md](SECURITY.md).

The server trusts the LLM operator to use build tools appropriately (e.g., `mvn clean` is allowed — it's a build operation, not an attack). It defends against malicious input injection, not against intentional build operations.
