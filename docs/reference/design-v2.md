# Design review for 2.0

This page records the architecture decision for the 2.0 development line. The published first prerelease is `v2.0.0-rc.1`; `main` now builds the unreleased `2.0.0-rc.2` development candidate. Required project roots, default HTTP authentication, removed model-visible tools, narrowed result shape, and scope semantics break 1.x clients. A later release tag requires all gates to pass on its exact commit.

## Trust boundaries

```mermaid
flowchart LR
    Client["🟣 MCP client / model"] -->|JSON-RPC| Transport["🔵 stdio or HTTP"]
    Transport -->|HTTP only| Auth{"🟠 Bearer + tool scope"}
    Transport --> Guard{"🟠 Path guard"}
    Auth --> Guard
    Guard --> Tool["🟢 Java tool service"]
    Tool --> Build["🟢 Maven / Gradle / sbt process"]
    Build --> Policy["🟡 Redacted, bounded diagnostics"]
    Tool --> Policy
    Policy --> Client
    Guard --> Deny["🔴 Generic error"]
    Auth --> Deny
    classDef client fill:#eee5ff,stroke:#7c3aed,color:#24114b
    classDef transport fill:#dbeafe,stroke:#2563eb,color:#102a56
    classDef decision fill:#ffedd5,stroke:#ea580c,color:#512600
    classDef execution fill:#dcfce7,stroke:#16a34a,color:#073b1e
    classDef output fill:#fef9c3,stroke:#ca8a04,color:#443400
    classDef deny fill:#fee2e2,stroke:#dc2626,color:#520909
    class Client client
    class Transport transport
    class Auth,Guard decision
    class Tool,Build execution
    class Policy output
    class Deny deny
```

The `ToolCallbackProvider` is the shared boundary for stdio and HTTP. Only tools explicitly mapped to a public permission are exposed; future `@Tool` methods are private by default. It canonicalizes `projectDir` and `localRepositoryPath`, rejects build files whose real paths escape the configured roots, and applies the output policy. Raw resource readers, URI-template tools, free-form CI generation, and async task tools remain private until they have useful privacy-safe result contracts. Async task summaries written inside a project contain only status, tool, timestamps, and exit code; commands and raw errors are not persisted into a Git repository. HTTP adds bearer and per-tool scope checks before dispatch. The catalog is static, so wrappers and credential digests are cached. Input schema failures abort startup and SDK input validation is enabled.

The public tool descriptions state the result contract after the output policy, rather than the richer local Java return values. In particular:

| Tool | Model-visible result | Retained locally |
|---|---|---|
| `analyze_pom_dependencies` | Dependency, managed-dependency, and imported-BOM counts | Coordinates and per-dependency classifications |
| `scan_dependency_cves` | Scanned, vulnerable, critical, and high counts; bounded redacted warnings | Dependency and CVE identities |

These aggregate results support triage but cannot identify a particular dependency to edit. A user who needs that detail must inspect the local build report outside the MCP result channel. The tool metadata and protocol tests pin this contract so a future implementation cannot advertise details that the model never receives.

Build execution and output analysis return `diagnostics` as structured objects: `severity` (`error` or `warning`), `category` (`compilation`, `test`, `dependency`, `configuration`, `execution`, or `other`), per-result `diagnosticRef`, optional per-result `fileRef`, `fileType` (`java`, `kt`, `scala`, `xml`, `gradle`, `kts`, or `sbt`) and positive `line`, and a redacted message of at most 500 characters. At most 12 distinct source diagnostics are returned, errors first; `diagnosticsTruncated: true` signals omitted entries. Recognized failure phrases retain the cause (for example, `cannot find symbol`) but normalize arbitrary identifiers, values, and dependency coordinates to placeholders. Unknown or suspicious text receives a generic local-inspection message. `diagnosticRef` distinguishes errors whose public fields otherwise match; `fileRef` groups messages from one file within a result but reveals no path. This is a triage contract: the user must inspect local build output to map a reference to a file and symbol before editing. The parser's raw log and command are removed before serialization for the model-visible policy, preserving final structured errors even when the original build output was large. Neither source excerpts nor raw file or symbol identities are emitted.

Maven analysis now carries its actual process exit code into its parser and retains bounded compiler diagnostics from the discarded middle of a large log. `execute_build_command` still infers public status from retained log markers; a later, narrow change should carry the authoritative subprocess exit code through its guarded projection for all three build tools. Conflicting synthetic markers, both transports, and privacy projection must be tested before claiming that status is authoritative.

## Critical review

| Finding from 1.x | 2.0 decision | Remaining risk |
|---|---|---|
| A built-in development key could authenticate HTTP | Remove it; HTTP defaults to bearer enforcement and keys default to no scopes | Operators must provision a key locally |
| Scope enum omitted live tools and was not enforced on calls | Publish only the 24 explicitly scoped tools; fail CI on catalog drift; check scope on HTTP `tools/call` | stdio relies on local process trust |
| Schema parse failure became an empty schema | Abort startup; validate tool inputs | Schema compatibility needs client conformance tests |
| Arbitrary build output and exceptions could reach the model | Project recognized failures to bounded redacted diagnostics; return generic errors for unknown text | Pattern redaction cannot prove every private identifier was removed |
| A stored build plan could be executed by ID without a path on the call | Withhold `create_build_plan` and `execute_build_plan` from MCP | Plan ownership and cancellation need a later design |
| Tool metadata, docs, and registry disagreed | Generate the 24-tool public catalog from runtime metadata and fail CI on drift | Keep prose and historical pages clearly scoped to their release |
| Project detection could silently fall back to Maven | Treat markerless or ambiguous directories as an explicit error | Hybrid projects must specify a tool name |

A configured root is a filesystem boundary, not a sandbox. The canonical path check prevents common traversal and symlink escape, but a build script can execute programs, reach the network, and mutate files accessible to its process. A hostile workspace therefore needs OS or container isolation. Likewise, client prompts and tool arguments travel through the client/model provider before reaching this server. Relative aliases avoid sending an absolute home path; the server cannot guarantee that a client or user never sends private prompt text.

## Protocol contract

The runtime uses [MCP Java SDK 2.0.1](https://github.com/modelcontextprotocol/java-sdk/blob/main/VERSIONING.md), whose documented spec line is `2025-11-25`. The SDK BOM aligns all resolved MCP modules to 2.0.1. The HTTP profile registers the SDK's stateless servlet on `/mcp`; its packaged-jar smoke and automated integration tests exercise `initialize`, `tools/list`, and `tools/call`. Discovery advertises that revision only. The published 2026-07-28 revision is not yet supported by this Java SDK; an inactive `server/discover` handler remains a compatibility experiment, and `/mcp/discover` is only an informational probe. A servlet filter validates `Origin` on every MCP request before SDK dispatch and rejects untrusted loopback `Host` values, including when bearer authentication is disabled. It caches the configured Origin policy at startup. The official 2025-11-25 DNS rebinding scenario is a release gate; other conformance scenarios and both transports need client coverage before stable release.

## Performance budget

The hot path parses each tool argument once, checks canonical paths, runs the tool, then redacts a bounded result. The output policy limits parsing to the final 256,000 characters of a large result. The static callback array is built once; credential hashes are computed when keys load and incoming tokens use constant-time digest comparison. Maven, Gradle, and sbt process streams retain at most their first 32 KiB and last 96 KiB. Both pipes drain concurrently in 8 KiB chunks, including unterminated lines; the dormant async task path also uses bounded storage. Subprocesses have a configurable timeout, and timeout, interruption, and async cancellation terminate descendants before the parent. The in-process Maven version probe uses the same bounded collector. HTTP MCP POST bodies are capped at 1 MiB by default, including requests without optional MCP headers; larger bodies receive 413 before SDK dispatch. These limits bound retained capture and accepted request size, not child-process memory or disk writes; isolate untrusted build scripts in a container. Measure p50/p95 tool overhead and heap usage with synthetic projects before stable release; record baselines rather than claiming speedups from source inspection.

## Container isolation

The Docker image builds with a persistent BuildKit Maven cache, verifies Gradle and sbt archive checksums, excludes local secrets from the build context, and runs as a nonroot user. For adversarial tests, copy source into a disposable writable container workspace, mount the local Maven repository read-only, disable the network, and set CPU, memory, process, and time limits. Keep the Docker socket and host secret directories outside the container. A read-only Maven cache may need a disposable writable copy when dependencies are missing; do not mount the host cache writable for untrusted builds.

Build the image with `DOCKER_BUILDKIT=1 docker build -t jvm-build-tools:2.0-local .`, then run `./scripts/docker-verify.sh`. The helper stages a private temporary archive of committed `HEAD`, streams it into tmpfs, and removes the archive on exit. Ignored worktrees, generated files, and uncommitted local data stay out; `~/.m2/repository` is mounted read-only, Maven runs offline, and the container is discarded on exit. Commit intended changes before using this release gate.

## Review and release gates

Each change gets a focused test, a full `mvn -B verify`, then an adversarial review and an independent code-quality review from fresh checkouts. Both reviews leave inline PR findings and explicit verdicts. Every finding receives a fix or a written rationale, and CI reruns after changes. Run a privacy canary (synthetic email, path, and token) through both transports, exercise path traversal and scope denial, run a Docker clean-room verify, and build docs strictly before the prerelease. No 2.0 tag should be created while a gate is red or unverified.
