# Release gates for the 2.0 line

The `.github/workflows/release-gates.yml` workflow packages the server
and runs `python3 scripts/release-gate.py` against loopback-only processes. The
script starts the packaged jar, tests Streamable HTTP with the pinned official
`@modelcontextprotocol/conformance@0.2.0-alpha.11` runner at the `2025-11-25`
wire version, checks adversarial HTTP requests, and probes stdio separately. It
prints only pass/fail labels; server logs and protocol responses stay out of CI
output.

The packaged Maven synthetic probe exercises both transports for a retained
middle compiler diagnostic and a failed `execute_build_command` whose output
also claims success. It checks authoritative `exitCode`, structured/text
parity, and private canary suppression without printing build logs.

The release workflow also runs the packaged Gradle/sbt middle-diagnostic probe
on HTTP and stdio. It drives a 28 MiB synthetic failure on stdout or stderr, plus a competing-stream
case whose final stderr error has no newline, and a colored Kotlin `e:` error in the discarded middle of a Gradle stream. It then checks analysis and execution, structured/text parity, exit status, and
synthetic path/email/secret suppression.

The official scenarios selected for this product are `server-initialize`,
`ping`, `tools-list`, `resources-list`, `prompts-list`, and
`dns-rebinding-protection`. The official runner's frozen `--requirements
2025-11-25` set also contains tests written for an “everything” server with
audio, images, elicitation, subscriptions, and other optional capabilities.
Those tests cannot be used as an honest pass/fail gate for this build-tool
server. Passing the six selected scenarios is **not** a claim of full MCP
conformance or a Tier 1 assessment. Review the [upstream requirement
set](https://github.com/modelcontextprotocol/conformance) when changing the
server's advertised capabilities.

For roadmap work, run the **optional** full server requirement audit locally
after packaging:

```sh
./mvnw -B package -DskipTests --no-transfer-progress
python3 scripts/conformance_matrix_audit.py
```

This uses the same pinned official runner and the frozen `2025-11-25` set: 30
scored server scenarios and three visibility-only server scenarios. The latter
are `server-session-lifecycle` (added after release), `json-schema-2020-12`
and `server-sse-polling` (pending upstream fixtures). The audit starts the jar
on loopback, runs the runner in a private temporary working directory, and
deletes its reports afterward. It prints only preapproved scenario/check IDs
and aggregate statuses, substituting `unlisted-check` for any new check ID;
server logs, transcripts, error messages, paths, and
report artifacts never enter the terminal or CI. Node.js/npm is needed to
fetch the pinned runner on first use; its npm package cache is reused. The
temporary working directory manages artifacts; it is not an OS sandbox for
the runner. Run the pinned package only in a trusted local environment.

**Audit only; nonblocking; the full scored set is not passed.** The command
returns zero when it completes and records failures, because this audit is
deliberately outside the release gate. Successful command execution means the
audit ran, not that the server passed the requirement set. Add
`--fail-on-scored-failure` to return nonzero for scored failures once a
scenario's required fixture is available. In a 2026-09-28 run against the
packaged server, the scored set reported 6 pass, 23 fail, and 1 warning;
the three unscored scenarios reported one failure, one warning, and one
informational result. Most scored failures request synthetic tools, resource
content, prompts, or optional interactions that this build-tool server does not
advertise. Use the scenario names to prioritize applicable product behavior;
these numbers do **not** establish or refute whole-protocol conformance. The
six selected scenarios and the adversarial probes above remain the release
blockers.

The black-box adversarial probes send only synthetic data. They require:

- A packaged Maven analysis call with a compiler diagnostic in the discarded
  middle of a 24 MiB synthetic stream to retain a useful redacted result on
  both HTTP and stdio. The probe checks structured/text parity, truthful
  output truncation, and absence of synthetic path, email, secret, symbol, and
  prompt-injection canaries.

- Malformed JSON-RPC to return the generic JSON-RPC `-32700` parse error in a
  small HTTP 400 response, without a synthetic private canary or exception
  internals anywhere in the body.
- Invalid JSON-RPC method, identifier, and parameter shapes to return a small
  generic `-32600` error; the gate checks eight synthetic mutations, including
  scalar and list parameters and a malformed tool argument.
- Unknown methods, tools, resources, and prompts to return bounded JSON-RPC
  errors without echoing synthetic private identifiers on both HTTP and stdio.
- Empty discovery probes on `/mcp` and `/mcp/discover` to remain available;
  whitespace and real RPC calls still need authentication in HTTP mode.
- A request larger than the 1 MiB MCP body cap to return HTTP 413.
- A hostile Host and Origin pair to return HTTP 403.
- A valid bearer limited to `build:read` to receive HTTP 403 for
  `execute_build_command` and successfully call `list_build_tools` with the
  same key.
- Stdio to negotiate `2025-11-25`, answer `ping` and `tools/list`, then
  return a bounded generic parse error for one malformed JSON line and still
  answer a subsequent `ping` in the same session.

The full Maven verification suite exercises structured diagnostic redaction,
prompt-injection suppression, authorization, and allowed-root/symlink handling.
The HTTP boundary parses every body under the 1 MiB request cap before SDK
dispatch to prevent malformed input reaching a verbose exception mapper. This
adds one bounded parse on requests without optional MCP headers; the release
performance baseline should include it. The server still buffers the request
only once at this boundary.
Both transports use one outbound JSON-RPC error policy: SDK error codes remain,
while exception data and caller-derived error messages are replaced with fixed
phrases. Successful tool responses, including projected diagnostics, keep their
normal format. This is a wire-output policy; operators should still investigate
server errors using local logs.
The stdio recovery adapter uses the SDK's existing line reader and session
transport, so valid messages get no second parse and responses stay serialized
by the SDK. Its input limit is set to 1 MiB of decoded characters; an
oversized line ends the stdio session. The SDK decodes UTF-8 with replacement
for malformed byte sequences, so this gate does not claim strict byte-level
UTF-8 rejection.
These checks do not prove that an arbitrary build script is safe. A filesystem
symlink can change after Java validates its path and before a child process uses
it; run untrusted workspaces in a container or equivalent OS sandbox without
network and with minimal mounts. The isolated `scripts/docker-verify.sh`
verifies committed `HEAD` from a tracked-source snapshot and a read-only Maven cache; it does not turn
the server itself into a sandbox.

The normal CI `Privacy and docs` job scans changed lines without printing
matches, builds the documentation with `mkdocs build --strict`, checks the
registry JSON, and tests the release alert audit. Its green status is a
distinct gate. The keyless `Dependency review` PR job blocks newly introduced
known vulnerabilities of moderate severity or higher in runtime, development,
and unknown scopes. On the final default-branch commit, run the separate
[Dependabot release alert audit](../DEPENDENCY_MANAGEMENT.md) with an authorized
maintainer credential. It fails on any open alert or unavailable API. Record
both results; a PR-only scan does not clear pre-existing alerts, and Dependabot
can take time to process a newly merged dependency change. No NVD API key is
required. The old OWASP Maven profile remains optional for local independent
investigation, not a release gate.

Local repeat command after packaging:

```sh
./mvnw -B package -DskipTests --no-transfer-progress
python3 scripts/release-gate.py
python3 scripts/gradle-sbt-middle-protocol-gate.py
```

For any build-output capture change, test a synthetic root cause in the discarded
middle and at each limit: per-stream head/tail bytes, complete-line length and
candidate count, the 256,000-character private input cap, and the 240,000-character
JSON envelope after escaping. Compare both MCP transports and inspect only
aggregate or redacted results; raw canary logs stay in private temporary files.
Repeat the same matrix on the exact PR head after review fixes.

Run `./mvnw -B verify --no-transfer-progress` and the privacy/docs checks as
separate gates. The Python command needs Node.js and npm to fetch the exact
official runner version.

## Tag-triggered publication

The `.github/workflows/maven-publish.yml` workflow runs only after a tag
push. It rejects tags whose version disagrees with both `pom.xml` and
`mcp-registry.json`, requires a nonempty versioned release body in
`.github/release-bodies/`, and requires the tagged commit to be on `main`. The
workflow runs the full JDK 21 Maven verification, then the packaged
Streamable HTTP and stdio protocol/adversarial gate, before publishing the
exact runnable jar. The curated body puts breaking changes and migration
links ahead of generated change notes on the GitHub Release page. An
`-rc.N` tag is marked as a GitHub prerelease and
cannot be the Latest release. The tag itself is created only after all
release evidence and independent reviews are accepted; preparing this
workflow does not publish a release.
