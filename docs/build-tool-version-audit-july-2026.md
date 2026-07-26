# Build Tool Version Audit — July 2026

**Research date:** 2026-07-26  
**Previous audit:** `docs/mcp-ecosystem-research-june-2026.md` (covered through June 2026)  
**Previously known:** Gradle 9.5–9.6, Maven 4.0.0-beta-5, SBT 2.0.0-RC16

---

## 1. Gradle

| Version | Status | Release Date | Notes |
|---------|--------|:---:|-------|
| 9.6.0 | **Stable** | Jun 18, 2026 | Improved configuration cache hit rates |
| 9.6.1 | **Latest stable** | Jun 26, 2026 | Patch: non-interactive flag, dependency cache permissions fix, deadlock fix |
| 9.7.0-M1 | Milestone | Jun 17, 2026 | Early preview |
| 9.7.0-M3 | Milestone | Jul 10, 2026 | Third milestone |
| **9.7.0-RC1** | **Release candidate** | **Jul 17, 2026** | **Next stable candidate** |

**Change from June 2026:** Gradle has progressed from 9.5–9.6 stable to **9.6.1 stable** with **9.7.0-RC1** now available. The previous research correctly identified the 9.5–9.6 range; 9.6.0 and 9.6.1 are now shipping.

### 9.6.x Highlights
- **Improved Configuration Cache hit rates** (9.6.0 headline feature)
- `--non-interactive` Gradle property support
- Dependency cache artifact permissions tightened to 0600
- `DefaultBuildOperationQueue` deadlock fix

### 9.7.0-RC1 Preview
- RC1 released July 17, 2026
- Release notes pending at https://docs.gradle.org/current/release-notes.html
- Available at https://services.gradle.org/distributions/
- Gradle moves fast — expect 9.7.0 stable within weeks

### CLI Impact on the Project
- No breaking CLI changes between 9.5 and 9.6.x
- The project's wrapper-based invocation should remain compatible
- 9.7.0 should be verified when stable ships

**Sources:**
- https://github.com/gradle/gradle/releases
- https://docs.gradle.org/current/release-notes.html
- https://gradle.org/releases/

---

## 2. Maven

| Version | Status | Release Date | Notes |
|---------|--------|:---:|-------|
| 3.9.16 | **Latest stable** | ~Jul 2026 | Recommended for production |
| 4.0.0-alpha-7 | Alpha | Feb 8, 2026 | Pre-beta |
| 4.0.0-beta-3 | Beta | Jul 12, 2026 | Java 17 runtime minimum |
| 4.0.0-beta-4 | Beta | ~2026 | Iteration |
| **4.0.0-beta-5** | **Latest beta** | **(previously reported)** | Still the latest Maven 4 beta |
| 3.10.0-rc-1 | RC | ~Jul 2026 | 3.x series RC |

**Change from June 2026:** Maven 4.0.0-beta-5 remains the latest Maven 4 beta. **No beta-6 or GA has shipped.** Maven 3.9.16 is now the latest stable 3.x release (was previously 3.9.x — minor patch updates only).

### Maven 4 Status
- **Still in beta** — not safe for production
- Java 17 minimum runtime requirement
- Key features in beta-5: project-specific `settings.xml`, BOM import exclusions, profile OS activation, mvnd improvements
- Maven 4.0.0-beta-3 was released July 12, 2026 (important: this is *after* beta-5, suggesting version numbering is not strictly sequential)

### Maven mvnd
- `apache/maven-mvnd` latest stable: **1.0.6** (released May 27, 2026)
- Provides faster Maven builds via daemon
- Recommended for use alongside the project's Maven build execution

### CLI Impact on the Project
- Maven 3.9.16 CLI is unchanged from what the project supports
- Maven 4 betas use a different CLI engine (Maven Resolver 2.x) — should verify compatibility when Maven 4 goes GA
- The project's Maven execution integration should continue working with both 3.x and 4.x

**Sources:**
- https://maven.apache.org/download.cgi
- https://maven.apache.org/docs/4.0.0-beta-5/release-notes.html
- https://github.com/apache/maven-mvnd/releases

---

## 3. SBT — MAJOR CHANGE: 2.0.0 Now GA

| Version | Status | Release Date | Notes |
|---------|--------|:---:|-------|
| **2.0.0** | **GA — First stable** | **Jun 14, 2026** | **SBT 2.0 stable is HERE** |
| 2.0.1 | Stable | ~Late Jun 2026 | Bug fixes (runner parsing, global plugin loading, OpenBSD support, Zinc fixes) |
| 2.0.2 | Stable | ~Jul 13, 2026 | Remote cache timeout, metabuild dependency, BSP fixes |
| **2.0.3** | **Latest stable** | **~Jul 16, 2026** | CVE-2026-26032 fix, dependency updates |
| 1.12.14 | Stable (1.x) | ~Jul 16, 2026 | CVE backport to 1.x |
| 2.0.0-RC16 | Superseded | ~May 2026 | No longer relevant — GA is out |
| 2.0.0-RC15 | Superseded | ~May 2026 | No longer relevant |
| 2.0.0-RC14 | Superseded | ~May 2026 | No longer relevant |

**This is the single biggest change from the June 2026 audit.** SBT 2.0.0 is no longer in RC — it has GA'd and shipped **three** patch releases.

### SBT 2.0.0 GA — Key Features
- Scala 3 constructs throughout
- **Bazel-compatible cache system** — task caching by default
- **Parallel testing by default**
- **Bazel-inspired remote caching** for shared build artifacts
- Coursier dependency resolution (parallel by default)
- Execution log for cache debugging
- BSP improvements
- FarmHash-based incremental compilation
- Scala 3.8.x support

### SBT 2.0.3 Bug Fixes
- CVE-2026-26032: packager cache fix
- Dependency updates: sjson-new 0.15.1, Jawn 1.7.0 (security fixes for GHSA-cc4v-rvgp-2pf3, GHSA-w4cm-gvhj-cgw6)
- JVM option capability in launcher config fix

### CLI Impact on the Project
- SBT 2.0.0 GA CLI should be backward compatible with current command invocations
- The project should test against SBT 2.0.3 as the reference version
- SBT 1.x (1.12.14) remains available for legacy projects
- Key changes to verify: `sbt` launcher behavior, `test` output format, cache directory structure

### SBT Version History (Final Pre-GA to GA)

```
RC14 → RC15 → RC16 → 2.0.0 GA → 2.0.1 → 2.0.2 → 2.0.3
                        │          │       │       │
                      Jun 14    Late Jun  Jul 13  Jul 16
```

**Sources:**
- https://github.com/sbt/sbt/releases
- https://eed3si9n.com/sbt-2.0.0
- https://users.scala-lang.org/t/sbt-2-0-0-released/12292

---

## 4. Ecosystem-Level Implications

### 4.1. Spring AI 2.0.0 GA (Separate Critical Finding)

Spring AI 2.0.0 GA was released June 12, 2026 — see `docs/mcp-spec-delta-july-2026.md` for full details. The project should upgrade from `2.0.0-RC2`.

### 4.2. Build Tool Compatibility Matrix

| Tool | Version in June Research | Version Now (Jul 26) | Status |
|------|:---:|:---:|--------|
| Gradle | 9.5–9.6 | **9.6.1 stable / 9.7.0-RC1** | Incremental improvement |
| Maven 3 | 3.9.x | **3.9.16** | Minor patch |
| Maven 4 | 4.0.0-beta-5 | **4.0.0-beta-5 (still)** | No progress — still beta |
| SBT 2 | 2.0.0-RC16 | **2.0.3 GA** | **MAJOR — now GA** |
| SBT 1 | 1.12.x | **1.12.14** | Patch-only maintenance |

### 4.3. Recommended Actions for the Project

1. **SBT 2.0 GA verification** — test the project's SBT integration against SBT 2.0.3 (and document the required SBT version in README/docs)
2. **Update ROADMAP.md** — references to "SBT 2.0.0-RC16" should be updated to "SBT 2.0.3"
3. **Gradle 9.7 verification** — test against 9.7.0-RC1 to catch any breaking changes before 9.7.0 stable ships
4. **Maven 3.9.16** — update recommended base version in documentation
5. **No Maven 4 GA urgency** — Maven 4 remains in beta; no immediate compatibility concern
6. **Consider CI testing** — add matrix builds testing against the latest stable of each build tool to catch regressions early
