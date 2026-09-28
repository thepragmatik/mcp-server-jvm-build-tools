# Dependency Management & Supply-Chain Scanning

This document records how `mcp-server-jvm-build-tools` manages and scans **its
own** dependencies, and the deliberate decision around the pre-GA Spring AI pin.
It is the recorded decision for issue
[#78](https://github.com/thepragmatik/mcp-server-jvm-build-tools/issues/78).

> Context: the project ships SBOM / supply-chain tooling to *its users*, so it
> must hold its own supply chain to the same bar. Previously the server's own
> dependencies were not scanned and it pinned a pre-GA dependency
> (`spring-ai 2.0.0-RC2`) with no documented upgrade plan.

## 1. Scanning the server's own dependencies

The release security decision uses GitHub's existing dependency graph and
advisory database. No NVD API key or additional vulnerability service is
needed. These checks complement tests and review; they only find known
vulnerabilities in dependencies that GitHub can identify.

### 1.1 Pull-request gate: Dependency Review

The [Dependency Review action](https://github.com/actions/dependency-review-action)
in [`.github/workflows/dependency-review.yml`](../.github/workflows/dependency-review.yml)
runs on every PR. It blocks newly introduced vulnerabilities rated **moderate
or higher** in runtime, development, or unknown scope. It has read-only
repository permission and does not run build scripts or need a secret. The
action is pinned to an immutable v5.0.0 commit. Its result must be green before
merge, including for Dependabot PRs.

This check compares the PR with its base branch. It cannot clear vulnerabilities
already present on the base branch, nor does a green result prove that GitHub
has a complete dependency graph. Maven fixture POMs and pinned
`docs/requirements.txt` are included in the repository; recent Dependabot
alerts have covered both. Review the dependency diff when a build plugin or
transitive dependency changes.

### 1.2 Final-release gate: open Dependabot alerts

After all release changes are merged, check out the **current default-branch
commit** and run:

```sh
python3 scripts/dependabot_release_gate.py --repo thepragmatik/mcp-server-jvm-build-tools
```

The script uses the maintainer's existing `gh` login and GitHub's paginated
[Dependabot alert API](https://docs.github.com/en/rest/dependabot/alerts). It
requires a clean local checkout at GitHub's current default-branch commit,
then fails if **any** alert is open. It rechecks that commit after pagination;
API denial, timeout, malformed response, a dirty checkout, or branch movement
fail closed. Output contains only pass/fail and an alert
count: no package name, private path, advisory text, or token. An authorized
credential with Dependabot-alert read access is required; `GITHUB_TOKEN` is not
assumed to have that permission. Wait for GitHub to process newly merged
dependency changes, then record the exact commit, result, and time in the
release evidence. A zero count is a point-in-time observation, not a guarantee
that new advisories will never appear.

The previous NVD-dependent CI workflow was removed because it skipped its
blocking scan without `NVD_API_KEY`. The opt-in `owasp` Maven profile and
[`owasp-suppressions.xml`](../owasp-suppressions.xml) remain available for local
independent investigation, but are **not** release evidence. The official
[OSV Scanner](https://google.github.io/osv-scanner/supported-languages-and-lockfiles/)
is another keyless option; it is not the primary gate here because its Maven
transitive graph omits test dependencies and its default resolution queries
deps.dev. That would add package-coordinate sharing and leave a coverage gap.

### 1.3 Dependabot (upgrade proposals)

[`.github/dependabot.yml`](../.github/dependabot.yml) opens weekly PRs for
Maven dependencies and plugins, pinned Python documentation tools, and GitHub
Actions. Related Maven upgrades are grouped; Spring AI has its own group.
Dependabot PRs pass the same CI and two-reviewer process. The release audit
also catches advisories disclosed after a dependency was last changed.

## 2. Spring AI version policy

The current `pom.xml` pins Spring AI `2.0.1` through `spring-ai.version`. The decision below is retained as historical context from June 2026 and is superseded; Spring AI GA has already been adopted. Keep this document aligned with the actual Maven property when the dependency changes. The active security gates are in §1.

### Historical decision: track Spring AI GA when released

**Status: accepted.** **Date: 2026-06.**

### Context

The server depends on `org.springframework.ai:spring-ai-mcp` via the Spring AI
BOM, currently pinned to **`2.0.0-RC2`** (a Release Candidate, not GA). Spring AI
provides the MCP server integration the project is built on, so the binding is
load-bearing. Pre-GA artifacts can still receive breaking API changes between
RCs and GA and do not carry GA stability/support guarantees.

### Decision

1. **Stay on the latest Spring AI 2.0.0 RC** until GA, rather than downgrading to
   an older GA line — the 2.0.0 line carries the MCP capabilities this server
   relies on, and moving backwards would mean losing functionality.
2. **Upgrade to Spring AI 2.0.0 GA as soon as it is released.** Treat it as a P1
   follow-up. Dependabot's dedicated `spring-ai` group will surface the upgrade
   PR automatically when GA (or a newer RC) is published.
3. **The RC pin is centralised and visible**: it lives in the single
   `spring-ai.version` property in `pom.xml` and is imported via the Spring AI
   BOM, so the GA bump is a one-line change plus a `verify`.
4. **De-risk while on the RC** by:
   - keeping the version in one property (no scattered pins),
   - running the then-current OWASP scan to look for known vulnerabilities,
   - relying on the full `mvn -B verify` suite (504+ tests) to catch RC→GA
     behavioural regressions when the bump lands.

### Consequences

- The build remains on a documented, monitored RC instead of an undocumented
  one. The upgrade path to GA is a single, reviewable, test-gated change.
- If a blocking issue is found in the RC before GA, the centralised property
  allows pinning to a different RC with minimal churn.

### How to perform the GA upgrade (runbook)

1. Bump `spring-ai.version` in `pom.xml` to the GA version.
2. `./mvnw -B verify --no-transfer-progress` → BUILD SUCCESS, all tests green.
3. Require green PR Dependency Review, then run the default-branch Dependabot alert audit after merge (§1).
4. Update `CHANGELOG.md`; open a PR (`Closes #<issue>`); follow the `AGENTS.md`
   review gates.
