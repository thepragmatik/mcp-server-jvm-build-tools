# Build Plan Authoring — Design Spec

**Date:** 2026-07-26  
**Target Release:** v1.2.0  
**Status:** Draft  
**Priority:** P1 (HIGH value, MEDIUM effort)  
**Author:** Architecture profile (Phase 1 research), mcp-server-jvm-build-tools

---

## 1. Overview / Motivation

AI coding agents can write code, refactor modules, and manage projects — but they currently
orchestrate build steps one tool call at a time. A 3-minute `mvn clean install` → fix →
`mvn compile` → fix → `mvn test` loop can consume 6+ blocking round-trips with no
structured plan, progress reporting, or failure handling.

**Build Plan Authoring** lets an agent express a multi-step workflow in natural language and
have the server produce, validate, and execute a structured build plan — with step-level
progress, failure isolation, and an aggregated result. This reduces the LLM round-trip cost
from O(N steps) to O(1) for common workflows like "build, test, and package".

### Key Design Goals

1. **Natural-language ingress** — agent says "build and test this project, then package it";
   the server plans the steps.
2. **Structured plan model** — JSON Schema-defined plan with ordered steps, dependencies,
   and error handling.
3. **Execution engine** — sequential step execution with per-step status reporting and
   configurable failure handling (stop vs. continue).
4. **Async by default** — plan execution runs as an MCP Task (2026-07-28 Tasks extension),
   returning a task handle immediately and streaming progress as step transitions.
5. **Replay/review** — plans can be persisted and re-executed or reviewed offline.

---

## 2. Design Decisions

### Decision 1: Plan format — JSON Schema (structured) over free-form text

**Chosen: JSON Schema with step objects.**

| Approach | Pros | Cons |
|----------|------|------|
| Free-form text + LLM parsing | Fast to prototype | Non-deterministic parsing; hard to validate, store, or diff |
| **JSON Schema steps** | Machine-validatable, storable, diffable, auditable | Slightly more work to author |

Free-form text is inherently fragile — two different LLMs (or even the same LLM across
model versions) may structure the plan differently, making caching and audit impossible.
A JSON Schema plan is deterministic, can be pre-validated, and produces structured output.

**Rationale from Phase 1 research:** The project already enforces JSON Schema 2020-12
validation on all MCP tool schemas (`ToolJsonSchemaComplianceTest`), and the existing
`validate_build_configuration` tool proves the server team is comfortable with
structured JSON validation. Consistency across the codebase favours JSON Schema.

### Decision 2: Storage — Resource Template (`plan://projects/{id}`) over ephemeral

**Chosen: Resource template-backed persistence.**

Plans are persisted in `.buildtools/plans/` (same convention as
`.buildtools/history/` used by `BuildPerformanceService`) and exposed via a
`plan://projects/{projectDir}/plans/{planId}` resource URI scheme. This lets
an agent save a plan, review it later, re-execute it, or share it across sessions.

Ephemeral (tool-parameter-only) plans would be lost when the MCP session ends,
defeating the replay/review use case. The resource template pattern is consistent
with the existing `BuildResourceService` and `ResourceTemplateService`.

### Decision 3: Execution — Sequential sync or async MCP Tasks

**Chosen: Async MCP Tasks (MCP 2026-07-28 Tasks extension).**

Build steps are long-running (30s–5min). Blocking the client for the entire plan is
unacceptable. The async approach:
1. Returns a `planId` and `taskId` immediately.
2. Client polls `get_build_task(taskId)` to see step-level progress.
3. Each completed step emits a status update: `queued → running → completed/failed/skipped`.
4. The agent can cancel the plan mid-execution.

This aligns with the existing `AsyncBuildService` patterns found in the codebase
(though that service is not currently wired into the tool surface).

### Decision 4: Output — Structured step-by-step results

**Chosen: Aggregated summary + per-step detail.**

The final output includes:
- **Summary:** total steps, succeeded, failed, skipped, overall duration.
- **Steps array:** each step's status, output (truncated), duration, errors.
- **Error context:** if a step fails, the error details (file:line, severity) are
  included so the agent can diagnose without re-running.

### Decision 5: Failure handling — configurable per step, default "stop at first failure"

Agents can annotate steps with `onFailure: "stop" | "continue" | "skipRemaining"`.

- `"stop"` (default): abort the plan on first failure.
- `"continue"`: log the error but keep executing subsequent steps.
- `"skipRemaining"`: mark the failed step and skip all remaining steps (like `stop`
  but doesn't return an error — useful for "try to build but skip tests if compilation
  fails").

### Decision 6: Integration with existing services

The plan engine delegates step execution to:
- `BuildToolsService#executeBuildCommand` for raw build commands.
- `BuildPerformanceService#profileBuild` for timed steps.
- `AsyncBuildService` for async step execution.
- `BuildToolProvider` for tool resolution and auto-detection.

No new build-tool logic is needed — the plan engine is a workflow orchestrator, not
a build executor.

---

## 3. Data Model

### 3.1 Plan — JSON Schema 2020-12

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "BuildPlan",
  "description": "A multi-step build plan with ordered steps and failure handling",
  "type": "object",
  "required": ["planId", "description", "steps", "createdAt"],
  "properties": {
    "planId": {
      "type": "string",
      "description": "Unique plan identifier (UUID)"
    },
    "description": {
      "type": "string",
      "description": "Human-readable description of the plan's goal",
      "maxLength": 1000,
      "examples": ["Build and test this project, then package it as a JAR"]
    },
    "projectDir": {
      "type": "string",
      "description": "Project directory path"
    },
    "buildToolName": {
      "type": "string",
      "enum": ["maven", "gradle", "sbt"],
      "description": "Build tool override. Omitted to auto-detect."
    },
    "buildToolHome": {
      "type": "string",
      "description": "Build tool installation path (required for Maven)"
    },
    "steps": {
      "type": "array",
      "description": "Ordered list of build steps to execute",
      "minItems": 1,
      "items": {
        "$ref": "#/$defs/BuildStep"
      }
    },
    "errorHandling": {
      "type": "string",
      "enum": ["stop", "continue", "skipRemaining"],
      "default": "stop",
      "description": "Default failure handling for steps that don't specify onFailure"
    },
    "createdAt": {
      "type": "string",
      "format": "date-time",
      "description": "Plan creation timestamp"
    },
    "ttlSeconds": {
      "type": "integer",
      "minimum": 60,
      "default": 3600,
      "description": "Plan expiry in seconds (CacheableResult hint)"
    }
  },
  "$defs": {
    "BuildStep": {
      "type": "object",
      "required": ["id", "label", "command"],
      "properties": {
        "id": {
          "type": "string",
          "description": "Step identifier (e.g., 'step-1', 'compile')"
        },
        "label": {
          "type": "string",
          "description": "Human-readable step name",
          "examples": ["Compile source code", "Run unit tests"]
        },
        "command": {
          "type": "string",
          "description": "Build command to execute (see execute_build_command for supported commands)",
          "examples": ["clean compile", "test", "package", "install"]
        },
        "dependsOn": {
          "type": "array",
          "items": { "type": "string" },
          "description": "Step IDs that must complete before this step starts",
          "default": []
        },
        "timeoutSeconds": {
          "type": "integer",
          "minimum": 10,
          "default": 300,
          "description": "Max execution time for this step"
        },
        "onFailure": {
          "type": "string",
          "enum": ["stop", "continue", "skipRemaining"],
          "description": "Override the plan-level error handling for this step"
        },
        "captureOutput": {
          "type": "boolean",
          "default": true,
          "description": "Whether to capture and return full step output"
        },
        "retryCount": {
          "type": "integer",
          "minimum": 0,
          "maximum": 3,
          "default": 0,
          "description": "Number of automatic retries on failure"
        }
      }
    }
  }
}
```

### 3.2 PlanResult — Output JSON

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "PlanResult",
  "type": "object",
  "required": ["planId", "status", "steps", "summary"],
  "properties": {
    "planId": { "type": "string" },
    "description": { "type": "string" },
    "status": {
      "type": "string",
      "enum": ["completed", "failed", "cancelled", "running", "queued"]
    },
    "projectDir": { "type": "string" },
    "tool": { "type": "string" },
    "totalDurationSeconds": { "type": "number" },
    "totalDurationFormatted": { "type": "string" },
    "steps": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/StepResult"
      }
    },
    "summary": {
      "type": "object",
      "properties": {
        "total": { "type": "integer" },
        "completed": { "type": "integer" },
        "failed": { "type": "integer" },
        "skipped": { "type": "integer" },
        "errorCount": { "type": "integer" },
        "warningCount": { "type": "integer" }
      },
      "required": ["total", "completed", "failed", "skipped"]
    },
    "errors": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "stepId": { "type": "string" },
          "file": { "type": "string" },
          "line": { "type": "integer" },
          "severity": { "type": "string", "enum": ["ERROR", "WARNING"] },
          "message": { "type": "string" }
        }
      }
    },
    "finishedAt": { "type": "string", "format": "date-time" }
  },
  "$defs": {
    "StepResult": {
      "type": "object",
      "required": ["id", "label", "status"],
      "properties": {
        "id": { "type": "string" },
        "label": { "type": "string" },
        "status": {
          "type": "string",
          "enum": ["completed", "failed", "skipped", "running", "queued"]
        },
        "durationSeconds": { "type": "number" },
        "success": { "type": "boolean" },
        "output": { "type": "string", "description": "Captured stdout (truncated to 10KB)" },
        "testSummary": {
          "type": "object",
          "properties": {
            "total": { "type": "integer" },
            "passed": { "type": "integer" },
            "failed": { "type": "integer" },
            "errors": { "type": "integer" },
            "skipped": { "type": "integer" }
          }
        },
        "errors": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "file": { "type": "string" },
              "line": { "type": "integer" },
              "severity": { "type": "string" },
              "message": { "type": "string" }
            }
          }
        },
        "retryAttempted": { "type": "integer", "default": 0 },
        "startedAt": { "type": "string", "format": "date-time" },
        "finishedAt": { "type": "string", "format": "date-time" }
      }
    }
  }
}
```

### 3.3 Java Records — Plan Data Model

```java
// New file: src/main/java/com/pragmatik/buildtools/plan/PlanStep.java
public record PlanStep(
    String id,
    String label,
    String command,
    List<String> dependsOn,       // default: List.of()
    int timeoutSeconds,           // default: 300
    String onFailure,             // default: "stop"
    boolean captureOutput,        // default: true
    int retryCount                // default: 0
) {}

// New file: src/main/java/com/pragmatik/buildtools/plan/BuildPlan.java
public record BuildPlan(
    String planId,
    String description,
    String projectDir,
    String buildToolName,         // nullable — auto-detect
    String buildToolHome,         // nullable
    List<PlanStep> steps,
    String errorHandling,         // default: "stop"
    Instant createdAt,
    int ttlSeconds               // default: 3600
) {}

// New file: src/main/java/com/pragmatik/buildtools/plan/StepResult.java
public record StepResult(
    String id,
    String label,
    String status,                // completed | failed | skipped | running | queued
    double durationSeconds,
    boolean success,
    String output,                // truncated to 10KB
    @Nullable TestSummary testSummary,
    List<BuildError> errors,
    int retryAttempted,
    Instant startedAt,
    Instant finishedAt
) {}

// New file: src/main/java/com/pragmatik/buildtools/plan/PlanResult.java
public record PlanResult(
    String planId,
    String description,
    String status,                // completed | failed | cancelled | running | queued
    String projectDir,
    String tool,
    double totalDurationSeconds,
    String totalDurationFormatted,
    List<StepResult> steps,
    PlanSummary summary,
    List<BuildError> errors,
    Instant finishedAt
) {}

// New file: src/main/java/com/pragmatik/buildtools/plan/PlanSummary.java
public record PlanSummary(
    int total,
    int completed,
    int failed,
    int skipped,
    int errorCount,
    int warningCount
) {}

// Reuse existing model:
// com.pragmatik.buildtools.build.BuildOutputParser.TestSummary
// com.pragmatik.buildtools.build.BuildOutputParser.BuildError
```

---

## 4. API / Tool Definitions

### 4.1 `create_build_plan` (New Tool)

Create a build plan from a natural language description or structured step list.

```java
@Tool(
    name = "create_build_plan",
    description = "Create a build plan from a natural language description or "
        + "structured step list. Generates a JSON plan with ordered steps, "
        + "validates it, and persists it as a resource for review or execution. "
        + "Returns the plan JSON including planId, steps, and estimated duration."
)
public String createBuildPlan(
    @ToolParam(required = true,
        description = "Natural language description of what the plan should accomplish, "
            + "e.g. 'Build and test the project, then package as JAR'")
    String description,

    @ToolParam(required = false,
        description = "Optional explicit step definitions. If omitted, steps are "
            + "generated from the description.")
    String stepsJson,

    @ToolParam(required = false,
        description = "Project directory path. Required when steps are auto-generated "
            + "from description.")
    String projectDir,

    @ToolParam(required = false,
        description = "Build tool name. Omit to auto-detect from project.")
    String buildToolName,

    @ToolParam(required = false,
        description = "Path to build tool installation. Required for Maven.")
    String buildToolHome,

    @ToolParam(required = false,
        description = "Default failure handling: 'stop' (default), 'continue', or "
            + "'skipRemaining'.")
    String errorHandling
);
```

**Returns:** Plan JSON (see §3.1).

**Example:**

```
Request:
  create_build_plan(
    description="Build and test this project, then package it as a JAR",
    projectDir="/home/user/my-app"
  )

Response:
  {
    "planId": "a1b2c3d4-...",
    "description": "Build and test this project, then package it as a JAR",
    "projectDir": "/home/user/my-app",
    "steps": [
      {"id": "compile", "label": "Compile source code", "command": "clean compile",
       "dependsOn": [], "timeoutSeconds": 120},
      {"id": "test", "label": "Run unit tests", "command": "test",
       "dependsOn": ["compile"], "timeoutSeconds": 300},
      {"id": "package", "label": "Package as JAR", "command": "package",
       "dependsOn": ["test"], "timeoutSeconds": 120}
    ],
    "createdAt": "2026-07-26T12:00:00Z",
    "status": "ready"
  }
```

### 4.2 `execute_build_plan` (New Tool)

Execute a previously created plan as an async MCP Task.

```java
@Tool(
    name = "execute_build_plan",
    description = "Execute a build plan as an async MCP Task. Returns a task handle "
        + "immediately. Poll with get_build_task to track step-level progress. "
        + "The plan must have been created with create_build_plan first."
)
public String executeBuildPlan(
    @ToolParam(required = true,
        description = "Plan ID returned by create_build_plan")
    String planId
);
```

**Returns:** Task handle JSON.

```
{
  "taskId": "task-abc123",
  "planId": "a1b2c3d4-...",
  "status": "running",
  "currentStep": {"id": "compile", "label": "Compile source code", "status": "running"},
  "stepsCompleted": 0,
  "stepsTotal": 3,
  "createdAt": "2026-07-26T12:00:05Z"
}
```

### 4.3 `get_build_plan_status` (New Tool)

Poll plan execution status (complements `get_build_task` from `AsyncBuildService`).

```java
@Tool(
    name = "get_build_plan_status",
    description = "Get the current execution status of a build plan. Returns "
        + "per-step status, timing, and any errors. Use after execute_build_plan "
        + "to track progress."
)
public String getBuildPlanStatus(
    @ToolParam(required = true,
        description = "Plan ID returned by create_build_plan")
    String planId
);
```

### 4.4 `cancel_build_plan` (New Tool)

Cancel a running plan execution.

```java
@Tool(
    name = "cancel_build_plan",
    description = "Cancel a running build plan execution. Running steps are "
        + "terminated; remaining queued steps are marked as skipped."
)
public String cancelBuildPlan(
    @ToolParam(required = true,
        description = "Plan ID returned by create_build_plan")
    String planId
);
```

### 4.5 Resource Templates

```
plan://projects/{projectDir}/plans/{planId}
plan://projects/{projectDir}/plans/{planId}/results
plan://projects/{projectDir}/plans/{planId}/steps/{stepId}
```

These expose plans and results as MCP resources, consistent with the existing
`BuildResourceService` `build://` scheme.

---

## 5. Execution Flow

```
┌────────────────┐     ┌───────────────────┐     ┌──────────────────────┐
│ Agent describes  │────▶│ create_build_plan  │────▶│ Plan is validated    │
│ build workflow   │     │ (parse description │     │ against JSON Schema  │
│                   │     │  → generate steps) │     │ and persisted to     │
│                   │     │                    │     │ .buildtools/plans/   │
└────────────────┘     └───────────────────┘     └──────────┬───────────┘
                                                             │
                    ┌──────────────────────────┐             │
                    │ execute_build_plan(planId)│◀────────────┘
                    │ Returns task handle       │
                    └────────────┬─────────────┘
                                 │
                    ┌────────────▼─────────────┐
                    │ PlanExecutionEngine        │
                    │  Sequential step runner    │
                    └────────────┬─────────────┘
                                 │
          ┌──────────────────────┼──────────────────────┐
          ▼                      ▼                      ▼
   ┌──────────────┐      ┌──────────────┐      ┌──────────────┐
   │ Step 1:      │      │ Step 2:      │      │ Step 3:      │
   │ clean compile│──────▶│ test         │──────▶│ package      │
   │              │      │              │      │              │
   │ BuildToolsSvc│      │ BuildToolsSvc│      │ BuildToolsSvc│
   └──────────────┘      └──────────────┘      └──────────────┘
          │                      │                      │
          ▼                      ▼                      ▼
   ┌────────────────────────────────────────────────────────┐
   │ PlanResult (aggregated summary + per-step results)      │
   │ Persisted to .buildtools/plans/{planId}/results         │
   │ Exposed via plan:// resource URI                        │
   └────────────────────────────────────────────────────────┘
```

### Step Generation Logic

When `create_build_plan` is called with only a `description` (no explicit steps), the
service uses a deterministic step-generation algorithm:

1. **Parse description** for keywords: `build`, `compile`, `test`, `package`,
   `install`, `deploy`, `validate`, `clean`, `check`, `jar`, `publish`.
2. **Order phases** by lifecycle convention:
   - `clean` always first (if present).
   - `compile` or `build` next.
   - `test` after compilation.
   - `package`, `check`, `verify`, `install`, `deploy` last.
3. **Detect build tool** (auto-detect or explicit).
4. **Generate step IDs** like `step-1`, `step-2` with labels.
5. **Validate** the generated plan against the JSON Schema.

For agents that need precise control, explicit `stepsJson` can be passed directly.

---

## 6. Error Handling

| Scenario | Behaviour |
|----------|-----------|
| Unknown step command (not in allowlist) | Plan rejected at creation with `INVALID_STEP` error listing the invalid command |
| Build tool not detected | Plan rejected — agent must specify `buildToolName` |
| Step timeout | Step marked as `failed`, `onFailure` policy applied |
| Build failure (test fails) | Step marked as `failed`, test summary attached. `onFailure: "stop"` aborts plan |
| Plan not found | `get_build_plan_status` returns `NOT_FOUND` error |
| Cancel already-completed plan | No-op, returns current status |
| Disk I/O error persisting plan | Returns error — plan execution continues in-memory but cannot be resumed later |

---

## 7. Implementation Plan

### Files to Create

| File | Purpose |
|------|---------|
| `src/main/java/com/pragmatik/buildtools/plan/PlanStep.java` | Step data record |
| `src/main/java/com/pragmatik/buildtools/plan/BuildPlan.java` | Plan data record |
| `src/main/java/com/pragmatik/buildtools/plan/StepResult.java` | Step execution result |
| `src/main/java/com/pragmatik/buildtools/plan/PlanResult.java` | Aggregated plan result |
| `src/main/java/com/pragmatik/buildtools/plan/PlanSummary.java` | Summary counters |
| `src/main/java/com/pragmatik/buildtools/plan/PlanStepGenerator.java` | NL description → step list |
| `src/main/java/com/pragmatik/buildtools/plan/PlanExecutionEngine.java` | Sequential async step runner |
| `src/main/java/com/pragmatik/buildtools/plan/PlanService.java` | @Service — MCP tool surface (4 tools) |
| `src/main/java/com/pragmatik/buildtools/plan/PlanStore.java` | Persistence to `.buildtools/plans/` |
| `src/test/java/com/pragmatik/buildtools/plan/PlanServiceTest.java` | Unit tests |
| `src/test/java/com/pragmatik/buildtools/plan/PlanStepGeneratorTest.java` | Step generation tests |
| `src/test/java/com/pragmatik/buildtools/plan/PlanExecutionEngineTest.java` | Execution tests |

### Files to Modify

| File | Change |
|------|--------|
| `BuildToolsApplication.java` | Register `PlanService` bean in `MethodToolCallbackProvider.toolObjects(...)` |
| `docs/reference/tools.md` | Add 4 new plan tools to the reference |
| `src/main/resources/application.properties` | Add plan TTL defaults |

---

## 8. Test Scenarios

### Scenario 1: Basic 3-step plan from description
```
Tool: create_build_plan
Input: description="Build and test this project, then package it as a JAR",
       projectDir="/tmp/maven-project"
Expect: Returns plan with 3 steps: compile → test → package.
        Steps validated against allowlist.
```

### Scenario 2: Explicit step definitions
```
Tool: create_build_plan
Input: description="Custom plan",
       stepsJson='[{"id":"clean","label":"Clean","command":"clean"},
                   {"id":"compile","label":"Compile","command":"compile","dependsOn":["clean"]}]',
       projectDir="/tmp/maven-project"
Expect: Returns plan with 2 steps in order. Clean runs first, then compile.
```

### Scenario 3: Async execution with status polling
```
Tool: execute_build_plan(planId="<from-scenario-1>")
Expect: Returns task handle with taskId.
        Polling get_build_plan_status shows: queued → running (step 1) → running (step 2) →
          running (step 3) → completed with summary.
        Final result shows 3/3 steps completed, total duration ~15s.
```

### Scenario 4: Step failure with "stop" (default)
```
Tool: execute_build_plan(planId="<plan-with-compile-failure>")
Expect: Step 1 (compile) fails.
        Plan stops immediately. Step 2 and 3 are skipped.
        Result: {summary: {total:3, completed:0, failed:1, skipped:2}}
```

### Scenario 5: Step failure with "continue"
```
Tool: execute_build_plan(planId="<plan-with-errorHandling=continue, test-fails>")
Expect: Step 1 (compile) passes. Step 2 (test) fails with test errors.
        Step 3 (package) still executes because onFailure=continue.
        Result: {summary: {total:3, completed:2, failed:1, skipped:0}}
        Package step may also fail if tests didn't pass.
```

### Scenario 6: Invalid command in step
```
Tool: create_build_plan
Input: description="Delete everything",
       projectDir="/tmp/maven-project"
Expect: Plan is created but step command "delete everything" is rejected
        as not in the allowlist for the detected build tool.
        Returns error: INVALID_STEP with details.
```

### Scenario 7: Cancel running plan
```
Tool: execute_build_plan(planId="<plan-with-3-slow-steps>")
      → task handle returned
      cancel_build_plan(planId="<same-plan>")
Expect: Running step is terminated. Remaining steps skipped.
        Final status: "cancelled".
        Summary: {total:3, completed:1, failed:0, skipped:2}
```

### Scenario 8: Plan persistence and retrieval via resource
```
Tool: create_build_plan(description="test", projectDir="/tmp/project")
Expect: Plan saved to .buildtools/plans/{planId}/plan.json
        read_build_resource(uri="plan://projects//tmp/project/plans/{planId}")
        returns the plan JSON.
```

### Scenario 9: Auto-detection across tools
```
Tool: create_build_plan(description="Build and test", projectDir="/tmp/gradle-project")
Expect: Detects Gradle from build.gradle.kts.
        Steps generated: build → test (Gradle-compatible commands).
```

### Scenario 10: Retry on transient failure
```
Tool: create_build_plan with steps=[{id:"compile", retryCount:2, command:"compile"}]
      execute_build_plan(planId="...")
Expect: If compile fails first time, retries up to 2 more times.
        If eventually succeeds, step status is "completed" with retryAttempted=1.
        If all retries fail, step status is "failed" with retryAttempted=2.
```

---

## 9. Acceptance Criteria

- [ ] All 4 tools (`create_build_plan`, `execute_build_plan`, `get_build_plan_status`,
      `cancel_build_plan`) are registered and discoverable via `tools/list`.
- [ ] `create_build_plan` generates valid step lists for Maven, Gradle, and SBT projects.
- [ ] `execute_build_plan` executes steps sequentially with proper dependency ordering.
- [ ] Plan execution supports async polling (returns task handle, non-blocking).
- [ ] Error handling respects per-step `onFailure` and plan-level `errorHandling`.
- [ ] Plans are persisted to `.buildtools/plans/` and exposed as resources.
- [ ] All 10 test scenarios pass.
- [ ] JSON Schema compliance validated by `ToolJsonSchemaComplianceTest`.
- [ ] No regression in existing `execute_build_command` or `profile_build`.
