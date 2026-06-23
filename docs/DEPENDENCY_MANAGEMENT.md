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

Two complementary, automated mechanisms run in CI. They overlap on purpose
(defence in depth): one proposes upgrades, the other blocks known-vulnerable
dependencies from shipping.

### 1.1 OWASP dependency-check (vulnerability gate)

The [OWASP dependency-check](https://owasp.org/www-project-dependency-check/)
Maven plugin matches every resolved dependency against the National
Vulnerability Database (NVD) and fails the build on any **High/Critical**
finding (CVSS ≥ 7, configurable via `-Dowasp.failBuildOnCVSS`).

- **Where it lives:** behind the opt-in `owasp` Maven profile in `pom.xml`. It is
  intentionally **not** bound to the default `verify` lifecycle: the NVD download
  makes it far slower than the test suite, so binding it everywhere would tax
  every local build and the whole cross-JDK CI matrix in `ci.yml`.
- **How it runs in CI:** a dedicated workflow,
  [`.github/workflows/dependency-check.yml`](../.github/workflows/dependency-check.yml),
  runs it
  - **weekly** (so CVEs disclosed *after* a dependency was last touched are still
    caught),
  - on **`workflow_dispatch`** (manual runs), and
  - on **pull requests / pushes that change the dependency surface** (`pom.xml`,
    the suppression file, or the workflow itself) — failing fast before merge.
- **Reports:** HTML, JSON, and SARIF are produced under `target/`. The workflow
  uploads them as artifacts and pushes the SARIF to GitHub code scanning.
- **NVD API key (optional):** set the `NVD_API_KEY` repository secret to speed up
  NVD downloads. The scan still works without it (just rate-limited).
- **Suppressions:** investigated false positives / accepted risks go in
  [`owasp-suppressions.xml`](../owasp-suppressions.xml), each with a written
  justification and, where possible, an `until` expiry so it gets revisited.

Run it locally:

```bash
# Full scan via the profile (also runs the rest of `verify`):
./mvnw -Powasp verify

# Just the dependency scan (skips tests/other verify-bound checks):
./mvnw -Powasp org.owasp:dependency-check-maven:check

# Faster NVD downloads with an API key:
./mvnw -Powasp org.owasp:dependency-check-maven:check -Dnvd.api.key="$NVD_API_KEY"
```

### 1.2 Dependabot (upgrade proposals)

[`.github/dependabot.yml`](../.github/dependabot.yml) opens weekly PRs for
out-of-date / vulnerable dependencies, covering two ecosystems:

- **`maven`** — project dependencies and plugins. Related upgrades are grouped
  (Spring Boot, Spring AI, Maven tooling, build-quality plugins) so coordinated
  stacks land in a single reviewable PR. **Spring AI is grouped on its own** so
  the GA-tracking upgrade (below) is easy to review in isolation.
- **`github-actions`** — the actions pinned in the CI / Pages / publish
  workflows.

Dependabot PRs go through the same CI gates (including the OWASP scan, since they
touch `pom.xml`) and the standard two-reviewer process in `AGENTS.md`.

## 2. Decision: track Spring AI GA when released

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
   - running the OWASP scan (§1.1) so any CVE against the RC is caught,
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
3. `./mvnw -Powasp org.owasp:dependency-check-maven:check` → no new High/Critical.
4. Update `CHANGELOG.md`; open a PR (`Closes #<issue>`); follow the `AGENTS.md`
   review gates.
