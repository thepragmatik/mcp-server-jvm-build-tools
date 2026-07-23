# Design Specification — mcp-server-jvm-build-tools v1.1.0

**Task:** ARCH-1 Gap Analysis & Design for Next Iteration
**Date:** 2026-07-23
**Author:** hswarm-arch
**Input:** RSRCH-1 SWARM-RESEARCH.md (competitive landscape, 12 ranked recommendations)
**Status:** Design complete — ready for ENG implementation

---

## 0. Executive Summary

Based on RSRCH-1's competitive landscape analysis of 7 JVM build MCP servers, v1.1.0 targets three P0 features that close the most critical competitive gaps:

| # | Feature | RSRCH-1 Rec | Competitive Rationale |
|---|---------|-------------|----------------------|
| F1 | **POM-aware dependency analysis** | P0#1 | Closes #1 gap vs maven-tools-mcp (they own dep intelligence) |
| F2 | **CVE/security signals in dependency checks** | P0#3 | Turns version lookup into must-use tool for enterprise teams |
| F3 | **Android project detection & build support** | P0#4 | Greenfield differentiator — no JVM build MCP server supports Android |

These three features are deliberately scoped small (max 3 per iteration) and are ordered for incremental implementation: F1 builds the dependency foundation that F2's CVE scanning layers on top of. F3 is orthogonal and can be implemented in parallel.

### What is NOT in v1.1.0

- **Upgrade recommendation engine (P0#2)** — deferred to v1.2.0; requires F1 as prerequisite and adds significant UX complexity (deterministic edit actions, conflict detection, human-review gating)
- **Kotlin REPL (P1#5)** — deferred; gradle-mcp already owns this niche and it requires JDK 25+ / JBang, which conflicts with our JDK 21 baseline
- **Agent Skills framework (P1#6)** — deferred; requires design of skill format, discovery, and injection that's better done in a dedicated iteration
- **Develocity integration (P1#7)** — deferred; enterprise-only feature with niche audience; revisit when we have community traction

---

## 1. Selected Features & Architecture Impact

### F1: POM-aware dependency analysis

**Current state:** `DependencyService.check_dependency_version` queries Maven Central for a single artifact's version metadata. It has no knowledge of POM structure — parent chains, `<dependencyManagement>`, BOM imports, or property interpolation. A user asking "what version of Jackson does my project actually resolve to?" gets an answer that may not reflect reality.

**Target state:** A new `analyze_pom_dependencies` tool that reads the project's `pom.xml`, walks the parent POM chain, resolves `<dependencyManagement>` (including imported BOMs), interpolates properties, and classifies every dependency as EXPLICIT (directly declared), MANAGED (version inherited from depMgmt), or OVERRIDE (explicit version that differs from managed).

#### Architecture Impact

```
NEW:  com.pragmatik.buildtools.dependency.pom/
        ├── PomAnalyzer.java          (POM parsing + parent chain walking)
        ├── PomDependencyResolver.java (dependencyManagement + BOM resolution)
        └── PomModel.java             (internal model: DependencyEntry, ResolvedDependency)

MODIFIED:
  com.pragmatik.buildtools.dependency/
        └── DependencyService.java    (adds analyze_pom_dependencies @Tool method)

MODIFIED:
  com.pragmatik.buildtools.application/
        └── BuildToolsApplication.java (if PomAnalyzer is a separate service bean;
                                         if inline in DependencyService, no change needed)
```

**Design decision: Inline vs separate service.** Since `PomAnalyzer` is always used through `DependencyService` and never independently, implement it as package-private classes within the `dependency` package, called directly by `DependencyService`. No new @Service bean — keeps the tool registration surface clean. `DependencyService` gains one new @Tool method but stays one bean.

#### New Tool Signature

```
analyze_pom_dependencies(projectDir: String, resolveTransitive: boolean = false)
  -> JSON {
       project: { groupId, artifactId, version, packaging, parent: {...} },
       dependencies: [
         { groupId, artifactId, version, scope, classification: EXPLICIT|MANAGED|OVERRIDE,
           source: "pom.xml:45" | "parent:spring-boot-starter-parent:3.5.14" | "BOM:spring-cloud:2024.0.0" }
       ],
       managedDependencies: [...],    // from <dependencyManagement>
       importedBoms: [...],           // resolved BOM GAVs
       propertySubstitutions: {...},  // ${project.version} → "1.0.0"
       unresolvedProperties: [...],   // properties that couldn't be resolved
       parentChain: ["self", "spring-boot-starter-parent:3.5.14", ...],
       warnings: [...]
     }
```

#### Key Design Decisions

1. **Parent chain walking:** Follow Maven's parent resolution rules — first check local filesystem (`../pom.xml`), then local repository (`~/.m2/repository/...`), then remote (Maven Central). Stop at the first unresolvable parent. This mirrors how Maven itself resolves parents without requiring a full Maven execution.

2. **BOM resolution:** For each `<dependencyManagement>` import-scoped dependency, fetch the BOM's `pom.xml` from the local repo or Maven Central, extract its `<dependencyManagement>` section, and merge into the managed dependency map. Order respects Maven's "nearest wins" rule.

3. **Property interpolation:** Support `${project.version}`, `${project.groupId}` (from current or parent POM), and explicit `<properties>` blocks. Mark unresolved properties with a warning rather than failing — the tool is advisory, not a build replacement.

4. **No Maven execution required:** Unlike the existing `execute_build_command` which shells out to Maven, this tool is pure Java — it reads and parses XML files using the same pattern as `validateBuildConfiguration` in `BuildToolsService`. This makes it fast (no JVM startup) and works even when Maven isn't installed.

5. **Scope:** Gradle and SBT projects get a clear "this tool requires a Maven POM project" error message. A future iteration can add Gradle variant-aware dependency resolution, but the immediate competitive gap is Maven POM intelligence (maven-tools-mcp is Maven-only and owns this space).

#### Risk: Non-standard repository layouts

Some organizations use custom Maven repository layouts. The tool's local repo path defaults to `~/.m2/repository` (standard). Mitigation: accept an optional `localRepositoryPath` parameter, defaulting to `~/.m2/repository`. Document this in the tool description.

---

### F2: CVE/security signals in dependency checks

**Current state:** `DependencyService.check_dependency_version` returns version metadata with no security context. A user checking `org.springframework:spring-web:5.3.0` sees version info but no indication that this version has known CVEs.

**Target state:** Two changes:

**(a)** Extend `check_dependency_version` with an optional `includeSecurityInfo` parameter. When true, the response includes `cveCount`, `highestSeverity` (CRITICAL/HIGH/MEDIUM/LOW/NONE), and `knownVulnerabilities[]` with CVE IDs and summaries.

**(b)** New `scan_dependency_cves` tool that bulk-scans all direct dependencies from a project's `pom.xml` or `build.gradle` against OSV.dev's API, returning a prioritized vulnerability report.

#### Architecture Impact

```
NEW:  com.pragmatik.buildtools.dependency.security/
        └── CveLookupService.java    (OSV.dev API client + severity classification)

MODIFIED:
  com.pragmatik.buildtools.dependency/
        └── DependencyService.java   (extends check_dependency_version with securityInfo param;
                                      adds scan_dependency_cves @Tool method)
```

**Design decision: OSV.dev vs OWASP.** OSV.dev is chosen because:
- Free REST API with no API key requirement
- Open source, community-maintained vulnerability database
- Supports Maven/Gradle package identifiers natively
- Faster than OWASP Dependency-Check (no NVD feed download)
- The project already has OWASP dependency-check in its CI pipeline (owasp profile in pom.xml) — OSV.dev fills the runtime gap

The `CveLookupService` is a lightweight HTTP client (reusing the same `HttpClient` pattern as `DependencyService`) that queries `https://api.osv.dev/v1/query` with a package payload.

#### Tool Signature Changes

**Modified tool:** `check_dependency_version`

```
check_dependency_version(groupId, artifactId, currentVersion?, versionPreference?,
                         projectDir?, includeSecurityInfo: boolean = false)
  -> Adds to existing response:
     security: {
       cveCount: 4,
       highestSeverity: "CRITICAL",
       vulnerabilities: [
         { id: "CVE-2024-22243", severity: "CRITICAL",
           summary: "Spring Framework URL parsing with double-encoding",
           fixedIn: "5.3.34", cvssScore: 9.8 },
         ...
       ]
     }
```

**New tool:** `scan_dependency_cves`

```
scan_dependency_cves(projectDir: String, severityThreshold: String = "HIGH")
  -> JSON {
       project: { tool: "maven"|"gradle", dir: "..." },
       scanSummary: { totalDeps: 42, vulnerableDeps: 3, criticalCount: 1, highCount: 2 },
       vulnerabilities: [
         { dependency: "org.springframework:spring-web:5.3.0",
           cves: [ { id: "CVE-2024-...", severity: "CRITICAL", fixedIn: "5.3.34" } ],
           recommendation: "Upgrade to 5.3.34 or later (PATCH upgrade)" }
       ],
       scannedAt: "2026-07-23T12:00:00Z"
     }
```

#### Key Design Decisions

1. **Rate limiting:** OSV.dev has rate limits. The bulk scanner batches requests (max 10 deps per parallel request, 5 parallel HTTP connections). For projects with 100+ direct dependencies, the scan will take ~2-3 seconds. Document the expected latency.

2. **Caching:** Simple in-memory LRU cache (TTL: 1 hour) for OSV.dev responses. A dependency's vulnerability profile doesn't change minute-to-minute, and this prevents redundant API calls during an agent session.

3. **Severity classification:** Map CVSS v3 scores — 9.0+ = CRITICAL, 7.0-8.9 = HIGH, 4.0-6.9 = MEDIUM, 0.1-3.9 = LOW, 0.0 = NONE. This aligns with the project's existing OWASP CVSS threshold (failBuildOnCVSS=7).

4. **Gradle support:** For Gradle projects with `gradle/libs.versions.toml`, parse version catalog entries. For `build.gradle` with inline dependencies, use regex extraction (same approach as `DependencyConflictService`). This is coarse but functional — deep Gradle dependency resolution is a v1.2.0 item.

5. **Error handling:** If OSV.dev is unreachable, return partial results with a `warnings` field indicating which dependencies could not be scanned. Never fail the entire tool call on a network error.

---

### F3: Android project detection & build support

**Current state:** `GradleBuildTool` detects Gradle projects via `build.gradle(.kts)` and `settings.gradle(.kts)`. It has no awareness of Android project structure — `android {}` blocks, AGP (Android Gradle Plugin) versioning, build variants, or Android-specific tasks like `assembleDebug`.

**Target state:** Android projects are detected during `isProject()` and `detectBuildTool()` scans. The Gradle task allowlist includes Android-specific tasks. The detection response surfaces Android build variants, AGP version, and SDK configuration.

#### Architecture Impact

```
MODIFIED:
  com.pragmatik.buildtools.gradle/
        └── GradleBuildTool.java      (extends MARKER_FILES, ALLOWED_TASKS;
                                        adds detectAndroidProject(), getAndroidBuildVariants())

MODIFIED:
  com.pragmatik.buildtools.build/
        └── BuildToolsService.java    (extends detectBuildTool() with Android hints;
                                        extends execute_build_command to allow Android tasks)
```

**No new classes needed.** Android support is an extension of existing Gradle infrastructure. The changes are additive to two existing files.

#### Detection Logic

Android projects are detected by scanning `build.gradle(.kts)` and `app/build.gradle(.kts)` for:

1. **AGP plugin declaration:**
   - Groovy: `apply plugin: 'com.android.application'` or `apply plugin: 'com.android.library'`
   - Kotlin DSL: `id("com.android.application")` or `id("com.android.library")`

2. **Android block:** `android {` (Groovy) or `android {` (Kotlin DSL)

3. **AGP version:** Parsed from root `build.gradle(.kts)` classpath or `gradle/libs.versions.toml`

4. **Build variants:** Extracted from `android { buildTypes { } }` and `flavorDimensions { }` blocks

#### New/Modified Gradle Tasks

Current ALLOWED_TASKS: clean, build, test, compileJava, compileTestJava, jar, assemble, check, publishToMavenLocal, dependencies, projects, tasks

Android additions (task names — full qualified paths like `:app:assembleDebug` are validated by colon-segment extraction already in `parseCommandTokens`):

```
assembleDebug        - Build debug APK/AAB
assembleRelease      - Build release APK/AAB
connectedCheck       - Run instrumentation tests on connected device
connectedAndroidTest - Run Android instrumentation tests
lint                 - Run Android lint checks
installDebug         - Install debug build on connected device
bundleDebug          - Build debug Android App Bundle
bundleRelease        - Build release Android App Bundle
```

Flag extensions: `--device-id` (safe flag pattern match), `-Pandroid.testInstrumentationRunnerArguments.class`

#### detectBuildTool() Changes

When Android markers are found, the detection response adds:

```json
{
  "hints": [
    "Android project detected (AGP)",
    "Android application module (:app) — buildTypes: [debug, release]",
    "AGP version: 8.11.0 (from gradle/libs.versions.toml)",
    "compileSdk: 36, minSdk: 24, targetSdk: 36",
    "Build variants: debug, release"
  ]
}
```

#### Key Design Decisions

1. **Detection is read-only.** We parse `build.gradle(.kts)` to detect Android markers and extract metadata, but we do NOT execute AGP. The build execution path remains unchanged — `executeBuildCommand("gradle", ..., "assembleDebug")` delegates to Gradle's own execution, which already handles AGP.

2. **No AGP dependency.** The server does NOT add AGP as a dependency. It only needs to recognize Android project structures in the same way it recognizes `pom.xml` for Maven. Build execution is delegated to the project's own Gradle/AGP installation.

3. **Flavor support.** For projects with `flavorDimensions`, the detection response lists available flavors but does not generate all variant combinations (which could be dozens). The LLM agent is expected to use `assemble<Flavor>Debug` syntax when needed.

4. **Multi-module Android projects.** The `detectBuildTool` scan walks one level down (into common subdirectories like `app/`, `lib/`, `feature-*/`) to detect per-module Android configuration. This mirrors how Android Studio discovers modules.

---

## 2. New/Modified Service Classes & Tool Signatures (Summary)

### New Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `PomAnalyzer` | `dependency.pom` | POM parser: parent chain walking, XML parsing, property extraction |
| `PomDependencyResolver` | `dependency.pom` | dependencyManagement + BOM import resolution |
| `PomModel` | `dependency.pom` | Internal model: `DependencyEntry`, `ResolvedDependency`, `PomInfo` |
| `CveLookupService` | `dependency.security` | OSV.dev API client with LRU cache, severity classification |

Total: 4 new classes (1 package-private util + 2 used by DependencyService + 1 independent service)

### Modified Classes

| Class | Changes |
|-------|---------|
| `DependencyService` | +`analyzePomDependencies()` tool method, extends `checkDependencyVersion()` with `includeSecurityInfo` param, +`scanDependencyCves()` tool method |
| `GradleBuildTool` | Extends `ALLOWED_TASKS` (+7 Android tasks), extends `MARKER_FILES`, adds `detectAndroidProject()` and `getAndroidBuildVariants()` methods |
| `BuildToolsService` | Extends `detectBuildTool()` Gradle case with Android hints, Android task names in description |
| `BuildToolsApplication` | No change (new methods added to existing service beans) |

### Full Tool Delta (28 → 30 tools)

| Status | Tool Name | Service |
|--------|-----------|---------|
| **NEW** | `analyze_pom_dependencies` | DependencyService |
| **NEW** | `scan_dependency_cves` | DependencyService |
| MODIFIED | `check_dependency_version` +`includeSecurityInfo` param | DependencyService |
| MODIFIED | `execute_build_command` accepts Android task names | BuildToolsService |
| MODIFIED | `detect_build_tool` surfaces Android project info | BuildToolsService |

### Modified BuildTool SPI

The `BuildTool` interface is **not modified**. Android support is detection-only at the `GradleBuildTool` level; it doesn't change the SPI contract. `GradleBuildTool.isProject()` just becomes more sophisticated (checks more marker patterns), and `getSupportedCommands()` returns more tasks.

---

## 3. Test Strategy

### F1: POM-aware dependency analysis

**Test class:** `PomAnalyzerTest` (new, in `src/test/java/.../dependency/pom/`)

| Test | What it verifies |
|------|-----------------|
| `parseSimplePom_returnsDependencies` | Basic POM with explicit deps — classification = EXPLICIT |
| `parsePomWithDepMgmt_classifiesManaged` | POM with `<dependencyManagement>` — managed deps = MANAGED |
| `parsePomWithOverride_classifiesOverride` | DepMgmt says v1.0, explicit dep declares v2.0 = OVERRIDE |
| `parsePomWithParent_walksParentChain` | Multi-level parent chain with inherited deps |
| `parsePomWithBomImport_resolvesBom` | Spring Boot starter-parent → BOM deps appear as MANAGED |
| `parsePomWithProperties_interpolatesCorrectly` | `${project.version}` and custom properties resolved |
| `parsePomWithUnresolvedProperties_warns` | Unresolvable `${some.unknown.property}` → listed in warnings |
| `parseMalformedPom_returnsError` | Invalid XML → clear error message, not stack trace |
| `parsePomWithMissingParentPartiallyResolves` | Parent not in local/remote → partial result with warnings |
| `detectGradleProject_returnsToolError` | Gradle project → "this tool requires a Maven POM" message |

**Test fixtures:** Create `src/test/resources/test-pom-projects/` with:
- `simple/` — single pom.xml with explicit deps
- `with-dep-mgmt/` — pom.xml + parent reference
- `with-bom/` — Spring Boot starter-parent with BOM import
- `with-properties/` — property substitution cases
- `malformed/` — invalid XML
- `multi-module/` — parent + child subdirectories

### F2: CVE/security scanning

**Test class:** `CveLookupServiceTest` (new, in `src/test/java/.../dependency/security/`)

| Test | What it verifies |
|------|-----------------|
| `lookupKnownVulnerable_returnsCves` | `org.springframework:spring-web:5.3.0` → known CVEs returned |
| `lookupSecure_returnsEmptyList` | Latest version with no known CVEs → empty array |
| `lookupNonexistentPackage_returnsEmpty` | Non-existent GAV → empty, no error |
| `lookupNetworkError_returnsWarning` | Mocked connection failure → warning in response, no exception |
| `cacheHit_doesNotMakeHttpCall` | LRU cache returns cached result within TTL |
| `cacheExpiry_makesFreshCall` | Stale cache entry → new HTTP request |

**Test class:** `DependencyServiceSecurityTest` (extend existing `DependencyServiceTest`)

| Test | What it verifies |
|------|-----------------|
| `checkDependencyVersion_withSecurityInfo_returnsCves` | Integration: real DependencyService call with `includeSecurityInfo=true` |
| `checkDependencyVersion_withoutSecurityInfo_noCves` | Backward compat: default `false` doesn't add security field |
| `scanDependencyCves_mavenProject` | Integration: pom.xml project scanned against OSV.dev |
| `scanDependencyCves_gradleProject` | Integration: build.gradle project with libs.versions.toml |

### F3: Android project detection

**Test class:** `GradleBuildToolAndroidTest` (new)

| Test | What it verifies |
|------|-----------------|
| `detectAndroidAppModule_returnsTrue` | `app/build.gradle.kts` with `id("com.android.application")` |
| `detectAndroidLibModule_returnsTrue` | Module with `id("com.android.library")` |
| `detectPlainGradleProject_returnsFalse` | Standard build.gradle → `isAndroidProject()` = false |
| `detectAndroidViaGroovyDSL` | `apply plugin: 'com.android.application'` in build.gradle |
| `getAndroidBuildVariants_singleBuildType` | Only 'debug' build type → [debug] |
| `getAndroidBuildVariants_withFlavors` | flavorDimensions = ['env'], flavor 'prod' → [prodDebug, prodRelease] |
| `getAndroidBuildVariants_debugAndRelease` | Default AGP types → [debug, release] |
| `getAgpVersion_fromVersionCatalog` | AGP pinned in `gradle/libs.versions.toml` |
| `getAgpVersion_fromClasspath` | AGP in root build.gradle classpath |

**Test class:** `BuildToolsServiceAndroidTest` (extend existing `BuildToolsService` tests)

| Test | What it verifies |
|------|-----------------|
| `detectBuildTool_androidProject` | Integration: Android project scan includes Android hints |
| `executeBuildCommand_assembleDebug_allowed` | Android task in allowlist → command accepted |

**Test fixtures:** Create `src/test/resources/test-android-project/`:
- Single-module Android app with `build.gradle.kts` + AGP
- Groovy DSL variant with `build.gradle`
- Multi-module with `app/` and `lib/` subdirectories
- Project with `gradle/libs.versions.toml` version catalog

### Test Quality Gates

- **Coverage:** Each new class must maintain or exceed existing thresholds: line >= 60%, branch >= 50%
- **Flakiness:** New tests must not introduce flaky behavior — no network-dependent tests in CI (use mocks for OSV.dev)
- **Existing tests must pass:** All 375 existing tests must remain green
- **Integration tests:** At least 2 real Maven Central queries (for CVE lookup) and 1 real Gradle Android project scan

---

## 4. Risk Assessment

### 4.1 Backward Compatibility

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| `check_dependency_version` response shape change | Low | Medium | New `includeSecurityInfo` param defaults to `false` — existing callers see identical response. Only `true` adds `security` field. |
| `analyze_pom_dependencies` breaks on non-standard POMs | Medium | Low | Non-standard POMs produce partial results with warnings, not errors. The tool is advisory. |
| Android task names clash with custom Gradle tasks | Low | Medium | Android tasks (`assembleDebug`, etc.) are standard AGP names. Custom tasks with these exact names are extremely unlikely. If they do exist, they're already in the allowlist via the `assemble` base task. |
| `GradleBuildTool.isProject()` broader detection | Low | Low | Adding Android markers to `isProject()` doesn't change behavior for non-Android projects — it just returns `true` for Android projects that were previously undetected. |

### 4.2 Security

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| POM parsing reads arbitrary file paths | Low | High | `analyze_pom_dependencies` only reads files under `projectDir` + the configured Maven local repo. Parent POM resolution uses `Path.of(projectDir).resolve("../pom.xml")` which is bounded by `toRealPath()` canonicalization. Remote POM fetching uses the same hardened HTTP client as existing `DependencyService` (5s timeout, no redirect to private hosts). |
| OSV.dev API response injection | Low | Medium | OSV.dev responses are structured JSON. CVE IDs and summaries are treated as strings, never evaluated. No shell execution path. |
| Android build tasks introduce new attack surface | Very Low | Low | Android tasks use the same `parseCommandTokens()` validation pipeline as existing Gradle tasks. Flag blocklist (`-I`, `-b`, `-D`) remains in effect. The task allowlist just gets 7 new entries. |
| Information disclosure via CVE scan | Low | Medium | `scan_dependency_cves` sends dependency GAVs to OSV.dev's public API. This is intended behavior (OSV.dev is a public vulnerability database). Document this in the tool description. |

### 4.3 Performance

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| POM parent chain walking is slow for deep hierarchies | Medium | Medium | Typical parent chain depth is 1-3 (self → spring-boot-starter-parent → spring-boot-dependencies). Each parent POM is read once from local repo or fetched once from remote. LRU cache in `PomAnalyzer` avoids re-fetching the same parent POM for multiple calls. |
| OSV.dev bulk scan for 100+ dependencies | Medium | Medium | Parallel HTTP requests (max 10 concurrent), LRU cache (1h TTL), batch size of 10 deps per request. For 100 deps: ~3-4 seconds. Document expected latency and provide `severityThreshold` filtering to skip low-severity results. |
| Android detection scans build files for every `isProject()` call | Very Low | Low | `isProject()` is a simple `Files.exists()` call for marker files — no file parsing. Android detection via `detectAndroidProject()` is only invoked in `detectBuildTool()`, which is a user-initiated scan, not on every build execution. |

### 4.4 Operational Risk

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| OSV.dev API deprecation or downtime | Low | Medium | `CveLookupService` returns partial results with warnings on failure. The tool doesn't block on network errors. Future iteration can add fallback providers (GitHub Advisory Database, NVD API). |
| AGP version parsing breaks on non-standard declarations | Medium | Low | AGP version extraction uses multiple fallback strategies: version catalog (`libs.versions.toml`) → classpath declaration → `pluginManagement` block. If all fail, returns "unknown" version with no error. |

---

## 5. Implementation Order & Dependencies

```
Phase 1: F1 (POM-aware dependencies)  ← independent, foundation for F2
  │
  ├─ PomAnalyzer + PomModel
  ├─ analyzePomDependencies() tool
  └─ PomAnalyzerTest + test fixtures

Phase 2: F2 (CVE scanning)            ← depends on F1's dependency model
  │
  ├─ CveLookupService
  ├─ Extended check_dependency_version
  ├─ scan_dependency_cves tool
  └─ CveLookupServiceTest + integration tests

Phase 3: F3 (Android support)          ← orthogonal, can run in parallel with Phase 1-2
  │
  ├─ GradleBuildTool extensions
  ├─ BuildToolsService detection updates
  └─ GradleBuildToolAndroidTest + test fixtures
```

**Estimated total effort:** 2-3 engineer-days (including tests and review)

---

## 6. Review Gates (per AGENTS.md)

Before merging to main:

1. **CI:** `mvn -B verify` GREEN on all JDK versions (21, 23, 25)
2. **Coverage:** JaCoCo line >= 60%, branch >= 50% maintained or improved
3. **SpotBugs:** No new High-severity findings
4. **OWASP:** No new High/Critical vulnerabilities (`-Powasp verify`)
5. **Adversarial review:** Verify POM parsing handles edge cases (circular parent refs, missing BOMs, XML entities)
6. **Code-quality review:** Verify class count stays manageable (4 new classes is fine; if it grows past 8 for any feature, refactor before merging)

---

## A. Appendix: Competitor Feature Parity Check

After v1.1.0, the competitive position vs key rivals:

| Feature | Us v1.0.0 | Us v1.1.0 | gradle-mcp | maven-tools-mcp |
|---------|-----------|-----------|------------|-----------------|
| POM-aware dep analysis | ❌ | ✅ | ❌ | ✅ |
| CVE/security scanning | ❌ | ✅ | ❌ | ✅ |
| Android build support | ❌ | ✅ | ❌ | ❌ |
| Multi build tool (Maven+Gradle+SBT) | ✅ | ✅ | ❌ Gradle-only | ❌ Maven Central only |
| MCP-RC compliance | ✅ | ✅ | ❌ | ❌ |
| Kotlin REPL | ❌ | ❌ | ✅ | ❌ |
| Upgrade recommendation engine | ❌ | ❌ | ❌ | ✅ |
| Agent Skills | ❌ | ❌ | ✅ | ❌ |

**Key takeaway:** v1.1.0 closes 3 critical gaps without compromising our core differentiators (multi-tool support, MCP-RC compliance, breadth of 30+ tools). Android support is a unique greenfield advantage. Post-v1.1.0, we match or exceed maven-tools-mcp on dependency intelligence and offer capabilities no other JVM build MCP server has (Android).
