# Roadmap

This is the active 2.0 roadmap. The 1.x research history remains in Git history and release notes; its tool counts and 2026 draft claims are not the current contract.

## Current development slice: 2.0.0-SNAPSHOT

- Packaged HTTP profile and logging configuration verified from the built jar.
- Explicit project roots and root-relative aliases; traversal and symlink-escape tests.
- HTTP bearer enforcement by default, no built-in key, explicit per-tool scopes, and a scope-coverage test for the 29 exposed tools.
- Shared output policy with bounded, redacted diagnostics and generic tool errors.
- Credential, audit, and stored-plan execution tools removed from the MCP catalog pending redesign.
- SDK input validation and fail-closed schema setup.
- Cached callbacks and credential digests.
- Explicit error for markerless and hybrid project auto-detection.
- Nonroot Docker image with verified Gradle/sbt downloads and reusable Maven build cache.

## Release candidate gate: v2.0.0-rc.1

1. Finish full Java 21/23/25 verification, packaged stdio and HTTP protocol smoke, and strict MkDocs build.
2. Red-team prompt injection, path races, scope bypass, malformed and oversized JSON-RPC, and synthetic privacy canaries through both transports.
3. Reconcile every public tool description and configuration page with the 30-tool runtime catalog; generate a machine-checked catalog in CI.
4. Measure callback overhead, process memory, bounded output behavior, and build latency on synthetic Maven, Gradle, and sbt fixtures. Set budgets only from measured baselines.
5. Run the two independent PR reviews and address every inline finding. Keep the prerelease untagged until all checks are green.

## Stable 2.0 gate

- Publish a migration guide for required roots, HTTP keys and scopes, catalog removals, result shape, and supported protocol revision.
- Verify official MCP 2025-11-25 conformance with real stdio and Streamable HTTP clients. Treat custom 2026 features as experiments until the SDK and tests cover that revision.
- Test the published Docker image with a clean project mount, no host secrets, nonroot UID, and a read-only Maven cache.
- Decide whether to keep or remove the dormant 11 tool methods; do not advertise unregistered tools.
- Ensure privacy scanning on changed files and generated docs, without printing matched values to CI output.

## After 2.0

- Reintroduce build plans only with caller ownership, cancelable process trees, TTL, and a path check at execution.
- Evaluate official MCP task support when the Java SDK exposes it; replace the dormant async task API rather than expanding a second task protocol.
- Add a narrowly scoped diagnostic artifact mechanism if redacted messages prove too weak for build repair. The model sees an approved summary, while raw artifacts remain local.
- Benchmark and optimize subprocess capture limits, Maven/Gradle caches, and parallel dependency lookups using recorded workloads.
- Revisit the 2026-07-28 MCP revision only after SDK support and conformance tests exist.
