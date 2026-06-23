# Overview

## What this server does

`mcp-server-jvm-build-tools` is a [Model Context Protocol](https://spec.modelcontextprotocol.io)
server that lets AI coding agents **execute and inspect JVM builds**. It speaks the MCP
protocol over **stdio** (and, optionally, Streamable HTTP), and exposes a curated set of
tools for **Maven**, **Gradle**, and **SBT**.

An agent connected to the server can:

- Detect which build tool a project uses and list available commands.
- Run safe build commands (`clean`, `compile`, `test`, `package`, …) and read the output.
- Get **structured JSON** results — test counts, compile errors with `file:line`, and warnings.
- Validate `pom.xml` / `build.gradle` / `build.gradle.kts` statically, without running a build.
- Look up the latest version of a Maven Central dependency and detect version conflicts.
- Check Java/JDK compatibility, scan for build credential configuration, and analyse SBT projects.
- Retrieve build/dependency **resources** and **prompt templates** to guide multi-step workflows.

## Who it's for

This server is for developers who use AI coding agents — Claude Desktop, Cursor, Cline,
Windsurf, Goose, Continue, GitHub Copilot, and any other MCP-capable client — and who want
those agents to **interact with real build tooling** rather than only generating code.

## How it works at a glance

```text
┌──────────────────────────┐   JSON-RPC (MCP)   ┌────────────────────────────┐
│  MCP client (the agent)  │ ◀────────────────▶ │  mcp-server-jvm-build-tools │
│  Claude Desktop, Cursor… │      over stdio    │  (Spring Boot + Spring AI)  │
└──────────────────────────┘                    └──────────────┬─────────────┘
                                                               │ spawns isolated processes
                                       ┌───────────────────────┼───────────────────────┐
                                       ▼                       ▼                       ▼
                                  ┌─────────┐            ┌──────────┐            ┌─────────┐
                                  │  Maven  │            │  Gradle  │            │   SBT   │
                                  │ invoker │            │ --no-daemon            │--no-colors
                                  └─────────┘            └──────────┘            └─────────┘
```

1. The agent connects to the server and calls `tools/list` to discover available tools.
2. When the agent calls a tool (for example `execute_build_command`), the server validates
   the request, resolves the right build tool (explicitly or by auto-detection), and runs it
   in an **isolated child process**.
3. The build's output is returned to the agent — either as raw text or parsed into JSON.

See the [Architecture reference](../reference/architecture.md) for the full component map and
request flow.

## Design principles

!!! abstract "Unified, not lowest-common-denominator"
    The server presents a consistent tool surface across Maven, Gradle, and SBT, while still
    honouring each tool's real command syntax (Maven space-separated phases, Gradle tasks, SBT
    semicolon-chained tasks).

!!! abstract "Secure by construction"
    Builds execute external processes, so security is a first-class concern. A
    [five-layer defense model](../reference/security.md) constrains *what* can run before any
    process is ever spawned.

!!! abstract "Local-first"
    The default **stdio** transport runs entirely on your machine: no listening port, no TLS,
    no cloud dependency. The attack surface is limited to MCP JSON-RPC messages, filesystem
    paths, and spawned build processes.

!!! abstract "Stateless and concurrent-friendly"
    Each tool call carries its own `projectDir` and is independent. One agent can build a Maven
    project and then a Gradle project with no shared state between calls.

## Supported build tools

| Build tool | Detected by | Execution method | Installation needed? |
|------------|-------------|------------------|----------------------|
| **Maven**  | `pom.xml` | Maven Shared Invoker (out-of-process); Maven Embedder for version queries | Yes — a Maven install via `MAVEN_HOME` or `buildToolHome` |
| **Gradle** | `build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts` | `ProcessBuilder` with `--no-daemon --console=plain`; auto-detects the `gradlew` wrapper | No — uses the wrapper, falls back to `gradle` on `PATH` |
| **SBT**    | `build.sbt` | `ProcessBuilder` with `--no-colors`; auto-detects an `sbt` wrapper | No — falls back to `sbt` on `PATH` |

## What it deliberately does **not** do

- It is **not** a remote build farm. There is no persistent daemon; each command spawns a fresh process.
- It does **not** protect against a trusted operator deliberately running a legitimate build
  command (for example, `mvn clean` removes the `target/` directory). It protects against
  *injection* and *unsafe flags*, not intentional, allowed build operations.
- It does **not** require network access for its core stdio operation; only
  `check_dependency_version` reaches out to Maven Central.

Ready to install? Head to the [Installation guide](installation.md).
