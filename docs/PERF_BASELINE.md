# Server Startup & Tool-Registration Baseline

Measured: 2026-09-12, packaged jar (`target/mcp-server-jvm-build-tools.jar`,
1.3.1 + resource packaging fix shipped in PR #198), at commit bcff7b6 of
branch `fix/197-package-profile-resources`, laptop-class hardware
(Apple Silicon, 16 GB, JDK 25 / temurin, local disk, no container).

## Method

1. `mvn -DskipTests package` (jar packaged with profile resources included —
   see below).
2. Launch: `java -jar target/mcp-server-jvm-build-tools.jar
   --spring.profiles.active=http --server.port=<port>` with wall-clock start
   timestamp recorded immediately before `Process.start`.
3. Poll `GET /health` every 1 s until HTTP 200.
4. Query `GET /mcp/discover` for the registered tool count.

Three runs; all showed the same behaviour (two earlier runs were invalid —
they hit the jar-packaging bug below and never bound a port).

## Results

| Metric | Value |
|---|---|
| Spring context started (`Started BuildToolsApplication`) | 1.662 s |
| Process start → `/health` HTTP 200 | 9.65 s (wall clock incl. JVM + jar verification) |
| `/health` body | `{"status":"UP","version":"1.3.1","transport":"streamable-http"}` |
| Registered MCP tools (`/mcp/discover` `tools.count`) | 34 |
| Tool discovery services | 2 (`buildToolsService`, `toolAuthorizationService`) |

## Caveats

- `/health` reachability (~10 s) is dominated by JVM startup + class loading;
  the Spring context itself starts in ~1.7 s. Treat ~10 s as the cold-start
  budget for orchestrators that health-check on launch.
- Baseline is single-machine; CI runners and slower disks will see larger
  wall-clock numbers. Re-measure before using in an SLA.
- The earlier, invalidated runs are documented here because they exposed a
  real defect: the pom's `<resources>` include-list shipped only
  `application.properties`, silently excluding `application-http.properties`,
  `application-metrics.properties` and `logback-spring.xml` from the jar. With
  those files missing, the http profile never activated a servlet web server
  and the process sat idle forever with nothing listening. Diagnosed under
  PR #196 (closed unmerged after a branch rename); fixed and shipped by
  PR #198, with a regression test in
  `ProfileResourcesPackagingTest`.
- Version note: the jar reports `1.3.1` (pom not yet bumped). Re-measure at
  the next release version before treating these numbers as release SLA data.
