# Examples

Concrete, end-to-end examples for Maven, Gradle, and SBT. Each shows the **tool call arguments**
the agent sends and a **representative response shape**. The JSON below illustrates the structure
the server returns — exact values depend on your project and toolchain.

!!! note "How to read these"
    You normally won't write tool calls yourself — your agent does. The arguments are shown so you
    can see exactly what each tool needs. Responses are *example shapes*, not metrics from a
    specific run.

## Discover the available tools

> "What build tools are available?"

The agent calls `list_build_tools` (no arguments). The server returns the registered tools and
their allowlisted commands:

```text
maven:  clean, compile, test, package, install, deploy, validate
gradle: clean, build, test, compileJava, compileTestJava, jar, assemble,
        check, publishToMavenLocal, dependencies, projects, tasks
sbt:    compile, test, run, package, clean, assembly, publishLocal,
        publish, update, doc, console
```

## Detect a project's build tool

> "Detect the build tool for /path/to/my-project"

=== "Tool call"

    ```json
    {
      "name": "detect_build_tool",
      "arguments": { "projectDir": "/path/to/my-project" }
    }
    ```

=== "Example response"

    ```json
    {
      "projectDir": "/path/to/my-project",
      "detected": ["maven"],
      "primary": "maven",
      "markers": { "maven": ["pom.xml"] }
    }
    ```

When several markers are present (a hybrid project), the server lists all detected tools and
prioritises Maven for auto-detection. Pass `buildToolName` explicitly to override.

## Maven: clean and test

> "Run 'clean test' on /path/to/my-maven-app"

=== "Tool call"

    ```json
    {
      "name": "execute_build_command",
      "arguments": {
        "buildToolName": "maven",
        "buildToolHome": "/opt/maven",
        "projectDir": "/path/to/my-maven-app",
        "command": "clean test"
      }
    }
    ```

=== "Auto-detect variant"

    Omit `buildToolName` to auto-detect from `pom.xml`. With `MAVEN_HOME` set, you can also omit
    `buildToolHome`:

    ```json
    {
      "name": "execute_build_command",
      "arguments": {
        "projectDir": "/path/to/my-maven-app",
        "command": "clean test"
      }
    }
    ```

This returns the raw build log as text. To get a parsed result instead, use
`analyze_build_output` (below).

## Maven: structured test results

> "Build and test /path/to/my-maven-app and tell me what failed"

=== "Tool call"

    ```json
    {
      "name": "analyze_build_output",
      "arguments": {
        "buildToolName": "maven",
        "buildToolHome": "/opt/maven",
        "projectDir": "/path/to/my-maven-app",
        "command": "clean test"
      }
    }
    ```

=== "Example response"

    ```json
    {
      "success": false,
      "tool": "maven",
      "command": "clean test",
      "testSummary": { "total": 42, "passed": 41, "failed": 1, "skipped": 0 },
      "errors": [
        {
          "file": "src/test/java/com/example/OrderServiceTest.java",
          "line": 88,
          "message": "expected:<3> but was:<2>"
        }
      ],
      "warnings": [],
      "errorCount": 1,
      "warningCount": 0,
      "duration": "PT12.4S"
    }
    ```

The parser extracts the success flag, test counts, errors with `file:line`, and warnings, so an
agent can reason about failures programmatically.

## Gradle: build with the wrapper

> "Run 'build' on /path/to/my-gradle-app"

=== "Tool call"

    ```json
    {
      "name": "execute_build_command",
      "arguments": {
        "buildToolName": "gradle",
        "projectDir": "/path/to/my-gradle-app",
        "command": "build"
      }
    }
    ```

Gradle needs **no installation**: the server auto-detects the project's `gradlew` wrapper and
falls back to `gradle` on `PATH`. It always appends `--no-daemon --console=plain`, so do **not**
add those flags yourself.

!!! tip "Multi-module Gradle"
    Use colon syntax to target a submodule task, e.g. `:web:build`. The task name is taken from the
    last colon-separated segment and validated against the allowlist.

## SBT: compile then test

> "Compile and test the SBT project at /path/to/my-sbt-app"

=== "Tool call"

    ```json
    {
      "name": "execute_build_command",
      "arguments": {
        "buildToolName": "sbt",
        "projectDir": "/path/to/my-sbt-app",
        "command": "clean;compile;test"
      }
    }
    ```

SBT chains tasks with **semicolons**, not spaces. The server appends `--no-colors` for
machine-readable output.

!!! warning "First SBT run is slow"
    The first `execute_build_command` for an SBT project may take 1–3 minutes while SBT downloads
    its launcher and compiles the build definition.

### Analyse an SBT build configuration

> "Analyse the SBT build at /path/to/my-sbt-app"

=== "Tool call"

    ```json
    {
      "name": "analyze_sbt_build",
      "arguments": { "projectDir": "/path/to/my-sbt-app" }
    }
    ```

The `SbtProjectService` also provides `detect_sbt_modules` (multi-module structure) and
`detect_sbt_test_frameworks` (e.g. ScalaTest, Specs2, MUnit).

## Check a dependency version

> "What's the latest version of com.google.guava:guava?"

=== "Tool call"

    ```json
    {
      "name": "check_dependency_version",
      "arguments": {
        "groupId": "com.google.guava",
        "artifactId": "guava",
        "currentVersion": "32.1.0-jre",
        "versionPreference": "RELEASE",
        "projectDir": "/path/to/my-maven-app"
      }
    }
    ```

=== "Notes"

    - `currentVersion` is optional — omit it to just fetch the latest version.
    - `versionPreference` is one of `RELEASE` (default), `LATEST`, `SNAPSHOT`, or `ALL`.
    - When `projectDir` is supplied, the server auto-detects the build tool and returns the
      dependency declaration in that tool's syntax (Maven XML, Gradle string notation, or SBT
      `libraryDependencies`).

This tool queries Maven Central over HTTPS (5-second timeout) and classifies versions by stability
(stable, RC, milestone, beta, alpha, snapshot).

## Validate a build file without building

> "Validate the build configuration in /path/to/my-project"

=== "Tool call"

    ```json
    {
      "name": "validate_build_configuration",
      "arguments": { "projectDir": "/path/to/my-project" }
    }
    ```

=== "Example response"

    ```json
    {
      "valid": true,
      "file": "pom.xml",
      "issues": [],
      "checks": ["xml-well-formed", "required-elements", "duplicate-dependencies",
                 "plugin-version-consistency"]
    }
    ```

This is a fast, static check (no build process is spawned). It validates `pom.xml`,
`build.gradle`, and `build.gradle.kts`. SBT `build.sbt` validation is not yet implemented.

## Detect dependency conflicts

> "Are there dependency version conflicts in /path/to/my-project?"

=== "Tool call"

    ```json
    {
      "name": "detect_dependency_conflicts",
      "arguments": {
        "projectDir": "/path/to/my-project",
        "scope": "all"
      }
    }
    ```

`scope` may be `maven`, `gradle`, `sbt`, or `all` (the default).

## Check Java compatibility

> "Is /path/to/my-project compatible with Java 17?"

=== "Tool call"

    ```json
    {
      "name": "check_java_compatibility",
      "arguments": {
        "projectDir": "/path/to/my-project",
        "targetJavaVersion": "17"
      }
    }
    ```

Omit `targetJavaVersion` to check against the project's configured target.

---

For the precise inputs and outputs of every tool, continue to the
[Tools / MCP API reference](../reference/tools.md).
