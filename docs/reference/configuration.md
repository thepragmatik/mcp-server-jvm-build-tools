# Configuration Reference

A complete reference for every configuration surface: environment variables, JVM flags, Spring Boot
properties, and the build-tool command/flag allowlists. For a task-oriented walkthrough, see the
[Configuration guide](../user-guide/configuration.md).

## Environment variables

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `MAVEN_HOME` | — | For Maven builds | Path to a Maven installation; `MAVEN_HOME/bin/mvn` must be executable. Without it, every Maven call must pass `buildToolHome`. |
| `MAVEN_OPTS` | — | No | JVM options for the **Maven** build process (e.g. `-Xmx1024m -XX:MaxMetaspaceSize=256m`). Used only by out-of-process Maven, not the server. |
| `GRADLE_OPTS` | — | No | JVM options for the **Gradle** build process. |
| `JAVA_HOME` | — | No | Used by Maven/Gradle/SBT to locate a JVM if they cannot find one. |
| `PATH` | inherited | No | Used to locate `gradle`, `sbt`, and `java`. |
| `SERVER_PORT` | `8080` | No | HTTP port used by `scripts/launcher.sh` when `--http` is passed. |
| `MCP_OPTS` | — | No | Extra JVM options passed by `scripts/launcher.sh`. |
| `GRADLE_HOME` / `SBT_HOME` | — | No | Optional install hints recognised by `scripts/launcher.sh`. |
| `BUILDTOOLS_API_KEY_<NAME>` | — | No | An API key value (used when authorization is enabled). |
| `BUILDTOOLS_API_KEY_<NAME>_SCOPES` | `*` | No | Comma-separated scopes for the matching key. Defaults to `*` (all) when unset. |

## Server JVM flags

These mirror the Dockerfile entrypoint and are recommended in the MCP client `args`:

| Flag | Purpose |
|------|---------|
| `-Xms64m` | Initial heap — low to avoid wasting memory at startup. |
| `-Xmx256m` | Maximum heap — the server is thin; 256 MB is ample. |
| `-XX:+UseG1GC` | G1 collector — low pause times suit the stdio transport. |
| `-XX:+UseStringDeduplication` | Reduce memory for repetitive build output strings. |
| `-XX:+ExitOnOutOfMemoryError` | Fail fast instead of degrading. |
| `-Djava.awt.headless=true` | No GUI; Spring Boot skips AWT init. |

Any Spring Boot property can be overridden with a `-D` flag, e.g.
`-Dlogging.level.com.pragmatik.buildtools=DEBUG`.

## Spring Boot properties

The shipped `application.properties` is baked into the JAR. Override at runtime with `-D` flags.

### Core

| Property | Default | Description |
|----------|---------|-------------|
| `spring.application.name` | `@project.name@` | Application identity (filtered at build time). |
| `spring.main.web-application-type` | `none` | No web server by default — this is a stdio server. |
| `spring.ai.mcp.server.name` | `@project.name@` | Canonical MCP server name. Single source shared by the server card, `server/discover`, and the `Mcp-Name` HeaderMismatch check (kept identical to `spring.application.name`). |
| `spring.ai.mcp.server.version` | `@project.version@` | MCP server version reported during `initialize`. |
| `spring.ai.mcp.server.stdio` | `true` | Enable the stdio transport. |
| `spring.main.banner-mode` | `off` | Suppress the banner — stdout must carry MCP JSON-RPC only. |
| `spring.jackson.deserialization.fail-on-unknown-properties` | `false` | Accept forward-compatible MCP fields (e.g. `extensions`). |

!!! danger "Keep stdout clean under stdio"
    Under the stdio transport, **stdout must contain only JSON-RPC**. The banner and console-log
    suppression below exist precisely to prevent corrupting the protocol.

### Logging

| Property | Default | Description |
|----------|---------|-------------|
| `logging.pattern.console` | *(empty)* | Suppress the console logging pattern (stdout is for MCP). |
| `logging.level.org.springframework` | `WARN` | Keep framework noise low. |
| `logging.level.com.pragmatik.buildtools` | *(unset)* | Set to `DEBUG` to troubleshoot the server. |

### Actuator

| Property | Default | Description |
|----------|---------|-------------|
| `management.endpoints.web.exposure.include` | `health,info` | Exposed Actuator endpoints. |
| `management.endpoint.health.show-details` | `when-authorized` | Expose detailed health information only to authorized principals. Because Spring Security is not on the classpath by default, no principal is ever authorized, so details are hidden from everyone (effectively `never`) until you add `spring-boot-starter-security` and configure roles. Set to `always` for unauthenticated local debugging (not recommended in production). |
| `management.endpoint.health.probes.enabled` | `true` | Enable readiness/liveness probes. |

### Streamable HTTP transport

The HTTP transport is opt-in. Activate it with the `http` Spring profile (the launcher's `--http`
flag does this) and set the port with `server.port`:

```bash
java -Dspring.profiles.active=http -Dserver.port=8080 \
  -jar target/mcp-server-jvm-build-tools.jar
```

!!! note "Stateless transport (2026-07-28 RC)"
    The Streamable HTTP transport is **stateless**: no protocol-level sessions, no
    `Mcp-Session-Id` header, and no SSE-stream resumability. Any replica can serve any request,
    and cross-call state is carried by explicit server-minted handles (e.g. an async build
    `taskId`) passed as ordinary tool arguments. The CORS allow-list advertises the standard
    `Mcp-Method` and `Mcp-Name` request headers (SEP-2243) and no longer the removed
    `Mcp-Session-Id`. These headers are validated against the request body by
    `McpHeaderValidationFilter`; absent headers (older clients) pass through unchanged.

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8080` | HTTP port (effective only with the `http` profile). |
| `mcp.transport.cors.allowed-origins` | `http://localhost:8080,http://127.0.0.1:8080` | Comma-separated CORS origins permitted to call `/mcp/**`. Restricted to local origins by default (no wildcard). List specific origins to widen for development; `*` is honoured for local testing only and must never be used in production. |
| `mcp.transport.sse.timeout-ms` | `1800000` | SSE stream timeout in milliseconds (30 minutes). |
| `mcp.transport.sse.max-subscribers` | `100` | Maximum concurrent SSE subscribers. |
| `mcp.transport.include-timing` | `true` | Include timing info in response headers. |

!!! warning "Harden HTTP for production"
    Deploy the HTTP transport behind a TLS-terminating reverse proxy and restrict
    `mcp.transport.cors.allowed-origins` to known domains. See the
    [Security reference](security.md#transport-security).

### MCP cache hints (SEP-2549)

The server advertises a per-method `{ttlMs, cacheScope}` caching policy under the `cacheHints` key
of its discovery surfaces (the server card and `server/discover`). The list surfaces are static for
the process lifetime, so they use a generous `public` TTL; per-project reads use a short `private`
TTL. Both TTLs are configurable:

| Property | Default | Description |
|----------|---------|-------------|
| `buildtools.cache.catalog-ttl-ms` | `86400000` | Freshness hint (ms) advertised for `tools/list`, `prompts/list`, `resources/list`, and `resources/templates/list` (`cacheScope: public`). Default 24h. |
| `buildtools.cache.read-ttl-ms` | `300000` | Freshness hint (ms) advertised for `resources/read` (`cacheScope: private`). Default 5min. |

See the [Tools reference](tools.md#cache-hints-ttlms-cachescope-sep-2549) for the emitted
`cacheHints` shape and the upstream note on per-result `CacheableResult` fields.

### Authorization and auditing

| Property | Default | Description |
|----------|---------|-------------|
| `buildtools.auth.enabled` | `false` | Require API keys with appropriate scopes to call tools. |
| `buildtools.auth.mode` | `permissive` | `permissive` = warn but allow; `enforcing` = deny unauthorized calls. |
| `buildtools.audit.enabled` | `true` | Write newline-delimited JSON of tool invocations. |
| `buildtools.audit.path` | `~/.buildtools/audit.log` | Audit log location. |

API keys are supplied as environment variables or system properties:

```bash
# Environment variables
export BUILDTOOLS_API_KEY_AGENT1=sk-your-key-value
export BUILDTOOLS_API_KEY_AGENT1_SCOPES=build:read,build:execute
```

```text
# System properties (alternative)
-Dbuildtools.api.key.agent2=sk-another-value
-Dbuildtools.api.key.agent2.scopes=build:read,build:execute
```

A key with **no scopes specified defaults to `*`** (full access). The 12 available scopes are
catalogued in the [Security reference](security.md#authorization-scopes).

!!! warning "Never commit secrets"
    Provide API keys via environment variables or system properties only — never in source code,
    commits, or logs.

### OAuth 2.1 resource server (HTTP transport)

Under the `http` profile the server aligns with the MCP OAuth 2.1 resource-server model
(RFC9728 / RFC6750) **additively**. RFC9728 Protected Resource Metadata is **always** served at
`GET /.well-known/oauth-protected-resource`; bearer-token **enforcement** on `/mcp/**` is opt-in,
so the default behaviour is byte-for-byte unchanged for existing clients. See the
[Security reference](security.md#oauth-21-resource-server-http-transport).

| Property | Default | Description |
|----------|---------|-------------|
| `buildtools.oauth.resource-server.enabled` | `false` | When `true`, require a valid `Authorization: Bearer <token>` on `/mcp/**`; a missing/invalid token returns `401` with `WWW-Authenticate: Bearer … resource_metadata="…"`. `server/discover` stays open so clients can learn how to authenticate. |
| `buildtools.oauth.resource` | *(blank)* | Canonical resource identifier advertised in the metadata `resource` field. Blank ⇒ derived per-request as `<scheme>://<host>[:<port>]/mcp`. Behind a reverse proxy set the external URL, e.g. `https://mcp.example.com/mcp`. |
| `buildtools.oauth.authorization-servers` | *(blank)* | Comma-separated OAuth authorization-server issuer URLs (RFC9728 `authorization_servers`); omitted from the metadata document when blank. |

Tokens are opaque bearer credentials validated locally against the configured
`BUILDTOOLS_API_KEY_*` store; full authorization-server work (JWT/JWKS, audience binding,
issuance, rotation, TLS) is delegated to a fronting OAuth-aware gateway. `scopes_supported` is
derived from the fine-grained `ToolPermission` scopes, and `offline_access` is never advertised.

## Build-tool command allowlists

Only these commands/tasks are permitted; anything else is rejected before a process is spawned.

| Build tool | Allowed commands |
|------------|------------------|
| **Maven** | `clean`, `compile`, `test`, `package`, `install`, `deploy`, `validate`, `dependency:tree` |
| **Gradle** | `clean`, `build`, `test`, `compileJava`, `compileTestJava`, `jar`, `assemble`, `check`, `publishToMavenLocal`, `dependencies`, `projects`, `tasks` |
| **SBT** | `compile`, `test`, `run`, `package`, `clean`, `assembly`, `publishLocal`, `publish`, `update`, `doc`, `console` |

!!! note "`dependency:tree` and `list_build_tools`"
    Maven's `dependency:tree` is permitted by the executor (`MavenInvoker.ALLOWED_COMMANDS`,
    8 entries) but is **not** reported by the `list_build_tools` tool, which surfaces
    `MavenBuildTool.getSupportedCommands()` (7 entries, without `dependency:tree`). If you
    cross-check this allowlist via `list_build_tools`, expect 7 Maven commands there even
    though `dependency:tree` is accepted when invoked.

## Build-tool flag policy

| Build tool | Safe flags | Blocked flags |
|------------|------------|---------------|
| **Maven** | `-D` (any key — passed through), `-f`, `-P`, `-q`, `-X`, `-T`, `-B`, `-U`, `--batch-mode`, `--non-recursive` | *(none — see below)* |
| **Gradle** | `-x`, `--exclude-task`, `--parallel`, `--configure-on-demand`, `--build-cache` | `--init-script`/`-I`, `--build-file`/`-b`, `--project-dir`/`-p`, `--include-build`, `--system-prop`/`-D` |
| **SBT** | *(standard tasks)* | `-D`, `-J`, `-sbt-dir`, `-sbt-boot`, `-sbt-launch-dir`, `-ivy`, `-maven-launcher` |

**Always-added flags:** Gradle runs with `--no-daemon --console=plain`; SBT runs with
`--no-colors`. These are appended automatically — do not include them in your command.

### Maven `-D` system properties

Maven `-D` system properties are passed through verbatim — there is no key allowlist or
blocklist. The server trusts the client's `-D` choices entirely. Shell metacharacters in any
token are still rejected by the safe-argument pattern, so injection via `-D` values is not
possible.

## MCP client configuration

The server uses standard MCP stdio transport and works with any stdio-capable client.

| Client | Config file |
|--------|-------------|
| Claude Desktop | `claude_desktop_config.json` (Settings → Developer) |
| Cursor | `.cursor/mcp.json` |
| Cline / Roo Code | `cline_mcp_settings.json` |
| Windsurf | Settings → Cascade → MCP Servers |
| Goose | `~/.config/goose/mcp.json` |
| Continue | `~/.continue/config.json` |
| GitHub Copilot | Agent mode (stdio) |

### Config field reference

| Field | Required | Description |
|-------|----------|-------------|
| `command` | yes | Path to the `java` executable (or `docker`). |
| `args` | yes | JVM flags + `-jar` + absolute path to the JAR. |
| `env` | no | `MAVEN_HOME`, `MAVEN_OPTS`, `GRADLE_OPTS`, `JAVA_HOME`, `PATH`. |
| `cwd` | no | Working directory; not required — the server does not rely on CWD. |

## Docker configuration

The multi-stage `Dockerfile` builds with JDK 21 Alpine and runs on JRE 21 Alpine, installing Maven
via `apk`. The entrypoint applies the recommended server JVM flags listed above.

```bash
docker run -i --rm \
  -v /host/projects:/projects \
  -v /opt/maven:/opt/maven \
  -e MAVEN_HOME=/opt/maven \
  mcp-server-jvm-build-tools
```

- `-i` is required for the stdio transport.
- Project paths in prompts must use **container** paths (`/projects/my-app`).
