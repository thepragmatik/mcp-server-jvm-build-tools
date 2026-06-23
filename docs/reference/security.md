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
| **Maven** | None — `-D` system properties are passed through (see below). |

#### Maven `-D` system properties

Maven `-D` system properties are passed through verbatim — there is no key allowlist or
blocklist. The server trusts the client's `-D` choices entirely. Shell metacharacters in any
token are still rejected by the safe-argument pattern (Layer 3), so injection via `-D` values is
not possible. Implemented in `MavenInvoker.getCommands(...)`; asserted by `MavenSecurityTest`.

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
    - **Safe defaults:** CORS is restricted to local origins
      (`http://localhost:8080,http://127.0.0.1:8080`) — no wildcard — and
      `management.endpoint.health.show-details=when-authorized`, so unauthenticated
      callers never see component-level health internals. Note: with no Spring
      Security on the classpath, no principal is ever authorized, so details are
      hidden from everyone (effectively `never`) until `spring-boot-starter-security`
      is added and roles are configured. A wildcard origin (`*`)
      is honoured for local testing only and must never be used in production.

## Header validation (Streamable HTTP)

The 2026-07-28 RC requires the standard request headers `Mcp-Method` and `Mcp-Name` on Streamable
HTTP POSTs (SEP-2243). `McpHeaderValidationFilter` (servlet filter, `@Order(2)`) defends against
**header/body mismatch attacks** on `POST /mcp/**`: when those headers are present and *contradict*
the JSON-RPC body's method, or `Mcp-Name` contradicts the server's own identity, the request is
rejected with `400` + a JSON-RPC `HeaderMismatchError`. This prevents a proxy/router from acting on
a header (e.g. for routing or policy) that disagrees with what the server will actually execute.

The check is **purely additive and backward-compatible**:

- **Absent** headers pass through — older clients (2024-11-05, 2025-03-26) that do not send them are
  unaffected.
- **Matching** headers pass through.
- Unparseable bodies are left to the transport — only a genuine self-contradiction is rejected.

The server's single identity (`Mcp-Name`, protocol versions, capabilities) is sourced from
`McpServerIdentity` and surfaced consistently on the server card and `server/discover`, so the
header check cannot drift from what discovery advertises.

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

## OAuth 2.1 resource server (HTTP transport)

Under the `http` profile the server aligns with the **MCP OAuth 2.1 resource-server model** — the
spec profiles an MCP server as an OAuth 2.1 resource server (RFC9728 Protected Resource Metadata,
RFC6750 `WWW-Authenticate` challenges, access-token validation). The alignment is **additive and
backward-compatible**:

1. **Protected Resource Metadata (RFC9728)** is **always** served at
   `GET /.well-known/oauth-protected-resource` (`OAuthProtectedResourceMetadataController`).
   OAuth-capable clients can discover the resource server; clients that do not speak OAuth simply
   never request it. `scopes_supported` is derived from the fine-grained `ToolPermission` scopes,
   and `offline_access` is never advertised.
2. **Bearer-token enforcement is opt-in** (`buildtools.oauth.resource-server.enabled`, default
   `false`). When enabled, `OAuthResourceServerFilter` (`@Order(1)`) requires a valid
   `Authorization: Bearer <token>` on `/mcp/**`; a missing/invalid token returns `401` with an
   RFC6750 `WWW-Authenticate: Bearer error="invalid_token", … resource_metadata="…"` challenge.
   The `server/discover` probe is exempt so clients can always learn how to authenticate. With
   enforcement off, request behaviour is byte-for-byte unchanged for existing clients.
3. **Tokens are validated locally** as opaque bearer credentials against the configured
   `BUILDTOOLS_API_KEY_*` store (`ToolAuthorizationService`). This is a deliberate, RFC9728-blessed
   topology: the resource server advertises metadata and accepts validated bearer tokens, while the
   heavyweight authorization-server work is delegated to a fronting OAuth gateway.

```
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer error="invalid_token", error_description="The access token is invalid or expired", resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource"
```

!!! warning "Delegated to the deployment"
    Local opaque-token validation **deliberately diverges** from full authorization-server
    integration. Token issuance, rotation, revocation and expiry; JWT/JWKS verification and
    audience/`iss` binding; transport confidentiality (TLS); and replay/mTLS protections are
    **out of scope for the resource server** and must be handled by a fronting OAuth-aware
    gateway / reverse proxy. A leaked opaque key grants its scopes until rotated
    (`BUILDTOOLS_API_KEY_*` + redeploy). Enable enforcement whenever the HTTP transport faces
    anything other than a trusted local caller. See the configuration
    [reference](configuration.md#oauth-21-resource-server-http-transport).

!!! note "stdio transport has no token surface"
    The default **stdio** transport has no network surface — no HTTP, no port, no tokens — so the
    OAuth resource-server model applies only to the opt-in Streamable HTTP transport.

## W3C Trace Context propagation

For distributed tracing the server adopts the W3C Trace Context / OpenTelemetry `_meta` conventions
(SEP-414), reading the exact key names `traceparent`, `tracestate`, and `baggage`. A span is opened
around every build (`BuildTracer`), and the active span is propagated to the build subprocess via
the conventional `TRACEPARENT` / `TRACESTATE` / `BAGGAGE` environment variables.

This is **additive and opt-in at the protocol level, with no regression for untraced builds**:

- An **inbound** trace is *continued* only when a request carries a valid `traceparent` in `_meta`
  (or the server's own environment already carries a `TRACEPARENT` from a host/CI runner). When it
  does, the build joins that existing trace — same `traceId`, with the server's span nested inside
  — so a host/CI-propagated trace is **preserved, never reparented** onto an unrelated root.
- A build with **no** inbound or inherited context simply starts a fresh, sampled **root** span, so
  it stays independently traceable. The server never clobbers a host trace and never corrupts an
  untraced build's correlation.
- The active span is stamped onto each build subprocess via `TRACEPARENT` / `TRACESTATE` /
  `BAGGAGE`; `TRACESTATE` / `BAGGAGE` are *removed* when the span carries none, so a child can never
  inherit an orphaned value mismatched with a different `TRACEPARENT`. Outside a build (no active
  span) nothing is injected.
- Clients that send no trace context — including existing clients unaware of `_meta` — see identical
  protocol behaviour and responses; trace propagation never changes a tool's result.

Span parentage is resolved deterministically: an active (nested) span → the request `_meta` context
→ an inherited environment `TRACEPARENT` → otherwise a fresh, sampled root span.

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

The test suite covers shell injection, path traversal, blocked plugin goals, Unicode / zero-width
attacks, null-byte injection, denial-of-service via long inputs, dangerous Gradle/SBT flags, and
MCP protocol compliance. Representative test
classes include `MavenSecurityTest`, `GradleServiceTest`, `SbtBuildToolTest`, `MavenInvokerTest`,
and `MavenIntegrationTest`.

## Reporting a vulnerability

!!! danger "Do not open a public issue for security reports"
    Do **not** file a public GitHub issue for a vulnerability. Follow the disclosure process in the
    repository's [`SECURITY.md`](https://github.com/thepragmatik/mcp-server-jvm-build-tools/blob/main/SECURITY.md),
    which describes private reporting, an acknowledgement window, and a responsible-disclosure
    timeline. Include reproduction steps, the affected version, impact, and any suggested
    remediation.
