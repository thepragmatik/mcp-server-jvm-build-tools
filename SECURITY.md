# Security Policy — mcp-server-jvm-build-tools

## Overview

This MCP server executes build commands (Maven, Gradle, SBT) on behalf of LLM agents over stdio transport. Security is a first-class concern. The architecture uses defense in depth — multiple independent layers.

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.2.x   | Yes       |
| < 0.2.0 | No        |

Only the latest release receives security patches.

## Reporting a Vulnerability

Do NOT open a public GitHub Issue for security vulnerabilities.

Email security disclosures to the repository maintainer. Include: steps to reproduce, affected version, potential impact, suggested remediation.

Acknowledgment within 72 hours. 90-day responsible disclosure window.

## Security Model: Defense in Depth

### Layer 1: Command Allowlist

Only predefined build tasks are permitted. Unknown commands rejected before spawning.

| Build Tool | Allowed Commands |
|------------|-----------------|
| Maven | clean, compile, test, package, install, deploy, validate, dependency:tree |
| Gradle | clean, build, test, compileJava, compileTestJava, jar, assemble, check, publishToMavenLocal, dependencies, projects, tasks |
| SBT | compile, test, run, package, clean, assembly, publishLocal, publish, update, doc, console |

### Layer 2: Dangerous Flag Blocking

| Build Tool | Blocked Flags |
|------------|--------------|
| Gradle | --init-script/-I, --build-file/-b, --project-dir/-p, --include-build, --system-prop/-D |
| SBT | -D, -J, -sbt-dir, -sbt-boot, -sbt-launch-dir, -ivy, -maven-launcher |
| Maven | None — `-D` system properties are passed through; the server trusts the client's choices (see below). Safe flags: `-D`, -f, -P, -q, -X, -T, -B, -U, --batch-mode, --non-recursive |

#### Maven `-D` system properties

Maven `-D` system properties are passed through verbatim. There is no key
allowlist or blocklist: the server trusts the client's `-D` choices entirely.
Shell metacharacters in any token are still rejected by the safe-argument
pattern (Layer 3), so injection via `-D` values is not possible.

### Layer 3: Safe-Argument Pattern

All command tokens validated against regex blocking shell metacharacters: &&, |, ;, $(), backticks, >, <, >>.

### Layer 4: Input Validation and Path Canonicalization

- 500-char command length limit
- Path.toRealPath() canonicalization prevents directory traversal
- Directory existence checks before invocation
- Null/empty input rejection

### Layer 5: Process Isolation

- Maven: Out-of-process via Maven Shared Invoker
- Gradle: ProcessBuilder with --no-daemon
- SBT: ProcessBuilder with --no-colors

No persistent build daemons. Each execution spawns a new process.

## What the Server Does NOT Protect Against

Protects against malicious input injection, not intentional misuse by a trusted LLM operator. An LLM can request 'mvn clean' (intentional) but cannot inject shell commands.

## Attack Surface Tested

The full suite covers: shell injection, path traversal, blocked plugin goals, Unicode/zero-width attacks, null-byte injection, DoS via long inputs, dangerous Gradle/SBT flags, MCP protocol compliance.

Test files: MavenSecurityTest.java, GradleServiceTest.java, SbtBuildToolTest.java, MavenInvokerTest.java, MavenIntegrationTest.java.

## Transport Security

The server supports two transport modes with different attack surfaces:

- **stdio** — Default. No network port, no HTTP endpoint, no TLS. Attack surface: MCP JSON-RPC messages (stdin/stdout), filesystem paths, spawned processes.

- **Streamable HTTP** — Opt-in via the `http` Spring profile (the launcher's `--http` flag). An embedded servlet container listens on `server.port` (default `8080`) with SSE, CORS, and health endpoints. Additional attack surface: network exposure, CORS misconfiguration, unauthenticated endpoints. **Deploy behind a TLS-terminating reverse proxy** for production HTTPS.

### Safe HTTP defaults

The HTTP transport ships with hardened defaults so that enabling it does not
silently widen the attack surface:

- **Restricted CORS (no wildcard).** Cross-origin access defaults to local
  origins only — `mcp.transport.cors.allowed-origins=http://localhost:8080,http://127.0.0.1:8080`.
  This is enforced in code via `allowedOrigins(...)` (exact match), not
  `allowedOriginPatterns("*")`.
  - **Widen for development** by listing specific origins, e.g.
    `mcp.transport.cors.allowed-origins=https://dashboard.example.com`.
  - A wildcard (`mcp.transport.cors.allowed-origins=*`) is honoured **for local
    testing only** and is applied via `allowedOriginPatterns` to remain valid
    alongside credentialed requests. Never use `*` in production.
- **Health details gated.** `management.endpoint.health.show-details=when-authorized`
  so unauthenticated callers see only `UP`/`DOWN`, never component-level
  internals (disk paths, dependency status, etc.). Because Spring Security is not
  on the classpath by default, no principal is ever authorized, so details are
  hidden from everyone (effectively `never`) until `spring-boot-starter-security`
  is added and roles are configured. Set it to `always` only for trusted local
  debugging.

### OAuth 2.1 resource-server posture

The MCP authorization spec profiles a protected MCP server as an **OAuth 2.1 resource
server** (RFC9728 Protected Resource Metadata, RFC6750 `WWW-Authenticate` challenges,
access-token validation). This server aligns with those **server-side** obligations
**additively and backward-compatibly** (see `docs/AUTHORIZATION.md` for the full
recorded decision and threat model):

- **Protected Resource Metadata (RFC9728)** is served at
  `/.well-known/oauth-protected-resource` under the HTTP profile — a new, additive
  discovery surface. `scopes_supported` lists the fine-grained `ToolPermission`
  scopes; per the spec, **`offline_access` is never advertised**.
- **Bearer-token enforcement is opt-in** via
  `buildtools.oauth.resource-server.enabled` (default `false`). When enabled, a
  missing/invalid `Authorization: Bearer` token on `/mcp/**` is answered `401` with
  `WWW-Authenticate: Bearer resource_metadata="..."`. `server/discover` stays open so
  clients can learn how to authenticate. With it disabled (the default), behaviour is
  unchanged for existing clients.
- **Tokens are validated locally** against the configured `BUILDTOOLS_API_KEY_*`
  credentials (opaque bearer tokens). Full authorization-server / JWT-JWKS validation
  is deliberately delegated to a fronting OAuth gateway in production.
- **The `dev-key-unsafe-do-not-use-in-production` default is guarded**: it is never
  created under a production profile (`prod`/`production`) or when
  `buildtools.auth.mode=enforcing`, so the unsafe default can never be live in a
  hardened deployment.

## Configuration Hardening

- spring.main.web-application-type=none (no web server unless the `http` profile is active)
- spring.main.banner-mode=off (clean stdio)
- logging.level.org.springframework=WARN (minimal noise)
- spring.jackson.deserialization.fail-on-unknown-properties=false (MCP forward-compat)
- mcp.transport.cors.allowed-origins=http://localhost:8080,http://127.0.0.1:8080 (restricted CORS; no wildcard by default)
- management.endpoint.health.show-details=when-authorized (no health internals to unauthenticated callers; hidden from everyone until Spring Security is added)
- buildtools.oauth.resource-server.enabled=false (bearer-token enforcement is opt-in; RFC9728 Protected Resource Metadata is served additively)
- dev-key-unsafe-do-not-use-in-production default key is never created under a `prod`/`production` profile or `buildtools.auth.mode=enforcing`

## Dependency & Supply-Chain Scanning

The server holds its **own** dependencies to the same bar as the SBOM /
supply-chain tooling it ships to users (issue #78). Two automated, complementary
mechanisms run in CI; see [docs/DEPENDENCY_MANAGEMENT.md](docs/DEPENDENCY_MANAGEMENT.md)
for the full design and the recorded decisions.

- **OWASP dependency-check** matches every resolved dependency against the NVD
  and **fails the build on any High/Critical (CVSS ≥ 7) vulnerability**. It runs
  in a dedicated workflow (`.github/workflows/dependency-check.yml`) weekly, on
  manual dispatch, and on PRs/pushes that change the dependency surface. It is
  gated behind the opt-in `owasp` Maven profile so it never slows the default
  build; run it locally with `./mvnw -Powasp verify`. Documented, expiring
  suppressions live in `owasp-suppressions.xml`.
- **Dependabot** (`.github/dependabot.yml`) opens weekly upgrade PRs for the
  `maven` and `github-actions` ecosystems, which then pass through the same CI
  gates (including the OWASP scan) and the standard review process.

### Pre-GA dependency tracking (Spring AI)

The MCP integration depends on Spring AI, currently pinned to the pre-GA
`2.0.0-RC2`. The decision — stay on the latest 2.0.0 RC, upgrade to **GA as a P1
follow-up when released**, keep the pin centralised in one `spring-ai.version`
property, and de-risk via the OWASP scan plus the full test suite — is recorded
in [docs/DEPENDENCY_MANAGEMENT.md](docs/DEPENDENCY_MANAGEMENT.md). Dependabot's
dedicated `spring-ai` group surfaces the GA upgrade PR automatically.

## Security Update Process

1. Identify vulnerable dependencies (automated: OWASP dependency-check + Dependabot — see above)
2. PR with fix: conventional commit
3. CI matrix (JDK 21, 23, 25)
4. Worker-adversarial security review
5. Squash-merge to staging, then integration PR to main
