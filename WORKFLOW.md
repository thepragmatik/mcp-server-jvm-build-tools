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
   - mvn test (JUnit tests)
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
1. **JUnit tests** — 375 tests, 0 failures required
2. **Coverage** — JaCoCo thresholds pending (not yet configured in POM)
3. **License headers** — mvn license:format runs (non-blocking)
4. **Compile warnings** — mvn compile -Dmaven.compiler.showWarnings=true

### Cross-Review Workflow

Every PR must pass a security review by `worker-adversarial` before merging.
Reviews are posted to GitHub for a permanent audit trail.

**Protocol (strict — do not deviate):**

1. Write review task to shared filesystem:
   ```
   /shared/inputs/worker-adversarial/task-N.md
   ```
2. Wait for worker output (~2-5 minutes):
   ```
   /shared/outputs/worker-adversarial/prN-review.md
   ```
3. Verify review exists, then post to GitHub:
   ```
   gh pr review N --comment --body "$(cat /shared/outputs/worker-adversarial/prN-review.md)"
   ```
4. Address any issues, push fixes, comment on PR with resolution.
5. Only merge when review verdict is PASS (no blocking issues remain).

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
| Current State       | All features integrated and merged to staging. Production integration PR target: main.               |
| Coverage            | JaCoCo not configured in POM — thresholds pending         |
| Tests               | 375 (GradleServiceTest: 64, SbtBuildToolTest: 51, DependencyServiceTest: 47, ToolAuthorizationServiceTest: 25, BuildAuthServiceTest: 20, MavenInvokerTest: 15, BuildOutputParserTest: 14, SupplyChainServiceTest: 14, MavenIntegrationTest: 13, BuildConfigurationValidationTest: 13, BuildCacheServiceTest: 11, MavenSecurityTest: 11, BuildConfigValidatorTest: 10, ResourceTemplateServiceTest: 10, AsyncBuildServiceTest: 9, TestFlakinessServiceTest: 9, SbtProjectServiceTest: 8, DependencyConflictServiceTest: 7, DependencyResourceServiceTest: 7, BuildPerformanceServiceTest: 6, JavaVersionServiceTest: 6, TransportConfigTest: 5) |

### Recovery

If a bad commit reaches main:
```bash
git checkout main
git revert <bad-commit-hash>
git push origin main
```
Never use `git push --force` or `git reset --hard` on main or staging.

## Swarm Automation Workflow

PRs created by the Metaswarm hierarchical agent swarm follow this process:

1. **Creation**: Engineer agents create feature branches from main, implement changes, and open PRs targeting staging.
2. **Adversarial Review**: Security-auditor agent reviews every PR for:
   - Security vulnerabilities (secrets, unsafe code patterns)
   - Code quality (exception handling, logging)
   - Conventional commit compliance
3. **CI Verification**: All PR checks must pass (JDK 21, 23, 25 + Quality checks).
4. **Remediation**: If CI fails or reviewer requests changes, engineer agents fix issues and push updates.
5. **Merge to Staging**: Once CI passes and review is complete, PRs are merged to staging.
6. **Production**: Staging PRs to main are left for human review per the branch strategy.

### Automated PR Naming Convention
- feat/* for new features
- fix/* for bug fixes
- docs/* for documentation
- chore/* for maintenance

### Agent Roles
- **mission_controller** (T1A Pro): Strategic planning, architecture decisions
- **security_auditor** (T1B Flash): Adversarial PR review, security scanning
- **sandbox_engineer** (T3 Local): Code changes, branch creation
- **catch_all** (T2 Local): Dead-letter task recovery
