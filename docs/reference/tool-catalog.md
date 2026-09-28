# Current MCP tool catalog

This is the public tool catalog exposed by `tools/list` in the 2.0 development
line. It is generated from the application-wired tool callback provider, using
the same safe descriptions sent to MCP clients. The scope column comes from
`ToolPermission`. A future tool change must update this page deliberately;
`mvn verify` checks it against the running application.

The server currently exposes **24 tools**. Path-bearing calls require an
allowed project root. HTTP `tools/call` requests require an authorized
bearer key with the listed scope. See the [quickstart](../user-guide/quickstart-v2.md)
and [2.0 security design](design-v2.md) before granting execution access.

| Tool | Required scope | Public result contract |
|------|----------------|------------------------|
| `analyze_build_output` | `build:execute` | Run build analysis and return test counts with at most 12 structured, redacted diagnostics (severity, category, diagnosticRef, optional fileRef, file type and line, normalized message), not raw logs. Use for triage; inspect local files for edits. |
| `analyze_build_performance` | `build:read` | Return tracked-build and suggestion counts with an optimization potential level; raw suggestions are withheld. |
| `analyze_pom_dependencies` | `dependency:read` | Return dependency, managed-entry, and BOM counts; coordinates and per-dependency classifications are withheld. |
| `analyze_sbt_build` | `sbt:read` | Return Scala and sbt versions without organization or plugin details. |
| `check_dependency_version` | `dependency:read` | Return available version numbers and upgrade status without dependency identifiers. |
| `check_java_compatibility` | `java:read` | Return a compatibility verdict and issue count without dependency details. |
| `check_tool_authorization` | `security:read` | Return an authorization decision without credential identities. |
| `detect_build_tool` | `build:read` | Detect Maven, Gradle, or sbt; return names and count without project paths. |
| `detect_dependency_conflicts` | `dependency:read` | Return conflict and analyzed-file counts without dependency identifiers. |
| `detect_sbt_modules` | `sbt:read` | Return module count and structure flags without module names. |
| `detect_sbt_test_frameworks` | `sbt:read` | Return framework count and configuration flags without framework names. |
| `execute_build_command` | `build:execute` | Execute an allowed build command and return status with at most 12 structured, redacted diagnostics (severity, category, diagnosticRef, optional file type and line, normalized message), not raw logs. Use for triage; inspect local files for edits. |
| `get_build_tool_version` | `build:read` | Return a build-tool version number without host details. |
| `list_available_scopes` | `security:read` | Return the names of public permission scopes. |
| `list_build_resources` | `resource:read` | Return resource count and kind names without resource URIs or contents. |
| `list_build_tools` | `build:read` | List supported build-tool names only. |
| `list_dependency_resources` | `resource:read` | Return resource count and available build-tool names without dependency identities. |
| `profile_build` | `build:execute` | Run a build and return duration and phase counts without raw phase details. |
| `prompt_build_and_test` | `prompt:read` | Return a server-authored build-and-test workflow without echoing user inputs. |
| `prompt_build_diagnosis` | `prompt:read` | Return a server-authored diagnosis workflow without echoing user inputs. |
| `prompt_dependency_audit` | `prompt:read` | Return a server-authored dependency-audit workflow without echoing user inputs. |
| `scan_dependency_cves` | `dependency:read` | Return scanned, vulnerable, critical, and high counts with redacted warnings; dependency and CVE identities are withheld. |
| `validate_build_configuration` | `build:read` | Return validity, counts, and bounded redacted diagnostics. |
| `validate_ci_flow` | `ci:read` | Return validity with bounded redacted errors and warnings; raw configuration is withheld. |

For exact input parameters and JSON schemas, ask the running server for
`tools/list`; that response is authoritative for the version you installed.
In builds after `v2.0.0-rc.1`, `analyze_build_output` also advertises an
`outputSchema` and returns the same safe JSON object in `structuredContent`
and legacy text; see
[structured build results](structured-build-results.md).
Results are bounded and privacy-filtered. Raw build logs and commands stay local;
diagnostics include at most 12 structured, redacted entries. The older
[1.x tool reference](tools.md) is retained as an archive and does not describe
the current public surface.
