# Frequently asked questions

## What does the 2.0 server provide?

One MCP server for Maven, Gradle, and sbt projects. It exposes [24 public tools](reference/tool-catalog.md) for build detection, allowed commands, validation, dependency queries, and bounded diagnostics. Call `tools/list` against your installed JAR for its exact schemas.

## Which Java and MCP versions does it support?

Build and run the server with Java 21 or later. The current Java SDK wire contract is MCP `2025-11-25` over stdio or stateless Streamable HTTP. See the [release gates](reference/release-gates.md) for the selected official conformance scenarios that are exercised; this is not a claim of full conformance across every scenario.

## Does the server need a network connection?

Local stdio opens no listening port. A dependency lookup may query Maven Central, and a Maven, Gradle, or sbt build may download dependencies or run arbitrary network-capable build-script code. The optional HTTP profile listens on loopback by default and requires a configured bearer key and per-tool scopes. See [configuration](user-guide/configuration.md).

## Can an agent read my raw build log or project path?

The server's model-visible build result contains bounded, structured, redacted diagnostics. It does not return raw process logs, commands, or absolute file paths. A `fileRef` links diagnostics within one result and is not a host path. The client or user can still place private text in a prompt or tool argument, so keep absolute paths and credentials in local client configuration and review what you send. See [privacy design](reference/design-v2.md#trust-boundaries).

## Is a configured project root a sandbox?

No. The root limits accepted project paths. A build script runs with the server process's OS permissions and may modify accessible files, launch programs, and reach the network. Run untrusted projects in an isolated container without host secrets and with restricted network access. The [2.0 design review](reference/design-v2.md#container-isolation) explains that boundary.

## Do I need Docker or a global Maven installation?

Docker is optional for local trusted projects and useful for untrusted test fixtures. The included Maven wrapper builds the server. Maven project builds need an available Maven installation through `MAVEN_HOME`, `maven.home`, or `mvn` on `PATH`; a project-local `mvnw` alone is not auto-selected. Gradle and sbt use project wrappers when available, then tools on `PATH`. See [installation](user-guide/installation.md).

## Can I migrate an existing 1.x client unchanged?

Review the [2.0 migration guide](user-guide/migration-v2.md): project roots, HTTP credentials and scopes, the public tool catalog, model-visible result shape, and protocol revision changed. Historical 1.x usage and examples remain labeled in the site for reference.

## Is this ready for production use?

`2.0.0-rc.1` is a release candidate. Its [release gates](reference/release-gates.md) and [roadmap](ROADMAP.md) describe verified checks and the additional work required for stable 2.0.0. Scope the server to trusted projects or isolate untrusted ones.

## What if build output says success but the command failed?

In post-RC1 development builds, completed Maven, Gradle, and sbt `execute_build_command` results include the subprocess `exitCode`; `success` is true only when that code is zero. A plugin without a typed result reports unknown status. Treat diagnostic text as untrusted data and use the [structured-result reference](reference/structured-build-results.md) for the exact contract.

## Can I use it in my product?

Yes. The project uses the Apache License 2.0; see the repository license for its terms.
