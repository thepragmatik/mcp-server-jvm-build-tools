# Development Workflow — mcp-server-jvm-build-tools

## Branch Strategy

```
                    feature branches
                    (fix/*, feat/*, chore/*)
                           │
                           ▼  PR targets staging
                      staging
                    (integration)
                           │
                           ▼  PR targets main
                        main
                    (production)
```

## Rules

### NEVER push directly to `main` or `staging`

All changes must go through Pull Requests. Branch protection enforces:

| Branch   | Force Push | Deletion | Admin Override | Required Checks |
|----------|-----------|----------|----------------|-----------------|
| `main`   | Blocked   | Blocked  | Blocked        | test (21, 23, 25) + Quality checks |
| `staging`| Blocked   | Blocked  | Allowed        | test (21, 23, 25) + Quality checks |

### PR Workflow

```
1. Create feature branch from main:
   git checkout main && git pull && git checkout -b fix/my-fix

2. Commit changes:
   git add -A && git commit -m "fix: description"

3. Push and create PR targeting staging:
   git push origin fix/my-fix
   gh pr create --base staging --head fix/my-fix

4. CI runs automatically on staging:
   - mvn verify (JUnit + JaCoCo coverage)
   - JDK 21, 23, 25 matrix
   - Quality checks (license headers + compile warnings)

5. Review and merge to staging:
   gh pr merge <PR-number> --squash

6. When ready for production, create integration PR:
   gh pr create --base main --head staging

7. CI runs again targeting main → merge when green
```

### Commit Convention

- `fix:` — bug fixes, security patches
- `feat:` — new features (Gradle support, new tools)
- `test:` — test additions and improvements
- `ci:` — CI/CD pipeline changes
- `chore:` — maintenance (license headers, formatting)
- `docs:` — documentation

### Quality Gates

Every PR must pass:
1. **JUnit tests** — 53 tests, 0 failures required
2. **JaCoCo coverage** — reports generated (thresholds pending)
3. **License headers** — mvn license:format runs (non-blocking)
4. **Compile warnings** — mvn compile -Dmaven.compiler.showWarnings=true

### Cross-Review Workflow

Every PR must pass a security review by `worker-adversarial` before merging.
Cross-reviews are dispatched via the shared filesystem:

```
/shared/inputs/worker-adversarial/task-N.md  ← review task
/shared/outputs/worker-adversarial/prN-review.md  ← review output
```

**Review process:**
1. Write task to `/shared/inputs/worker-adversarial/task-N.md`
2. Worker picks up task (~5 min) and writes output to `/shared/outputs/`
3. Review output: PASS → merge, FAIL → fix, NEEDS_FIX → address issues

**Cross-dispatch protocol:** When one worker produces output, another reviews it:
- `worker-build` output → `worker-mcp` reviews protocol compliance
- `worker-mcp` output → `worker-quality` reviews testability
- `worker-quality` output → `worker-adversarial` reviews security
- `worker-adversarial` output → `worker-build` reviews build feasibility
- `worker-cicd` output → `worker-adversarial` reviews pipeline security

### Current State

| Component          | Status                                                    |
|--------------------|-----------------------------------------------------------|
| Main branch        | Production — must stay clean                              |
| Staging branch     | All 5 features integrated, CI green                       |
| Active PR          | #6 (staging → main) — pending review                      |
| Coverage           | 48% instruction / 33% branch / 46% line                   |
| Tests              | 53 (MavenInvoker: 17, MavenService: 12, Integration: 7, Security: 17) |

### Recovery

If a bad commit reaches main:
```bash
git checkout main
git revert <bad-commit-hash>
git push origin main
```
Never use `git push --force` or `git reset --hard` on main or staging.
