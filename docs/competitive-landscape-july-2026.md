# Competitive Landscape — JVM Build-Tool MCP Servers (July 2026)

**Research date:** 2026-07-26  
**Previous research:** `docs/mcp-ecosystem-research-june-2026.md` (covered through June 2026)  
**Project:** mcp-server-jvm-build-tools v1.1.0 (released Jul 23, 2026) — 39 tools

---

## 1. New Entrants Since June 2026

Since the June 2026 ecosystem survey, several new JVM/build-tool MCP servers have appeared or gained significant traction.

### 1.1. JVM / Build Tool MCP Servers (New)

| Server | Author | Language | Stars (approx) | Focus | First Seen |
|--------|--------|----------|:---:|-------|:---:|
| **build-scout** | David-Parry | Java | New | Multi-build-system (Gradle, Maven, NPM, Cargo, Python, Make, CMake) | ~Nov 2025 |
| **gradle-mcp** | jermeyyy | Python | New | Gradle integration via FastMCP + Gradle wrapper | ~Jun 2026 |
| **gradle-mcp-server** (deprecated) | IlyaGulya | Kotlin | New | Gradle via Tooling API — **deprecated, replaced by gradle-mcp** | Jun 2026 |
| **maven-tools-mcp** | arvindand | TypeScript | ~New | Maven Central dependency intelligence; Maven/Gradle/SBT/Mill | May 2026 |
| **jvm-diagnostics-mcp** | brunoborges | Java | New | JVM diagnostic tools via JDK commands (jstack, jmap, jstat, etc.) | ~Jun 2026 |
| **maven-mcp-server** | Bigsy | TypeScript | 33 | Maven dependency version checking from Maven Central | ~2025 |
| **jdb-mcp** | d4n-sec | Python | New | Java Debugger via JDI (breakpoints, stack traces, variables) | Jan 2026 |
| **maven-mcp-server** | danielscholl | TypeScript | New | Lightweight Maven Central querying | ~2026 |
| **jarp-mcp** | tersePrompts | TypeScript | New | Decompiles compiled Java classes from dependencies | ~2026 |
| **spring-mcp-server** | mayank-yadav26 | Java | New | Spring Boot MCP server template | ~2026 |
| **remote-mcp-functions-java** | Azure-Samples | Java | New | Azure Functions-hosted MCP server template | ~2026 |

**Sources:**
- https://github.com/David-Parry/build-scout
- https://github.com/jermeyyy/gradle-mcp
- https://github.com/IlyaGulya/gradle-mcp-server
- https://github.com/arvindand/maven-tools-mcp
- https://github.com/brunoborges/jvm-diagnostics-mcp
- https://github.com/Bigsy/maven-mcp-server
- https://github.com/d4n-sec/jdb-mcp
- https://github.com/danielscholl/maven-mcp-server
- https://github.com/tersePrompts/jarp-mcp

### 1.2. Previously Tracked Competitors (Still Active)

| Server | Author | Stars (June → July) | Status |
|--------|--------|:---:|--------|
| **jvm-mcp-server** | xzq-xu | Unchanged | Python-based JVM monitoring (jps, jstack, jmap); still at v0.1.1 |
| **XcodeBuildMCP** | getsentry | 5,891 | iOS/macOS build tools (not JVM, but build-tool reference) |
| **unity-mcp-server** | AnkleBreaker-Studio | 259 | Unity build tools; 268 tools |

---

## 2. Competitive Analysis

### 2.1. Direct Build-Tool Competitors

These overlap most directly with mcp-server-jvm-build-tools:

#### **build-scout** (David-Parry)
- **Language:** Java (GraalVM native-image)
- **Build systems:** Gradle, Maven, NPM/Yarn, Cargo, Python, Makefile, CMake
- **Strengths:** Multi-language (not just JVM); native image for fast startup; supports prompts and roots
- **Weaknesses:** Broader but shallower than JVM-specific servers; fewer JVM-specific diagnostics
- **Github:** https://github.com/David-Parry/build-scout

#### **gradle-mcp** (jermeyyy)
- **Language:** Python (FastMCP)
- **Focus:** Gradle-only
- **Tools:** `list_projects`, `run_task`, `clean` (separated from run_task for safety)
- **Strengths:** Python ecosystem integration; separates cleaning from execution for safety
- **Weaknesses:** Gradle-only; Python dependency; no Maven/SBT support
- **Github:** https://github.com/jermeyyy/gradle-mcp

#### **maven-tools-mcp** (arvindand)
- **Language:** TypeScript
- **Focus:** Maven Central dependency intelligence (read-only)
- **Features:** Bulk operations, version comparison, stability filtering, dependency age analysis, Context7 integration
- **Strengths:** Universal JVM build tool support (Maven, Gradle, SBT, Mill); rich version analysis
- **Weaknesses:** Read-only (does not execute builds); dependency lookup only
- **Github:** https://github.com/arvindand/maven-tools-mcp

### 2.2. Adjacent / Diagnostic Competitors

These overlap partially with the diagnostics aspects of the project:

| Server | Focus | Overlap |
|--------|-------|---------|
| **jvm-diagnostics-mcp** (brunoborges) | JDK diagnostic commands | Low — different purpose (diagnostics vs. build) |
| **jvm-mcp-server** (xzq-xu) | Arthas-based JVM monitoring | Low — monitoring vs. build |
| **jdb-mcp** (d4n-sec) | Java debugger (JDI) | Low — debugging vs. build |
| **jarp-mcp** (tersePrompts) | Decompile classes | Low — decompilation vs. build |

---

## 3. Feature Comparison Matrix

| Feature | **mcp-server-jvm-build-tools** | build-scout | gradle-mcp | maven-tools-mcp | xzq-xu/jvm-mcp-server |
|---------|:---:|:---:|:---:|:---:|:---:|
| **Build Execution (Maven)** | ✅ | ✅ | ❌ | ❌ | ❌ |
| **Build Execution (Gradle)** | ✅ | ✅ | ✅ | ❌ | ❌ |
| **Build Execution (SBT)** | ✅ | ❌ | ❌ | ❌ | ❌ |
| **Build Execution (Other)** | ❌ | ✅ (NPM, Cargo, etc.) | ❌ | ❌ | ❌ |
| **Dependency Management** | ✅ | ✅ | ❌ | ✅ (central lookup) | ❌ |
| **Dependency Version Checking** | ✅ | Partial | ❌ | ✅ (advanced) | ❌ |
| **Build Profiling / Performance** | ✅ | ❌ | ❌ | ❌ | ❌ |
| **Test Execution & Analysis** | ✅ | ❌ | ✅ (basic) | ❌ | ❌ |
| **Multi-module Analysis** | ✅ | ✅ | ✅ | ❌ | ❌ |
| **Build History** | ✅ | ❌ | ❌ | ❌ | ❌ |
| **JVM Diagnostics** | ❌ | ❌ | ❌ | ❌ | ✅ |
| **MCP Spec 2025-11-25** | ✅ | ✅ | ✅ | ✅ | ✅ |
| **MCP Spec 2026-07-28** | ❌ | ❌ | ❌ | ❌ | ❌ |
| **STDIO Transport** | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Streamable HTTP Transport** | ✅ | ❌ | ❌ | ❌ | ❌ |
| **Native Image / Fast Startup** | ❌ | ✅ (GraalVM) | ❌ | ❌ | ❌ |
| **Language** | Java (Spring AI) | Java (GraalVM) | Python (FastMCP) | TypeScript | Python |
| **Tool Count** | **39** | ~10-15 (estimated) | ~5-10 | ~5-10 | ~5-10 |
| **Open Source License** | MIT | MIT | MIT | MIT | MIT |
| **GitHub Stars** | New (est.) | New | New | New | ~50 (est.) |
| **Last Updated** | Jul 23, 2026 | ~2026 | ~Jun 2026 | ~May 2026 | Nov 2025 |

---

## 4. Key Differentiators for mcp-server-jvm-build-tools

1. **Deepest JVM build tool coverage** — the only server supporting all three major JVM build tools (Maven, Gradle, SBT) with build *execution*, not just dependency lookup.

2. **Highest tool count (39)** among JVM build-tool MCP servers.

3. **Build profiling & history** — unique features not found in any competitor:
   - `profile_build` — detailed build performance analysis
   - `analyze_build_performance` — dependency resolution, task timing
   - Build history tracking across sessions

4. **Streamable HTTP transport** — competitors (gradle-mcp, build-scout) are stdio-only.

5. **Multi-module support** — deep Maven/Gradle multi-module project analysis.

6. **SBT 2.0.0 GA support** — the project tracks SBT releases (current: 2.0.0 GA).

---

## 5. Threats & Gaps

### Threats
- **build-scout (David-Parry)** is the most direct competitor — Java-based, multi-build-system, native-image packaged. If it deepens its JVM build tool features and adds HTTP transport, it could erode the project's differentiation.
- **maven-tools-mcp (arvindand)** has superior Maven Central intelligence with Context7 integration — the project should consider adding a similar feature (P2 SBOM work would partially address this).
- **gradle-mcp (jermeyyy)** is simpler and Python-based — easier for non-Java developers to configure. The project shouldn't try to compete on simplicity but should maintain its depth advantage.

### Gaps vs Competitors
- **No native-image / fast startup** — build-scout ships as a GraalVM native image
- **No dependency version analysis parity** with maven-tools-mcp
- **No JVM diagnostics** (but this is intentionally out of scope)
- **No simplified Python-based config** (but Java ecosystem integration is the core value)

---

## 6. Registry Presence

### modelcontextprotocol/registry
- The official registry at https://registry.mcpservers.org/ has several JVM entries but **mcp-server-jvm-build-tools is not listed**.
- **JVM entries in the registry:** build-scout, gradle-mcp, jvm-diagnostics-mcp, maven-mcp-server (Bigsy), jvm-mcp-server (xzq-xu)
- **Recommendation:** Submit the project to https://github.com/modelcontextprotocol/registry

### awesome-mcp-servers
- The curated list at https://github.com/appcypher/awesome-mcp-servers has a "Developer Tools" section
- **mcp-server-jvm-build-tools is not listed.**
- **Recommendation:** Submit a PR to add the project

---

## 7. JVM MCP Ecosystem Trends (July 2026)

1. **Proliferation of single-purpose JVM MCP servers** — diagnostics, debugging, decompilation, dependency lookup all have their own servers now. The trend is toward specialization rather than all-in-one servers.

2. **build-scout is the first multi-language build server** — this is the closest competitor to the project's "do it all" approach, but across languages rather than deep in one ecosystem.

3. **Python dominates for new JVM-adjacent MCP servers** — gradle-mcp, jvm-mcp-server, and jdb-mcp all use Python/FastMCP rather than Java SDK. This reflects Python's ease of MCP server development vs Java's complexity.

4. **No server has 2026-07-28 spec support yet** — all competitors are on 2025-11-25. The 2026-07-28 migration will be an industry-wide event, not a competitive differentiator.

5. **Microsoft and Azure enter JVM MCP space** — `Azure-Samples/remote-mcp-functions-java` and `microsoft/lets-learn-mcp-java` (LangChain4J + Quarkus) signal enterprise interest in JVM-based MCP servers on Azure.
