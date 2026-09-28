# Release gates for the 2.0 line

The `.github/workflows/release-gates.yml` workflow packages the server
and runs `python3 scripts/release-gate.py` against loopback-only processes. The
script starts the packaged jar, tests Streamable HTTP with the pinned official
`@modelcontextprotocol/conformance@0.2.0-alpha.11` runner at the `2025-11-25`
wire version, checks adversarial HTTP requests, and probes stdio separately. It
prints only pass/fail labels; server logs and protocol responses stay out of CI
output.

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

The black-box adversarial probes send only synthetic data. They require:

- Malformed JSON-RPC to avoid an HTTP 5xx and avoid reflecting a synthetic
  private canary.
- A request larger than the 1 MiB MCP body cap to return HTTP 413.
- A hostile Host and Origin pair to return HTTP 403.
- A valid bearer limited to `build:read` to receive HTTP 403 for
  `execute_build_command`.
- Stdio to negotiate `2025-11-25` and answer `ping` and `tools/list`.

The full Maven verification suite exercises structured diagnostic redaction,
prompt-injection suppression, authorization, and allowed-root/symlink handling.
These checks do not prove that an arbitrary build script is safe. A filesystem
symlink can change after Java validates its path and before a child process uses
it; run untrusted workspaces in a container or equivalent OS sandbox without
network and with minimal mounts. The isolated `scripts/docker-verify.sh`
checks a clean build but does not turn
the server itself into a sandbox.

The normal CI `Privacy and docs` job scans changed lines without printing
matches, builds the documentation with `mkdocs build --strict`, and checks the
registry JSON. Its green status is a distinct gate. The OWASP dependency-check
workflow is **skipped** when `NVD_API_KEY` is absent; a skipped job is not
security-scan evidence. Configure the repository secret and obtain a successful
scan before declaring a release candidate security-complete. See
[dependency management](../DEPENDENCY_MANAGEMENT.md).

Local repeat command after packaging:

```sh
./mvnw -B package -DskipTests --no-transfer-progress
python3 scripts/release-gate.py
```

Run `./mvnw -B verify --no-transfer-progress` and the privacy/docs checks as
separate gates. The Python command needs Node.js and npm to fetch the exact
official runner version.
