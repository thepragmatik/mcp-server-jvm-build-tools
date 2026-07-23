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

## PR workflow — 4-gate state machine (never skip a gate, never fake a result)
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
   (GitHub review API, `path`+`line`), and posts a role-tagged verdict comment.
   **Reviews MUST be posted through the GitHub PR interface** using the `gh` CLI —
   Kanban comments alone are insufficient because they are not visible to the PR
   author in the GitHub UI and do not trigger CI re-evaluation.

   **Review command example:**
   ```sh
   git fetch origin pull/<PR_NUMBER>/head:pr-review && git checkout pr-review
   mvn -B verify --no-transfer-progress
   # After full review, post verdict via GitHub:
   gh pr review <PR_NUMBER> --repo thepragmatik/mcp-server-jvm-build-tools \
     --request-changes --body "ADVERSARIAL — VERDICT: REQUEST_CHANGES

   <specific findings with file paths and line numbers>"
   ```

   **Review roles:**
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
7. **GATE 4 — Merge (squash).** Once all of the following are true:
   - CI green (all 4 checks pass)
   - Both reviews posted and verdicts are APPROVE
   - Every review comment has a response (accepted+implemented or declined with rationale)

   a) If the PR is a draft, mark it ready: `gh pr ready <PR_NUMBER>`
   b) Merge: `gh pr merge --squash --delete-branch <PR_NUMBER>`

   > **Note on auto-merge:** If the repo setting "Allow auto-merge" is enabled (Settings → General → Pull Requests), replace step (b) with:
   > `gh pr merge --squash --auto <PR_NUMBER>`
   > Then delete the branch after merge completes:
   > `gh api repos/:owner/:repo/git/refs/heads/<branch> -X DELETE`

   If any condition is not met, block and notify. Never force-push,
   never modify branch protection, and never merge on red/unknown CI.

   **Restrictions:**
   - A single bot identity **cannot self-approve** on GitHub (`--approve` returns `GraphQL: Review Can not approve your own pull request`).
     Always use `gh pr review --comment --body "VERDICT: APPROVE | REQUEST_CHANGES"` instead.
   - The review verdict lives in the comment body, not GitHub's native approval state.

## Honesty & escalation
- Evidence over assertion: verify with real tool output; never fabricate results,
  metrics, or test outcomes. If unknown, say so.
- Flag schema, architecture, or strategy changes for explicit human approval.
