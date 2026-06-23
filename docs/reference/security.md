# Security

This server executes build commands (Maven, Gradle, SBT) on behalf of LLM agents. Security is a
first-class concern, addressed through **defense in depth** — several independent layers that
constrain *what* can run before any process is spawned.

## Threat model in one line

The server defends against **malicious input injection**, not against intentional misuse by a
trusted operator. An agent can ask for `mvn clean` (an allowed, intentional operation that deletes
`target/`) but it **cannot** inject shell commands or behaviour-altering flags.

## Defense in depth

```text
request ─▶ ① command allowlist ─▶ ② dangerous-flag blocking ─▶ ③ safe-argument pattern
        ─▶ ④ input validation & path canonicalisation ─▶ ⑤ process isolation ─▶ build
```

### Layer 1 — Command allowlist

Only predefined build tasks are permitted; unknown commands are rejected before a process is
spawned.

| Build tool | Allowed commands |
|------------|------------------|
| **Maven** | `clean`, `compile`, `test`, `package`, `install`, `deploy`, `validate`, `dependency:tree` |
| **Gradle** | `clean`, `build`, `test`, `compileJava`, `compileTestJava`, `jar`, `assemble`, `check`, `publishToMavenLocal`, `dependencies`, `projects`, `tasks` |
| **SBT** | `compile`, `test`, `run`, `package`, `clean`, `assembly`, `publishLocal`, `publish`, `update`, `doc`, `console` |

### Layer 2 — Dangerous-flag blocking

Flags that enable arbitrary code execution or file access are blocked.

| Build tool | Blocked flags |
|------------|---------------|
| **Gradle** | `--init-script`/`-I`, `--build-file`/`-b`, `--project-dir`/`-p`, `--include-build`, `--system-prop`/`-D` |
| **SBT** | `-D`, `-J`, `-sbt-dir`, `-sbt-boot`, `-sbt-launch-dir`, `-ivy`, `-maven-launcher` |
| **Maven** | Behaviour-altering `-D` system properties are denied by key (see below). |

#### Maven `-D` system-property deny-list

The safe-argument pattern accepts any well-formed `-Dkey=value`, so a targeted deny-list rejects
these keys (**case-sensitive, exact match**):

| Denied key | Why it is denied |
|------------|------------------|
| `maven.ext.class.path` | Injects extension classes into the Maven core class loader (arbitrary code execution). |
| `maven.repo.local` | Redirects the local artifact repository (dependency substitution / cache poisoning). |
| `maven.multiModuleProjectDirectory` | Overrides multi-module project-root detection, changing which configuration applies. |

Keys that merely *contain* a denied name as a prefix or suffix (e.g. `maven.repo.local.backup`)
are **not** blocked. A rejected token raises `IllegalArgumentException` (`Blocked system property:
…`) before any process is spawned. Implemented in `MavenInvoker.getCommands(...)`; asserted by
`MavenSecurityTest`.

### Layer 3 — Safe-argument pattern

Every command token is validated against a regex that rejects shell metacharacters: `&&`, `|`,
`;`, `$()`, backticks, `>`, `<`, `>>`.

### Layer 4 — Input validation and path canonicalisation

- 500-character command-length limit.
- `Path.toRealPath()` canonicalisation prevents directory traversal (`../`).
- Directory-existence checks before invocation.
- Null/empty input rejection.

### Layer 5 — Process isolation

- **Maven:** out-of-process via the Maven Shared Invoker.
- **Gradle:** `ProcessBuilder` with `--no-daemon`.
- **SBT:** `ProcessBuilder` with `--no-colors`.

No persistent build daemons — each execution spawns a fresh process. Synchronous builds run with an
execution timeout, and stdout/stderr are drained concurrently to avoid pipe-buffer deadlocks.

## What the server does **not** protect against

It protects against malicious *input*, not intentional misuse by a trusted LLM operator. A request
for `mvn clean` is honoured because it is an allowed build operation; the server does not try to
judge intent — it ensures only allowlisted, injection-free commands ever run.

## Transport security

The server supports two transports with different attack surfaces.

=== "stdio (default)"

    - No network port, no HTTP endpoint, no TLS.
    - Runs locally between the MCP client and the server process.
    - Attack surface: MCP JSON-RPC messages (stdin/stdout), filesystem paths, spawned processes.

=== "Streamable HTTP (opt-in)"

    - Enabled via the `http` Spring profile; an embedded servlet container listens on `server.port`
      (default `8080`), with SSE, CORS, and health endpoints.
    - Additional attack surface: network exposure, CORS misconfiguration, unauthenticated endpoints.
    - **Deploy behind a TLS-terminating reverse proxy** for production HTTPS and restrict
      `mcp.transport.cors.allowed-origins` to known domains.

## Configuration hardening

The shipped properties already harden the stdio transport:

| Property | Value | Effect |
|----------|-------|--------|
| `spring.main.web-application-type` | `none` | No web server is started by default. |
| `spring.main.banner-mode` | `off` | Clean stdout (no banner to corrupt the protocol). |
| `logging.level.org.springframework` | `WARN` | Minimal log noise. |
| `spring.jackson.deserialization.fail-on-unknown-properties` | `false` | Forward-compatible MCP parsing. |

## Authorization (optional)

Authorization is **off by default**. When enabled (`buildtools.auth.enabled=true`), MCP clients
must present a valid API key with the appropriate scopes. The model maps onto OAuth/OIDC concepts:

| Concept | Implementation |
|---------|----------------|
| OAuth bearer token | `BUILDTOOLS_API_KEY_*` environment variables (or `buildtools.api.key.*` properties) |
| OIDC scopes | 12 fine-grained scopes (see below) |
| Token validation | Constant-time hashed comparison; tokens are never logged or stored |
| Audit logging | `ToolAuditLogger` (OWASP-MCP06 style, newline-delimited JSON) |
| Permission model | Scope-based, with a wildcard `*` for full access |

### Authorization modes

| Mode | Behaviour |
|------|-----------|
| `permissive` (default) | Warn on unauthorized calls, but allow them. |
| `enforcing` | Deny unauthorized calls. |

A key configured **without** scopes defaults to `*` (full access). Configure scopes explicitly to
apply least privilege.

### Authorization scopes

The 12 scopes and the tools they grant (from the `ToolPermission` enum):

| Scope | Grants access to |
|-------|------------------|
| `build:read` | `get_build_tool_version`, `list_build_tools`, `detect_build_tool`, `validate_build_configuration` |
| `build:execute` | `execute_build_command` |
| `build:profile` | `profile_build`, `analyze_build_performance` |
| `dependency:read` | `check_dependency_version`, `list_dependencies` |
| `dependency:manage` | `detect_dependency_conflicts` |
| `credential:read` | `check_credential_status` |
| `java:read` | `check_java_compatibility` |
| `sbt:read` | `check_sbt_project`, `list_sbt_modules` |
| `sbt:execute` | `execute_sbt_command` |
| `prompt:read` | `get_build_tool_prompt` |
| `resource:read` | `list_build_resources`, `read_build_resource`, `list_dependency_resources`, `read_dependency_resource` |
| `resource:template` | `list_resource_templates`, `get_resource_template` |

!!! note "Inspecting authorization at runtime"
    The `ToolAuthorizationService` exposes tools to work with this model:
    `check_tool_authorization`, `list_available_scopes`, `audit_tool_access`, and
    `validate_access_token`. See the [Tools reference](tools.md#toolauthorizationservice).

## Audit logging

Audit logging is **on by default** (`buildtools.audit.enabled=true`). Tool invocations are written
as newline-delimited JSON to `buildtools.audit.path` (default `~/.buildtools/audit.log`). Recent
entries can be read back with the `audit_tool_access` tool, filtered by `all`, `authorized`, or
`denied`.

## Credential handling

`check_credential_status` scans Maven `settings.xml` and Gradle properties for configured
credentials and reports gaps and recommendations. **All passwords are masked** (e.g. `****xyz`)
and are never returned in plaintext.

## Tested attack surface

The test suite covers shell injection, path traversal, blocked plugin goals, behaviour-altering
Maven `-D` system properties, Unicode / zero-width attacks, null-byte injection, denial-of-service
via long inputs, dangerous Gradle/SBT flags, and MCP protocol compliance. Representative test
classes include `MavenSecurityTest`, `GradleServiceTest`, `SbtBuildToolTest`, `MavenInvokerTest`,
and `MavenIntegrationTest`.

## Reporting a vulnerability

!!! danger "Do not open a public issue for security reports"
    Do **not** file a public GitHub issue for a vulnerability. Follow the disclosure process in the
    repository's [`SECURITY.md`](https://github.com/thepragmatik/mcp-server-jvm-build-tools/blob/main/SECURITY.md),
    which describes private reporting, an acknowledgement window, and a responsible-disclosure
    timeline. Include reproduction steps, the affected version, impact, and any suggested
    remediation.
