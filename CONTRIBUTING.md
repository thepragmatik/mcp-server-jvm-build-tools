# Contributing to mcp-server-jvm-build-tools

Thanks for your interest in contributing! This document outlines the process for contributing to this project.

## Getting Started

### Prerequisites
- Java 21 or later
- Apache Maven 3.9+
- Git

### Build and Test
```bash
git clone https://github.com/thepragmatik/mcp-server-jvm-build-tools.git
cd mcp-server-jvm-build-tools
mvn verify
```

## Branch Strategy

We use a two-branch model:

```
feature branches (feat/*, fix/*, chore/*)
       │
       ▼  PR targets staging
   staging (integration)
       │
       ▼  PR targets main
    main (production)
```

**Never push directly to `main` or `staging`.** All changes must go through Pull Requests.

See [WORKFLOW.md](WORKFLOW.md) for the full development workflow, branch protection rules, and CI details.

## Commit Conventions

We follow conventional commits:

| Prefix | Use for |
|--------|---------|
| `feat:` | New features (e.g., SBT support, new MCP tools) |
| `fix:` | Bug fixes, security patches |
| `test:` | Test additions and improvements |
| `ci:` | CI/CD pipeline changes |
| `docs:` | Documentation changes |
| `chore:` | Maintenance (license headers, formatting, dependency bumps) |

## Pull Request Process

1. Create a feature branch from `main`:
   ```bash
   git checkout main && git pull && git checkout -b feat/my-feature
   ```
2. Make your changes with conventional commits
3. Push and create a PR targeting `staging`:
   ```bash
   git push origin feat/my-feature
   gh pr create --base staging --head feat/my-feature
   ```
4. CI runs automatically on JDK 21, 23, and 25
5. Address review feedback and ensure all checks pass
6. When approved, the PR is squash-merged into `staging`
7. Periodically, `staging` is merged into `main` via a separate integration PR

## Quality Gates

Every PR must pass:
- **375 tests** with 0 failures (JUnit 5)
- **JaCoCo coverage** — reports generated for information (48% instruction / 33% branch / 46% line baseline); enforcement thresholds are pending (not yet configured in POM)
- **License headers** — `mvn license:format` must pass
- **Compile warnings** — `mvn compile -Dmaven.compiler.showWarnings=true` must be clean

## Reporting Issues

Use [GitHub Issues](https://github.com/thepragmatik/mcp-server-jvm-build-tools/issues) for:
- Bug reports (include steps to reproduce, expected vs actual behavior, environment details)
- Feature requests (describe the use case and how it fits the project scope)
- Documentation improvements

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for a detailed walkthrough of the codebase structure, class relationships, tool registration, and security model.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
