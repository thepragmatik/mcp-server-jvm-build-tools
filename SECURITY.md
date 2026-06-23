# Security Policy — mcp-server-jvm-build-tools

## Overview

This MCP server executes build commands (Maven, Gradle, SBT) on behalf of LLM agents over stdio transport. Security is a first-class concern. The architecture uses defense in depth — multiple independent layers.

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.1.x   | Yes       |
| < 0.1.0 | No        |

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
| Maven | Behavior-altering `-D` system properties are denied by key (see below). Safe flags: `-D` (non-denied keys), -f, -P, -q, -X, -T, -B, -U, --batch-mode, --non-recursive |

#### Maven `-D` system-property deny-list

The safe-argument pattern (Layer 3) accepts any well-formed `-Dkey=value`, so it
cannot, on its own, prevent an operator from setting behavior-altering Maven
system properties. A targeted deny-list rejects the following property **keys**
(the part between `-D` and `=`), with or without a value:

| Denied `-D` key | Why it is denied |
|-----------------|------------------|
| `maven.ext.class.path` | Injects extension classes into the Maven core class loader, enabling arbitrary code execution. |
| `maven.repo.local` | Redirects the local artifact repository, allowing dependency substitution / cache poisoning. |
| `maven.multiModuleProjectDirectory` | Overrides multi-module project-root detection, changing which configuration is applied. |

Matching is **case-sensitive** and **exact** on the key, consistent with Maven's
own case-sensitive system-property handling. Keys that merely contain a denied
name as a prefix or suffix (e.g. `maven.repo.local.backup`) are **not** blocked.
A rejected token raises `IllegalArgumentException` ("Blocked system property: …")
before any process is spawned. Implemented in `MavenInvoker.getCommands(...)`;
see `MavenSecurityTest` for the asserting tests.

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

375 tests covering: shell injection, path traversal, blocked plugin goals, behavior-altering Maven `-D` system properties, Unicode/zero-width attacks, null-byte injection, DoS via long inputs, dangerous Gradle/SBT flags, MCP protocol compliance.

Test files: MavenSecurityTest.java, GradleServiceTest.java, SbtBuildToolTest.java, MavenInvokerTest.java, MavenIntegrationTest.java.

## Transport Security

The server supports two transport modes with different attack surfaces:

- **stdio** — Default. No network port, no HTTP endpoint, no TLS. Attack surface: MCP JSON-RPC messages (stdin/stdout), filesystem paths, spawned processes.

- **Streamable HTTP** — Enabled via `--http` flag. HTTP server on port 8081 (configurable) with SSE, CORS, health endpoints. Additional attack surface: network exposure, CORS misconfiguration, unauthenticated endpoints. Deploy behind a TLS-terminating reverse proxy for production HTTPS.

## Configuration Hardening

- spring.main.web-application-type=none (no web server)
- spring.main.banner-mode=off (clean stdio)
- logging.level.org.springframework=WARN (minimal noise)
- spring.jackson.deserialization.fail-on-unknown-properties=false (MCP forward-compat)

## Security Update Process

1. Identify vulnerable dependencies
2. PR with fix: conventional commit
3. CI matrix (JDK 21, 23, 25)
4. Worker-adversarial security review
5. Squash-merge to staging, then integration PR to main
