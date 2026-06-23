# Agent Contributor Guide — mcp-server-jvm-build-tools

This repository is maintained by autonomous coding agents. **Follow this process
EXACTLY for every change.** It is the source of truth for the PR lifecycle.

## Build & test
- JDK 21+, Maven. Full verify: `mvn -B verify --no-transfer-progress` — must be
  **BUILD SUCCESS**, all tests green. During iteration you may run the affected
  test class for speed, but run the full `verify` before pushing.

## Privacy / PII (mandatory)
Never write, commit, push, log, or send to the model any PII (real names, emails,
phones, addresses, salaries, vehicles, plates/VINs, GitHub handles, company/client
names, home paths with real usernames) or any secrets/API keys/tokens/`.env`
contents. Output may become a PUBLIC PR — treat everything as publishable. Use
environment variables for keys and generic placeholders otherwise. When in doubt,
leave it out.

## PR workflow — state machine (never skip a gate, never fake a result)
1. **Branch** off `main` (`fix/<issue>-<slug>` or `feat/<issue>-<slug>`). Never
   commit to `main`. Never merge your own work outside the gates below.
2. **Implement** complete, production-ready code (no placeholders/TODOs/stubs).
   Prefer whole-file writes over fuzzy partial diffs. Add/adjust tests. **Commit
   early** (after the fix compiles; after tests compile) so a transient failure
   never loses work.
3. `mvn -B verify` **GREEN**. Push. Open a PR with `Closes #<issue>` and a body:
   summary + root cause + changes + the real `Tests run:` line.
4. **GATE 1 — CI:** all checks pass. Never merge on red/pending/unknown.
5. **GATE 2 — TWO independent reviews.** Each reviewer does a *fresh checkout*,
   runs `mvn -B verify` itself (don't trust prior runs), leaves **inline comments**
   (GitHub review API, `path`+`line`), and posts a role-tagged verdict comment:
   - **ADVERSARIAL** — correctness, edge cases, concurrency, security, regressions,
     test validity. Verdict: `ADVERSARIAL — VERDICT: APPROVE | REQUEST_CHANGES`.
   - **CODE-QUALITY** — clean design, readability, naming, SOLID/DRY, cohesion,
     test quality, docs, maintainability. Verdict:
     `CODE-QUALITY — VERDICT: APPROVE | REQUEST_CHANGES`.
   (Single shared bot identity cannot natively self-approve; verdicts live in the
   comment bodies, not GitHub's approval state. Do not rubber-stamp — cite specific
   code and your own build output.)
6. **GATE 3 — Author responds to EVERY review comment.** For each comment thread,
   either: (a) **implement** the change, commit, and reply linking the commit; or
   (b) **reply with a clear rationale** for declining. No comment is left
   unaddressed. If code changed, re-run CI and ask both reviewers to re-confirm
   their blocking comments are resolved.
7. **MERGE** only when: CI green **AND** both reviewers' blocking comments are
   resolved / both verdicts APPROVE **AND** every comment has a response.
   Squash-merge; delete the branch.

## Honesty & escalation
- Evidence over assertion: verify with real tool output; never fabricate results,
  metrics, or test outcomes. If unknown, say so.
- Flag schema, architecture, or strategy changes for explicit human approval.
