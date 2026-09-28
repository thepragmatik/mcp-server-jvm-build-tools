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
- **DNS rebinding guard.** Every `/mcp` request checks `Origin` when supplied
  and returns 403 for an unapproved origin. A loopback bind also permits only
  local `Host` names by default; local reverse proxies can add their hostname
  through `mcp.transport.allowed-hosts`. A missing `Origin` remains valid for
  CLI clients. This check applies with or without bearer authentication.
- **Health details gated.** `management.endpoint.health.show-details=when-authorized`
  so unauthenticated callers see only `UP`/`DOWN`, never component-level
  internals (disk paths, dependency status, etc.). Because Spring Security is not
  on the classpath by default, no principal is ever authorized, so details are
  hidden from everyone (effectively `never`) until `spring-boot-starter-security`
  is added and roles are configured. Set it to `always` only for trusted local
  debugging.

### HTTP authentication and OAuth discovery

The HTTP profile requires a configured, scoped opaque API key by default. A missing
key receives `401` with a plain `WWW-Authenticate: Bearer` challenge. Protected
Resource Metadata at `/.well-known/oauth-protected-resource` returns 404 until
`buildtools.oauth.authorization-servers` names an issuer. Once configured, metadata
contains `authorization_servers` and 401 challenges link to it.

This application validates only locally configured opaque keys; issuer configuration
does not add JWT/JWKS, audience, or remote-token validation. A production OAuth gateway
must validate tokens, prevent bypass, and map validated requests to locally recognized
credentials. See the [current HTTP authentication guide](docs/reference/http-authentication.md).

## Configuration Hardening

- spring.main.web-application-type=none (no web server unless the `http` profile is active)
- spring.main.banner-mode=off (clean stdio)
- logging.level.org.springframework=WARN (minimal noise)
- spring.jackson.deserialization.fail-on-unknown-properties=false (MCP forward-compat)
- mcp.transport.cors.allowed-origins=http://localhost:8080,http://127.0.0.1:8080 (restricted CORS; no wildcard by default)
- management.endpoint.health.show-details=when-authorized (no health internals to unauthenticated callers; hidden from everyone until Spring Security is added)
- buildtools.oauth.resource-server.enabled=true in the HTTP profile (scoped bearer keys required); OAuth discovery requires a configured issuer
- dev-key-unsafe-do-not-use-in-production default key is never created under a `prod`/`production` profile or `buildtools.auth.mode=enforcing`

## Dependency & Supply-Chain Scanning

Runtime OSV queries send only bounded Maven group, artifact, and version
coordinates. Coordinates containing whitespace, control characters, quotes, or
unsupported punctuation cause a fixed incomplete scan before the HTTP request.
OSV responses and transport failures do
not cause raw package coordinates or exception messages to be logged.
The scan reads the first present POM or Gradle build file through one held,
no-symlink project directory handle. An unsafe path, unsupported filesystem,
file over 1 MiB, or invalid UTF-8 fails before any OSV request. OSV receives
valid dependency coordinates by design; run the scan only when that egress is
acceptable for the project. The OSV HTTP client does not follow redirects, so
dependency coordinates are not forwarded to a redirect target.

`check_dependency_version` sends Maven group and artifact coordinates to the fixed Maven Central metadata endpoint. It rejects malformed or overlong coordinates before network access, refuses redirects, and accepts at most 1 MiB of strict UTF-8 XML within a ten-second request deadline. DTDs and external entities are disabled; network, parser, and malformed-response errors return fixed text without caller values. Setting `includeSecurityInfo=true` also queries OSV.dev for the supplied version; use that option only when dependency-inventory egress is acceptable.
The MCP result exposes only bounded aggregate status, count, and known/unknown highest severity; individual OSV findings remain local. Single-coordinate OSV severity is derived only from complete CVSS v3.1 base vectors; malformed, unsupported, temporal, or environmental vectors remain unknown. Batch scans still cannot infer severity from sparse OSV matches.
Unexpected OSV response fields fail incomplete, and version strings matching secret-redaction rules are withheld from model-visible results.

The server holds its **own** dependencies to the same bar as the SBOM /
supply-chain tooling it ships to users (issue #78). The keyless
[Dependency Review workflow](.github/workflows/dependency-review.yml) blocks
new moderate-or-higher known vulnerabilities on every PR. On the final default
branch commit, the release engineer runs the fail-closed
[Dependabot alert audit](docs/DEPENDENCY_MANAGEMENT.md#12-final-release-gate-open-dependabot-alerts)
with an authorized GitHub login; any open alert blocks the release. Dependabot
opens weekly Maven, Python documentation, and GitHub Actions update PRs. This
uses GitHub's dependency data without another scan provider or an NVD key.

### Pre-GA dependency tracking (Spring AI)

The MCP integration currently uses Spring AI `2.0.1`. The earlier pre-GA
decision is preserved as history in
[docs/DEPENDENCY_MANAGEMENT.md](docs/DEPENDENCY_MANAGEMENT.md); the GA upgrade
is complete. Future changes to its central `spring-ai.version` property follow
the same dependency review and test gates.

## Security Update Process

1. Identify vulnerable dependencies (PR Dependency Review and Dependabot alert audit — see above)
2. PR with fix: conventional commit
3. CI matrix (JDK 21, 23, 25)
4. Worker-adversarial security review
5. Squash-merge the approved PR to main
