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
| Maven | None blocked. Safe flags: -D, -f, -P, -q, -X, -T, -B, -U, --batch-mode, --non-recursive |

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

307 tests covering: shell injection, path traversal, blocked plugin goals, Unicode/zero-width attacks, null-byte injection, DoS via long inputs, dangerous Gradle/SBT flags, MCP protocol compliance.

Test files: MavenSecurityTest.java, GradleServiceTest.java, SbtBuildToolTest.java, MavenInvokerTest.java, MavenIntegrationTest.java.

## Transport Security

Stdio-only. No network port, no HTTP endpoint, no TLS. Attack surface: MCP JSON-RPC messages, filesystem paths, spawned processes.

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
