# CI/CD Flow Interpreter — Design Spec

**Date:** 2026-07-26  
**Target Release:** v1.2.0  
**Status:** Draft  
**Priority:** P2 (HIGH value, LOW-MEDIUM effort)  
**Author:** Architecture profile (Phase 1 research), mcp-server-jvm-build-tools

---

## 1. Overview / Motivation

Developers commonly need to set up CI/CD pipelines for their JVM projects, but writing
multi-platform CI configurations (GitHub Actions, GitLab CI, Jenkins) is error-prone,
especially when the pipeline must account for build-tool-specific nuances (Maven wrapper
vs. Gradle wrapper caching, SBT parallel testing, JDK matrix strategies).

**CI/CD Flow Interpreter** lets an agent describe a pipeline in natural language and
receive a validated, build-tool-aware CI configuration file. This eliminates the
trial-and-error cycle of writing, committing, pushing, and debugging CI YAML.

### Key Design Goals

1. **Natural-language ingress** — "Set up CI that runs tests on every push, builds on PR,
   deploys on tag" → generated YAML.
2. **Build-tool-aware generation** — generated pipelines use the correct commands for
   Maven (`mvn verify`), Gradle (`./gradlew build`), and SBT (`sbt test`).
3. **Validation** — generated YAML is syntax-checked and validated for required fields
   (on trigger, job name, runner, steps).
4. **As MVP: GitHub Actions only** — but the architecture supports adding GitLab CI,
   Jenkins, CircleCI, etc. via a `CicdTarget` interface.

---

## 2. Design Decisions

### Decision 1: Output format — GitHub Actions YAML MVP, extensible

**Chosen: GitHub Actions as the initial (MVP) target, with an interface for future targets.**

| Target | Phase | Priority |
|--------|-------|----------|
| GitHub Actions | v1.2.0 (MVP) | P0 — ~80% of OSS JVM projects use this |
| GitLab CI | v1.3.0 | P1 |
| Jenkinsfile | v1.4.0 | P2 |

**Rationale:** Phase 1 competitive research (`docs/competitive-landscape-july-2026.md`)
shows that 13 new JVM MCP servers have appeared since June 2026, but none offer CI/CD
config generation. This is a clear differentiator for the project's 3-build-tool support.

The interface `CicdTarget` abstracts the generation strategy so new targets can be
added without changing the core interpretation logic.

### Decision 2: Generation only (MVP), generation + validation later

**Chosen: Generation first; validation of existing CI configs added in v1.3.0.**

For v1.2.0, the tool generates a complete CI configuration from scratch. Validation
(read-only analysis of existing configs) extends the tool in a follow-up release.
This keeps the MVP scope tight and avoids the complexity of parsing existing CI YAML
from diverse sources.

### Decision 3: Template-based generation with dynamic parameterization

**Chosen: Hybrid approach — templates for common pipeline shapes, dynamic for custom flows.**

- **Pipeline shapes** (predefined): `ci-push`, `ci-pr`, `ci-release`, `ci-full` (push+PR+release).
- **Templates** encode best practices for each build tool:
  - Maven: `mvn verify` with `actions/setup-java`, wrapper caching, JDK matrix.
  - Gradle: `./gradlew build` with Gradle cache, wrapper validation, JDK matrix.
  - SBT: `sbt test` with Coursier cache, JDK matrix, parallel execution.
- **Custom flows** are assembled dynamically when the description doesn't match a predefined shape.

### Decision 4: Template library in `/cicd/templates/`

Templates are stored as resource files in `src/main/resources/cicd/templates/` and loaded
at startup. They use a simple `{{PLACEHOLDER}}` substitution system (not a full template
engine, to avoid additional dependencies).

### Decision 5: Secret management — placeholder injection

Generated CI configs use `${{ secrets.XXX }}` placeholders for secrets. The tool
documents the required secrets (e.g., `DEPLOY_KEY`, `MAVEN_SETTINGS`, `DOCKER_PASSWORD`)
and the agent or user creates them in the CI provider's UI.

---

## 3. Pipeline Data Model

### 3.1 CicdPipeline — Intermediate Representation

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "CicdPipeline",
  "description": "Intermediate representation of a CI/CD pipeline",
  "type": "object",
  "required": ["name", "target", "on", "jobs"],
  "properties": {
    "name": {
      "type": "string",
      "description": "Pipeline name (e.g., 'CI', 'Build and Test')"
    },
    "target": {
      "type": "string",
      "enum": ["github-actions"],
      "description": "CI/CD platform target"
    },
    "description": {
      "type": "string",
      "description": "Original natural language description"
    },
    "on": {
      "type": "object",
      "description": "Trigger configuration",
      "properties": {
        "push": {
          "type": "object",
          "properties": {
            "branches": {
              "type": "array",
              "items": { "type": "string" },
              "default": ["main"]
            },
            "paths": {
              "type": "array",
              "items": { "type": "string" }
            }
          }
        },
        "pull_request": {
          "type": "object",
          "properties": {
            "branches": {
              "type": "array",
              "items": { "type": "string" }
            }
          }
        },
        "release": {
          "type": "object",
          "properties": {
            "types": {
              "type": "array",
              "items": { "type": "string", "enum": ["created", "published", "released"] }
            }
          }
        },
        "schedule": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "cron": { "type": "string" }
            }
          }
        },
        "workflow_dispatch": {
          "type": "object"
        }
      }
    },
    "env": {
      "type": "object",
      "description": "Environment variables shared across all jobs",
      "additionalProperties": { "type": "string" }
    },
    "defaults": {
      "type": "object",
      "description": "Default settings for all jobs",
      "properties": {
        "shell": { "type": "string", "default": "bash" },
        "working-directory": { "type": "string" }
      }
    },
    "jobs": {
      "type": "array",
      "description": "Ordered list of CI jobs",
      "items": {
        "$ref": "#/$defs/CicdJob"
      }
    },
    "permissions": {
      "type": "object",
      "description": "Workflow-level permissions",
      "properties": {
        "contents": { "type": "string", "enum": ["read", "write", "none"] },
        "issues": { "type": "string", "enum": ["read", "write", "none"] },
        "pull-requests": { "type": "string", "enum": ["read", "write", "none"] },
        "packages": { "type": "string", "enum": ["read", "write", "none"] },
        "id-token": { "type": "string", "enum": ["read", "write", "none"] }
      }
    },
    "requiredSecrets": {
      "type": "array",
      "description": "Secrets that must be configured in the CI provider",
      "items": {
        "type": "object",
        "properties": {
          "name": { "type": "string" },
          "description": { "type": "string" }
        }
      }
    }
  },
  "$defs": {
    "CicdJob": {
      "type": "object",
      "required": ["name", "runsOn", "steps"],
      "properties": {
        "name": { "type": "string" },
        "id": { "type": "string", "description": "Job identifier for the target format" },
        "runsOn": {
          "type": "string",
          "description": "Runner type (e.g., 'ubuntu-latest', 'windows-latest')",
          "default": "ubuntu-latest"
        },
        "needs": {
          "type": "array",
          "items": { "type": "string" },
          "description": "Job dependencies"
        },
        "strategy": {
          "type": "object",
          "properties": {
            "matrix": {
              "type": "object",
              "properties": {
                "java": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "JDK versions (e.g., ['21', '23'])"
                },
                "os": {
                  "type": "array",
                  "items": { "type": "string" }
                }
              }
            },
            "fail-fast": {
              "type": "boolean",
              "default": true
            }
          }
        },
        "steps": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/CicdStep"
          }
        },
        "env": {
          "type": "object",
          "additionalProperties": { "type": "string" }
        },
        "timeoutMinutes": {
          "type": "integer",
          "default": 60
        },
        "condition": {
          "type": "string",
          "description": "Job condition (e.g., 'github.ref == refs/heads/main')"
        }
      }
    },
    "CicdStep": {
      "type": "object",
      "required": ["name"],
      "properties": {
        "name": { "type": "string" },
        "uses": {
          "type": "string",
          "description": "Action reference (e.g., 'actions/checkout@v4')"
        },
        "run": {
          "type": "string",
          "description": "Shell command to execute"
        },
        "with": {
          "type": "object",
          "description": "Action parameters",
          "additionalProperties": true
        },
        "env": {
          "type": "object",
          "additionalProperties": { "type": "string" }
        },
        "if": {
          "type": "string",
          "description": "Step condition"
        },
        "continue-on-error": {
          "type": "boolean",
          "default": false
        },
        "timeoutMinutes": {
          "type": "integer"
        },
        "workingDirectory": {
          "type": "string"
        }
      }
    }
  }
}
```

### 3.2 Java Records

```java
// New file: src/main/java/com/pragmatik/buildtools/cicd/CicdPipeline.java
public record CicdPipeline(
    String name,
    String target,                // "github-actions" (MVP)
    String description,
    CicdTrigger on,
    Map<String, String> env,
    @Nullable CicdDefaults defaults,
    List<CicdJob> jobs,
    @Nullable CicdPermissions permissions,
    List<CicdSecret> requiredSecrets,
    String detectedBuildTool       // auto-detected or specified
) {}

// New file: src/main/java/com/pragmatik/buildtools/cicd/CicdTrigger.java
public record CicdTrigger(
    @Nullable CicdPushTrigger push,
    @Nullable CicdPrTrigger pullRequest,
    @Nullable CicdReleaseTrigger release,
    List<CicdScheduleTrigger> schedule,
    boolean workflowDispatch
) {}

// New file: src/main/java/com/pragmatik/buildtools/cicd/CicdJob.java
public record CicdJob(
    String name,
    String id,
    String runsOn,                // default: "ubuntu-latest"
    List<String> needs,
    @Nullable CicdMatrixStrategy strategy,
    List<CicdStep> steps,
    Map<String, String> env,
    int timeoutMinutes,           // default: 60
    @Nullable String condition
) {}

// New file: src/main/java/com/pragmatik/buildtools/cicd/CicdStep.java
public record CicdStep(
    String name,
    @Nullable String uses,
    @Nullable String run,
    Map<String, Object> with,
    Map<String, String> env,
    @Nullable String condition,
    boolean continueOnError,
    @Nullable Integer timeoutMinutes,
    @Nullable String workingDirectory
) {}

// New file: src/main/java/com/pragmatik/buildtools/cicd/CicdTarget.java
public interface CicdTarget {
    String name();                           // e.g., "github-actions"
    String generate(CicdPipeline pipeline);  // Intermediate → target YAML/String
    boolean validate(String configContent);  // Syntax + required-fields check
}

// New file: src/main/java/com/pragmatik/buildtools/cicd/GitHubActionsTarget.java
@Component
public class GitHubActionsTarget implements CicdTarget {
    // Generates .github/workflows/{name}.yml from CicdPipeline IR
}
```

### 3.3 Pipeline Shapes (Predefined Templates)

```yaml
# Template: ci-push.yml
# Trigger: push to main
name: CI - {{NAME}}
on:
  push:
    branches: [{{BRANCHES}}]
jobs:
  build:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        java: [{{JAVA_VERSIONS}}]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: ${{ matrix.java }}
          {{CACHE_CONFIG}}
      - name: Build with {{TOOL_NAME}}
        run: {{BUILD_COMMAND}}
```

---

## 4. API / Tool Definitions

### 4.1 `interpret_ci_flow` (New Tool)

Interpret a natural language CI/CD description and generate a pipeline configuration.

```java
@Tool(
    name = "interpret_ci_flow",
    description = "Interpret a natural-language CI/CD pipeline description and "
        + "generate a validated configuration file. Supports GitHub Actions (MVP). "
        + "Returns the generated YAML, validation status, detected build tool, "
        + "required secrets, and a pipeline summary."
)
public String interpretCiFlow(
    @ToolParam(required = true,
        description = "Natural language description of the CI/CD pipeline, "
            + "e.g. 'Set up CI that runs tests on every push to main, builds on "
            + "PR, and deploys on release tag'")
    String description,

    @ToolParam(required = false,
        description = "CI/CD target platform. Default: 'github-actions'. "
            + "Future: 'gitlab-ci', 'jenkins'.")
    String target,

    @ToolParam(required = false,
        description = "Project directory for build-tool detection "
            + "(auto-detects Maven, Gradle, or SBT)")
    String projectDir,

    @ToolParam(required = false,
        description = "Build tool override: 'maven', 'gradle', or 'sbt'")
    String buildToolName,

    @ToolParam(required = false,
        description = "Pipeline shape hint: 'ci-push', 'ci-pr', 'ci-release', "
            + "'ci-full', or 'custom' (default: auto-detect from description)")
    String pipelineShape,

    @ToolParam(required = false,
        description = "JDK versions to test against (comma-separated). "
            + "Default derived from project Java configuration. "
            + "Examples: '21', '21,23', '17,21,23'")
    String javaVersions,

    @ToolParam(required = false,
        description = "Additional configuration overrides as JSON. "
            + "Examples: {\"runners\": [\"ubuntu-latest\"], "
            + "\"timeout\": 30, \"branches\": [\"main\", \"develop\"]}")
    String configOverrides
);
```

**Returns:** Pipeline generation result JSON.

```
{
  "pipelineName": "CI - my-project",
  "target": "github-actions",
  "detectedBuildTool": "maven",
  "validation": {
    "valid": true,
    "warnings": []
  },
  "requiredSecrets": [
    {"name": "DEPLOY_KEY", "description": "SSH key for deployment"},
    {"name": "MAVEN_SETTINGS", "description": "Maven settings.xml with server credentials"}
  ],
  "pipelineSummary": {
    "triggers": ["push to main", "pull_request", "release published"],
    "jobs": [
      {"name": "Build", "runsOn": "ubuntu-latest", "matrix": ["21", "23"]},
      {"name": "Test", "needs": ["build"]},
      {"name": "Deploy", "needs": ["test"], "condition": "startsWith(github.ref, 'refs/tags/v')"}
    ],
    "totalSteps": 10
  },
  "config": "# Generated by mcp-server-jvm-build-tools v1.2.0\n... full YAML ...",
  "suggestedPath": ".github/workflows/ci.yml"
}
```

### 4.2 `validate_ci_config` (New Tool)

Validate an existing CI configuration for syntax and build-tool compatibility.

```java
@Tool(
    name = "validate_ci_config",
    description = "Validate an existing CI/CD configuration file for syntax "
        + "correctness and build-tool compatibility. Checks YAML validity, "
        + "required field presence, and build-tool-specific command alignment. "
        + "Does NOT execute any commands — read-only analysis."
)
public String validateCiConfig(
    @ToolParam(required = true,
        description = "Raw CI configuration file content")
    String configContent,

    @ToolParam(required = false,
        description = "CI/CD target platform: 'github-actions' (default)")
    String target,

    @ToolParam(required = false,
        description = "Build tool for compatibility check: 'maven', 'gradle', "
            + "or 'sbt'")
    String buildToolName
);
```

---

## 5. Generation Strategy

### 5.1 Pipeline Shape Detection

The `interpret_ci_flow` tool uses a keyword-matching algorithm on the description to
determine the pipeline shape:

| Keywords | Shape | Jobs Generated |
|----------|-------|----------------|
| `push`, `commit` | `ci-push` | Build + Test |
| `pr`, `pull request`, `review` | `ci-pr` | Build + Test (PR trigger only) |
| `deploy`, `release`, `publish`, `tag` | `ci-release` | Build + Test + Deploy |
| `push` + `pr` + `release` | `ci-full` | Build + Test + Deploy (all triggers) |
| (none specific) | `ci-push` (default) | Build + Test |

### 5.2 Build-Tool-Specific Patterns

| Build Tool | Setup Step | Build Command | Cache Key | Test Command |
|------------|-----------|---------------|-----------|--------------|
| Maven | `setup-java@v4` | `mvn verify -B` | `maven-{{ checksum "pom.xml" }}` | `mvn test -B` |
| Gradle | `setup-java@v4` + `gradle/actions/setup-gradle` | `./gradlew build` | `gradle-{{ checksum "**/*.gradle*" }}` | `./gradlew test` |
| SBT | `setup-java@v4` + Coursier cache | `sbt test` | `sbt-{{ checksum "build.sbt" }}` | `sbt test` |

### 5.3 JDK Matrix Strategy

When the project has a `pom.xml` with `<maven.compiler.release>21</maven.compiler.release>`,
the generated matrix includes:
- **21** (project minimum)
- **23** (latest stable if discovered from system)
- Optionally **25** (early-access, if user specifies)

When the project is Gradle or SBT, the matrix defaults to `['21', '23']` unless
`javaVersions` is explicitly provided.

### 5.4 Cache Configuration

Each build-tool target generates optimal cache configuration:

**Maven:**
```yaml
- name: Cache Maven dependencies
  uses: actions/cache@v4
  with:
    path: ~/.m2/repository
    key: maven-${{ hashFiles('**/pom.xml') }}
    restore-keys: maven-
```

**Gradle:**
```yaml
- name: Cache Gradle packages
  uses: actions/cache@v4
  with:
    path: |
      ~/.gradle/caches
      ~/.gradle/wrapper
    key: gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
    restore-keys: gradle-
```

**SBT:**
```yaml
- name: Cache Coursier / SBT
  uses: actions/cache@v4
  with:
    path: |
      ~/.cache/coursier
      ~/.sbt
    key: sbt-${{ hashFiles('build.sbt', 'project/**') }}
    restore-keys: sbt-
```

---

## 6. Validation Approach

### What Gets Validated

| Check | What It Does |
|-------|-------------|
| YAML syntax | Parses the generated YAML. Reports line:column errors. |
| Required fields | Ensures `name`, `on`, `jobs[].runs-on`, `jobs[].steps` are present. |
| Trigger validity | Verifies `on.push.branches` is non-empty; `on.schedule.cron` is valid. |
| Step uniqueness | Checks for duplicate step names within a job. |
| Build-tool commands | Verifies that all `run` commands use allowlisted build tool commands. |
| Action references | Validates `uses:` references follow `{owner}/{repo}@{ref}` format. |

### What's NOT Validated (Out of Scope for MVP)

- Actual workflow runtime correctness (e.g., does the workflow file actually run?)
- GitHub Actions expression syntax (`${{ }}`) beyond basic presence checking.
- Composite action internals.
- Self-hosted runner availability.

---

## 7. Error Handling

| Scenario | Behaviour |
|----------|-----------|
| Unknown build tool | Returns error with detected project files; asks user to specify |
| Empty description | Returns error: "CI description is required" |
| Invalid target name | Returns error: "Unknown target 'gitlab-ci'. Supported: github-actions" |
| YAML generation failure | Returns error with last good intermediate representation for debugging |
| Template not found for shape | Falls back to dynamic generation from intermediate representation |
| Validation warning | Returns config with warnings embedded; does NOT prevent generation |

---

## 8. Implementation Plan

### Files to Create

| File | Purpose |
|------|---------|
| `src/main/java/com/pragmatik/buildtools/cicd/CicdPipeline.java` | Pipeline IR record |
| `src/main/java/com/pragmatik/buildtools/cicd/CicdTrigger.java` | Trigger configuration |
| `src/main/java/com/pragmatik/buildtools/cicd/CicdJob.java` | Job definition |
| `src/main/java/com/pragmatik/buildtools/cicd/CicdStep.java` | Step definition |
| `src/main/java/com/pragmatik/buildtools/cicd/CicdTarget.java` | SPI for target platforms |
| `src/main/java/com/pragmatik/buildtools/cicd/CicdService.java` | @Service — MCP tool surface (2 tools) |
| `src/main/java/com/pragmatik/buildtools/cicd/GitHubActionsTarget.java` | @Component — GitHub Actions YAML generator |
| `src/main/java/com/pragmatik/buildtools/cicd/PipelineShapeDetector.java` | NL description → pipeline shape |
| `src/main/java/com/pragmatik/buildtools/cicd/CicdValidator.java` | YAML syntax + required-field validation |
| `src/main/resources/cicd/templates/ci-push.yml` | Push-only template |
| `src/main/resources/cicd/templates/ci-pr.yml` | Pull-request template |
| `src/main/resources/cicd/templates/ci-release.yml` | Release/deploy template |
| `src/main/resources/cicd/templates/ci-full.yml` | Full pipeline template |
| `src/test/java/com/pragmatik/buildtools/cicd/CicdServiceTest.java` | Integration tests |
| `src/test/java/com/pragmatik/buildtools/cicd/GitHubActionsTargetTest.java` | YAML generation tests |
| `src/test/java/com/pragmatik/buildtools/cicd/PipelineShapeDetectorTest.java` | Shape detection tests |

### Files to Modify

| File | Change |
|------|--------|
| `BuildToolsApplication.java` | Register `CicdService` bean |
| `docs/reference/tools.md` | Add `interpret_ci_flow` and `validate_ci_config` |
| `README.md` | Mention CI/CD generation in features list |

---

## 9. Test Scenarios

### Scenario 1: Basic push-triggered CI (Maven)
```
Tool: interpret_ci_flow
Input: description="Run tests on every push to main",
       projectDir="/tmp/maven-project"
Expect: Detects Maven. Generates GitHub Actions YAML with:
  - Trigger: push to main
  - Job: Build (mvn verify -B)
  - JDK matrix: ['21']
  - Maven caching
  - Validation: passes
```

### Scenario 2: Full CI/CD pipeline (Gradle)
```
Tool: interpret_ci_flow
Input: description="Build on push to any branch, run tests on PR, deploy on release tag",
       projectDir="/tmp/gradle-project"
Expect: Detects Gradle. Generates 3-job pipeline:
  - Build: ./gradlew build (push to any branch)
  - Test: ./gradlew test (PR trigger)
  - Deploy: ./gradlew publish (tag trigger, needs test)
  - Gradle wrapper caching enabled
  - requiredSecrets includes DEPLOY_KEY
```

### Scenario 3: SBT project with matrix
```
Tool: interpret_ci_flow
Input: description="Build and test on push",
       projectDir="/tmp/sbt-project",
       javaVersions="21,23"
Expect: Detects SBT. Generates:
  - sbt test command
  - Coursier cache
  - JDK matrix: ['21', '23']
```

### Scenario 4: Custom pipeline with config overrides
```
Tool: interpret_ci_flow
Input: description="Run tests weekly and on every push",
       projectDir="/tmp/maven-project",
       configOverrides='{"branches": ["main", "develop"],
                         "timeout": 45}'
Expect: Generates:
  - Push trigger on main + develop
  - Schedule trigger with cron expression
  - 45-minute timeout on all jobs
```

### Scenario 5: Explicit pipeline shape
```
Tool: interpret_ci_flow
Input: description="Release pipeline",
       pipelineShape="ci-release",
       projectDir="/tmp/maven-project"
Expect: Generates release pipeline with:
  - Trigger: release published
  - Build → Test → Deploy jobs
  - Deploy conditioned on tag
```

### Scenario 6: Validate existing CI config
```
Tool: validate_ci_config
Input: configContent="<valid yaml>", target="github-actions"
Expect: Validation passes. Returns {valid: true, warnings: []}
```

### Scenario 7: Validate invalid CI config
```
Tool: validate_ci_config
Input: configContent="<yaml without name field>", target="github-actions"
Expect: Validation fails. Returns {valid: false,
  errors: [{message: "Missing required field: name", line: 1}]}
```

### Scenario 8: Unknown project type
```
Tool: interpret_ci_flow
Input: description="Build on push", projectDir="/tmp/python-project"
Expect: Returns error: "No JVM build tool detected.
  Supported: Maven (pom.xml), Gradle (build.gradle/.kts), SBT (build.sbt)"
```

### Scenario 9: Generated YAML write-friendly output
```
Tool: interpret_ci_flow(description="...", projectDir="...")
Expect: "suggestedPath": ".github/workflows/ci.yml" in output.
  YAML content is valid per GitHub Actions schema.
```

### Scenario 10: Multi-branch + multi-JDK
```
Tool: interpret_ci_flow
Input: description="Build on push to main, develop, and feature branches",
       javaVersions="17,21,23",
       projectDir="/tmp/gradle-project"
Expect: Generates:
  - Trigger: push to main, develop, and ** (all branches)
  - JDK matrix: ['17', '21', '23']
  - Gradle caching with wrapper validation
```

---

## 10. Acceptance Criteria

- [ ] `interpret_ci_flow` generates valid GitHub Actions YAML for Maven, Gradle, and SBT.
- [ ] Pipeline shape detection correctly identifies `ci-push`, `ci-pr`, `ci-release`, `ci-full`.
- [ ] Build-tool-specific caching and JDK setup are correct per tool.
- [ ] `validate_ci_config` detects missing required fields and YAML syntax errors.
- [ ] Generated YAML passes `actions/cache` key conventions for each build tool.
- [ ] Output includes `suggestedPath`, `requiredSecrets`, and `pipelineSummary`.
- [ ] All 10 test scenarios pass.
- [ ] No dependency on external YAML rendering libraries (Java `String.format` / `SnakeYAML`
      are sufficient).
