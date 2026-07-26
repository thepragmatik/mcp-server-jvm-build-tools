# MCP Server — JVM Build Tools

**One MCP server for all your JVM build tools.** Give any MCP-compatible AI agent
secure, hands-on access to **Maven**, **Gradle**, and **SBT** — compile, test,
package, inspect dependencies, and validate build configuration through a single,
unified interface with automatic project-type detection.

[Get started in 5 minutes :material-rocket-launch:](user-guide/installation.md){ .md-button .md-button--primary }
[Browse the MCP API :material-tools:](reference/tools.md){ .md-button }

---

!!! success "Now available — v1.1.0 :material-rocket-launch:"
    The latest release: **30 MCP tools**, **stdio auto-configuration**, and
    first-class support for **Maven, Gradle, and sbt**.

    [Read the v1.1.0 release notes :material-text-box-outline:](https://github.com/thepragmatik/mcp-server-jvm-build-tools/releases/tag/v1.1.0){ .md-button .md-button--primary }

## What is it?

This project is a [Model Context Protocol](https://spec.modelcontextprotocol.io)
(MCP) server, built with **Spring Boot 4.1.0** and **Spring AI 2.0.0**, that
exposes JVM build operations as MCP tools. Any MCP client — Claude Desktop, Cursor,
Cline, Windsurf, Goose, Continue, GitHub Copilot — can discover and call these tools
to drive real builds on your machine.

Instead of an agent only *generating* build files, it can actually **run** the build,
read the structured results, and act on them — all over a local, network-free
transport.

!!! tip "Why one server for three tools?"
    Maven, Gradle, and SBT each have different invocation models, flags, and output
    formats. This server hides those differences behind a consistent set of tools and
    **auto-detects** the build tool from your project's marker files (`pom.xml`,
    `build.gradle`, `build.sbt`), so you rarely need to specify it by hand.

## What's New in v1.1.0

Version **1.1.0** builds on the stable foundation. Highlights:

-   **30 MCP tools across 12 services** (was 28). Build execution, detection, validation, structured output
    analysis, dependency intelligence, performance profiling, prompts, resources, and optional
    scope-based authorization. See the [Tools / MCP API](reference/tools.md) reference.
-   **MCP stdio transport auto-configuration.** Spring AI 2.0.0 spring-ai-mcp now auto-configures
    stdio transport out of the box — no manual transport wiring needed.
-   **Docker image with JDK 21 + Maven/Gradle/SBT.** Full build toolchain pre-installed in the
    container for instant readiness.
-   **Full test suite: 599 tests, 17 MCP protocol tests.** Comprehensive coverage across unit,
    integration, and MCP protocol layers.
-   **Real-world usability testing with MCP Inspector CLI.** Verified end-to-end with the MCP
    Inspector for stdio transport.

For the complete list, read the
[v1.1.0 release notes](https://github.com/thepragmatik/mcp-server-jvm-build-tools/releases/tag/v1.1.0).

## Key features

<div class="grid cards" markdown>

-   :material-language-java: **Three build tools, one interface**

    Maven, Gradle, and SBT behind a unified `BuildTool` SPI with auto-detection by
    project marker files.

-   :material-shield-check: **Defense-in-depth security**

    A five-layer model — command allowlists, dangerous-flag blocking, shell
    metacharacter rejection, path canonicalization, and process isolation.

-   :material-code-json: **Structured build output**

    `analyze_build_output` parses Maven/Gradle/SBT logs into machine-readable JSON:
    test counts, errors with `file:line`, warnings, and success status.

-   :material-magnify: **Dependency intelligence**

    Query Maven Central for the latest versions, detect version conflicts, and check
    Java/JDK compatibility — with build-tool-aware dependency syntax.

-   :material-transit-connection-variant: **Local-first transport**

    Runs over **stdio** by default — no network port, no TLS, no cloud. An opt-in
    Streamable HTTP transport is available for web deployments.

-   :material-key-variant: **Optional scope-based authorization**

    Fine-grained API-key scopes and per-invocation audit logging, off by default and
    enforced only when you turn it on.

</div>

## Quick start

!!! note "Prerequisites"
    - **Java 21+** (the server targets Java 21).
    - **Apache Maven 3.9+** to build the JAR.
    - An **MCP-compatible client**.

=== "1. Build the JAR"

    ```bash
    git clone https://github.com/thepragmatik/mcp-server-jvm-build-tools.git
    cd mcp-server-jvm-build-tools
    mvn clean package -DskipTests
    ```

    The artifact is produced at `target/mcp-server-jvm-build-tools.jar`.

=== "2. Configure your MCP client"

    ```json
    {
      "mcpServers": {
        "jvm-build-tools": {
          "command": "java",
          "args": [
            "-jar",
            "/absolute/path/to/mcp-server-jvm-build-tools.jar"
          ],
          "env": {
            "MAVEN_HOME": "/opt/maven"
          }
        }
      }
    }
    ```

    Restart the client. The build tools appear automatically.

=== "3. Try it"

    Ask your agent:

    ```text
    What build tools are available?
    Build and test the project at /path/to/my-project
    ```

The full walkthrough — including client-specific configuration and Docker — is in the
[Installation guide](user-guide/installation.md).

## Where to go next

<div class="grid cards" markdown>

-   :material-book-open-variant: **[Overview](user-guide/overview.md)**

    The problem this solves, who it's for, and how the pieces fit together.

-   :material-download: **[Installation](user-guide/installation.md)**

    Prerequisites, building the JAR, and wiring it into every major MCP client.

-   :material-tune: **[Configuration](user-guide/configuration.md)**

    Environment variables, JVM flags, and Spring Boot properties — the real ones.

-   :material-play-circle: **[Usage](user-guide/usage.md)**

    Connect a client and invoke tools, with the request/response shapes to expect.

-   :material-flask: **[Examples](user-guide/examples.md)**

    Concrete Maven, Gradle, and SBT invocations with sample requests and responses.

-   :material-sitemap: **[Architecture](reference/architecture.md)**

    Components, transports, and the end-to-end request flow.

-   :material-tools: **[Tools / MCP API](reference/tools.md)**

    Every registered tool: purpose, inputs, and outputs, grounded in the source.

-   :material-shield-lock: **[Security](reference/security.md)**

    The threat model, allowlists, blocked flags, timeouts, and transports.

</div>

---

!!! info "Project facts"
    - **Version:** v1.1.0
    - **License:** Apache License 2.0
    - **Language / runtime:** Java 21+
    - **Frameworks:** Spring Boot 4.1.0, Spring AI 2.0.0
    - **Transports:** stdio (default), Streamable HTTP (opt-in)
    - **MCP tools exposed:** 30 across 12 service beans
