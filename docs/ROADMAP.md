# Roadmap

This is the active 2.0 roadmap. The 1.x research history remains in Git history and release notes; its tool counts and 2026 draft claims are not the current contract.

## Current candidate: 2.0.0-rc.1

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

1. Finish full Java 21/23/25 verification, packaged stdio and HTTP protocol smoke, and strict MkDocs build.
2. Red-team prompt injection, path races, scope bypass, malformed and oversized JSON-RPC, and synthetic privacy canaries through both transports.
3. Reconcile every public tool description and configuration page with the 24-tool runtime catalog; generate a machine-checked catalog in CI.
4. Measure callback overhead, process memory, bounded output behavior, and build latency on synthetic Maven, Gradle, and sbt fixtures. Set budgets only from measured baselines.
5. Run the two independent PR reviews and address every inline finding. Keep the prerelease untagged until all checks are green.

The release evidence matrix is a living gate, not a list of assumed passes. Attach the exact commit, environment, command, and summarized result to the release PR; a result on one machine is not a universal performance guarantee.

| Gate | Evidence to attach | State at roadmap update | Owner |
| --- | --- | --- | --- |
| JDK 21/23/25, packaged stdio/HTTP, strict docs | CI checks, packaged protocol smoke, `mkdocs build --strict` | Recheck on final release head | Release engineer |
| MCP protocol | Pinned official runner, advertised-capability scenarios and DNS-rebinding case on final jar | Recheck on final release head | Protocol engineer |
| Privacy and adversarial cases | Synthetic canary matrix on both transports; counts/status only | Implemented; recheck on final release head | Security reviewer |
| Public contract | Generated runtime catalog and docs drift check | Implemented in CI; recheck on final release head | Docs engineer |
| Performance | `scripts/benchmark-release-gate.py` aggregates for Maven, Gradle, sbt, callback and bounded-output stress, with warm-cache/offline provenance | Exploratory baseline recorded; final release-head rerun and runner budget pending | Performance engineer |
| Dependencies | Green keyless PR Dependency Review, then 0 open Dependabot alerts on the final default-branch commit using an authorized maintainer credential | Keyless gate implemented; recheck after final merge | Release engineer |
| PR review | Two fresh-checkout role-tagged reviews on final SHA; all inline threads answered | Pending final SHA | Quality and adversarial reviewers |

The release engineer must not tag `v2.0.0-rc.1` while a required row is failed, unknown, or blocked. A PR dependency review is not the final-branch alert audit; record both on the release head, and fail closed if alert access is unavailable. The performance script deliberately has no invented latency or memory budget. Compare repeated runs on a pinned runner and cache state before proposing one.

## Stable 2.0 gate

- Publish a migration guide for required roots, HTTP keys and scopes, catalog removals, result shape, and supported protocol revision.
- Verify official MCP 2025-11-25 conformance with real stdio and Streamable HTTP clients. Treat custom 2026 features as experiments until the SDK and tests cover that revision.
- Test the published Docker image with a clean project mount, no host secrets, nonroot UID, and a read-only Maven cache.
- Decide whether to keep or remove the dormant 11 tool methods; do not advertise unregistered tools.
- Ensure privacy scanning on changed files and generated docs, without printing matched values to CI output.

## After 2.0

Order the next slices by observed user friction, with a measured baseline or test before choosing an implementation:

1. **Repair from private diagnostics.** If redacted structured diagnostics cannot explain a real synthetic failure, provide a short-lived local artifact reference and a separate, explicit caller-approved share operation. Keep raw content local by default; scope artifact access to the caller and project, bound size and lifetime, and recheck the path at read time. Acceptance: injected instructions, private-path/email/secret canaries, cross-caller reads, symlink races, expiry, and oversized artifacts cannot reach model-visible results without approval. Avoid building a general file browser or log-export API.
2. **Reduce repair round trips.** Observe a small set of representative repair sessions first. Then offer one constrained `diagnose → suggest → verify` operation or build-plan primitive only if it demonstrably reduces calls and time. It requires caller ownership, cancelable process trees, TTL, revalidated project paths at execution, and no stored credentials. Acceptance: deterministic synthetic repair fixture, cancellation/race tests, privacy-safe result, and a measured comparison with the current tool sequence. Keep the first slice to a single build tool and command family.
3. **Improve hot-path cost with evidence.** Use the release-gate fixture and recorded workloads to profile subprocess capture, Maven/Gradle cache behavior, and parallel dependency lookup before tuning. Acceptance: the same tests and security/privacy cases pass, with an environment-matched latency/RSS comparison; no global budget inferred from a developer laptop.
4. **Adopt official Tasks when stable in the Java SDK.** Treat MCP Tasks and the 2026-07-28 revision as experimental until SDK support and official conformance cover them. Replace the dormant custom async protocol rather than offering two competing task models. Acceptance: cancellation, ownership, expiry, interoperability, and migration tests with real clients.

Architecture debt stays visible alongside features: decide whether to remove the dormant 11 `@Tool` methods and stale 1.x feature specs after the 2.0 contract stabilizes; first quantify their code, test, and review cost. Consolidate process execution and cache policies where measured duplication exists; preserve the single authorization/output-policy boundary for new tools; and keep the generated public catalog as the docs drift check. Each decision needs a failing test, a specific user or maintenance cost, or a measured hot path rather than a speculative rewrite.
