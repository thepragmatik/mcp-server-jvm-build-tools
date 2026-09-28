# Roadmap

This is the active 2.0 roadmap. The 1.x research history remains in Git history and release notes; its tool counts and protocol claims are not the current contract.

## Current development line: 2.0.0-rc.2 (unreleased)

The latest published 2.0 prerelease is [v2.0.0-rc.1](https://github.com/thepragmatik/mcp-server-jvm-build-tools/releases/tag/v2.0.0-rc.1). Builds from `main` report the next candidate version so they cannot be confused with that immutable release artifact.

- Packaged HTTP profile and logging configuration verified from the built jar.
- Explicit project roots and root-relative aliases; traversal and symlink-escape tests.
- HTTP bearer enforcement by default, no built-in key, explicit per-tool scopes, and a scope-coverage test for the 24 exposed tools.
- Shared output policy with bounded, redacted diagnostics and generic tool errors.
- Credential, audit, and stored-plan execution tools removed from the MCP catalog pending redesign.
- SDK input validation and fail-closed schema setup.
- Cached callbacks and credential digests.
- Explicit error for markerless and hybrid project auto-detection.
- Nonroot Docker image with verified Gradle/sbt downloads and reusable Maven build cache.

## Release candidate gate: v2.0.0-rc.1

Completed for the published prerelease tag. The checks below remain the release gate for subsequent tags.

1. Finish full Java 21/23/25 verification, packaged stdio and HTTP protocol smoke, and strict MkDocs build.
2. Red-team prompt injection, path races, scope bypass, malformed and oversized JSON-RPC, and synthetic privacy canaries through both transports.
3. Reconcile every public tool description and configuration page with the 24-tool runtime catalog; generate a machine-checked catalog in CI.
4. Measure callback overhead, process memory, bounded output behavior, and build latency on synthetic Maven, Gradle, and sbt fixtures. Set budgets only from measured baselines.
5. Run two independent exact-head PR reviews and address every inline finding. Tag the prerelease only when all final-head gates are green; record the tag and release outcome separately.

The release evidence matrix is a living gate, not a list of assumed passes. Attach the exact commit, environment, command, and summarized result to the release PR; a result on one machine is not a universal performance guarantee.

| Gate | Evidence to attach | State at roadmap update | Owner |
| --- | --- | --- | --- |
| JDK 21/23/25, packaged stdio/HTTP, strict docs | CI checks, packaged protocol smoke, `mkdocs build --strict` | Recheck on final release head | Release engineer |
| MCP protocol | Pinned official runner, advertised-capability scenarios and DNS-rebinding case on final jar | Recheck on final release head | Protocol engineer |
| Privacy and adversarial cases | Synthetic canary matrix on both transports; counts/status only | Implemented; recheck on final release head | Security reviewer |
| Public contract | Generated runtime catalog and docs drift check | Implemented in CI; recheck on final release head | Docs engineer |
| Performance | `scripts/benchmark-release-gate.py` aggregates for Maven, Gradle, sbt, callback and bounded-output stress, with warm-cache/offline provenance | Five-run offline candidate-tree baseline recorded in [workflow](WORKFLOW.md#reproducible-performance-baseline); pinned-runner budget still pending | Performance engineer |
| Dependencies | Green keyless PR Dependency Review, then 0 open Dependabot alerts on the final default-branch commit using an authorized maintainer credential | Keyless gate implemented; recheck after final merge | Release engineer |
| PR review | Two fresh-checkout role-tagged reviews on final SHA; all inline threads answered | Pending final SHA | Quality and adversarial reviewers |

Before any release tag, the release engineer checks this matrix against the exact final default-branch commit and records the outcome. Do not infer a pass from an earlier PR head. A PR dependency review is not the final-branch alert audit; record both on the release head, and fail closed if alert access is unavailable. The performance script deliberately has no invented latency or memory budget. Compare repeated runs on a pinned runner and cache state before proposing one.

## Stable 2.0 gate

- Publish a migration guide for required roots, HTTP keys and scopes, catalog removals, result shape, and supported protocol revision.
- Verify official MCP 2025-11-25 conformance with real stdio and Streamable HTTP clients. The MCP 2026-07-28 specification is published, but the current Java SDK 2.0.1 targets 2025-11-25; do not advertise 2026 support until the SDK and tests cover it.
- Use the [optional full requirement audit](reference/release-gates.md) to track this gap: the current packaged HTTP baseline passes 6 of 30 scored server scenarios, fails 23, and has one warning; three other server scenarios are unscored. This audit is nonblocking and is not a conformance claim. The six selected official scenarios plus adversarial probes remain the release gate.
- Test the published Docker image with a clean project mount, no host secrets, nonroot UID, and a read-only Maven cache.
- Verify the dormant annotations are absent from the public catalog; decide separately whether their retained services and methods should be deleted.
- Ensure privacy scanning on changed files and generated docs, without printing matched values to CI output.

## Next development slices

Order the next slices by observed user friction, with a measured baseline or test before choosing an implementation:

The first post-RC increment already added MCP `structuredContent` and a bounded
`outputSchema` for `analyze_build_output` while preserving its JSON text result.
The same bounded contract now covers `execute_build_command`, preserving its
existing JSON text and one-build execution behavior.
Both transports, schema conformance, privacy, and adapter overhead were checked;
see the [result contract](reference/structured-build-results.md).
The assertion-versus-compilation classification bug was also fixed and tested
for Maven, Gradle, and sbt.

A synthetic 25.4 MB Maven failure showed that the old 32 KiB head / 96 KiB tail
capture omitted the sole compiler error in the middle, leaving a failed build
with `errorCount: 0`. The Maven-only analysis path now captures at most 13
complete compiler lines of 2 KiB each while draining, then applies the shared
privacy projection. The unchanged head/tail capture and a separate
`outputTruncated` signal remain; evaluate Gradle and sbt against matching
fixtures before extending this mechanism.

The first feature slice exposes three new static native workflows through `prompts/list` and `prompts/get` on both transports, while retaining the legacy `prompt_*` tools. HTTP requires `prompt:read`; prompt messages never interpolate paths, commands, arguments, or logs. The empty native resources capability remains advertised because its selected official conformance scenario failed when it was removed; `resources/list` returns no entries. Both-transport protocol, argument, scope, and synthetic privacy tests now cover the slice.

1. **Repair from private diagnostics.** If redacted structured diagnostics cannot explain a real synthetic failure, provide a short-lived local artifact reference and a separate, explicit caller-approved share operation. Keep raw content local by default; scope artifact access to the caller and project, bound size and lifetime, and recheck the path at read time. Acceptance: injected instructions, private-path/email/secret canaries, cross-caller reads, symlink races, expiry, and oversized artifacts cannot reach model-visible results without approval. Avoid building a general file browser or log-export API.
2. **Reduce repair round trips.** Observe a small set of representative repair sessions first. Then offer one constrained `diagnose → suggest → verify` operation or build-plan primitive only if it demonstrably reduces calls and time. It requires caller ownership, cancelable process trees, TTL, revalidated project paths at execution, and no stored credentials. Acceptance: deterministic synthetic repair fixture, cancellation/race tests, privacy-safe result, and a measured comparison with the current tool sequence. Keep the first slice to a single build tool and command family.
3. **Improve hot-path cost with evidence.** Use the release-gate fixture and recorded workloads to profile subprocess capture, Maven/Gradle cache behavior, and parallel dependency lookup before tuning. Acceptance: the same tests and security/privacy cases pass, with an environment-matched latency/RSS comparison; no global budget inferred from a developer laptop.
4. **Adopt official Tasks when stable in the Java SDK.** The 2026-07-28 core revision is published, while Tasks now lives in a separate extension. Wait for suitable Java SDK support and official conformance before replacing the dormant custom async protocol. Acceptance: cancellation, ownership, expiry, interoperability, and migration tests with real clients.
5. **Carry authoritative execution status (implemented after RC1).** Built-in Maven, Gradle, and sbt `execute_build_command` results now derive `success` from the completed subprocess exit code, including when retained markers disagree. Plugins without a typed process result expose unknown status. The typed Gradle/sbt path also preserves bounded stdout diagnostics on failure, which the legacy Java method previously discarded when stderr was empty. The legacy method remains available for plans and async callers.
6. **Preserve Gradle/sbt and async middle diagnostics.** A synthetic 28 MB Gradle/sbt output can still place its sole root cause outside the retained head/tail; the async path has the same class of loss. Extend a private bounded diagnostic collector only after tool-specific recognition tests and an environment-matched latency/RSS comparison. Keep the captured candidate count and line length bounded and project through the existing privacy filter.

Architecture debt stays visible alongside features: the 11 annotations on unregistered service methods have been removed, while their ordinary methods and beans remain for a separately justified deletion decision. Consolidate process execution and cache policies where measured duplication exists; preserve the single authorization/output-policy boundary for new tools; and keep the generated public catalog as the docs drift check. Each decision needs a failing test, a specific user or maintenance cost, or a measured hot path rather than a speculative rewrite.
