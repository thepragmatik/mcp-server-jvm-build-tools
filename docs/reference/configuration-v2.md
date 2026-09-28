# Configuration 2.0

This page describes the current 2.0 development line. Start with the
[quickstart](../user-guide/quickstart-v2.md) for a local stdio server.
The [1.x configuration reference](configuration.md) is historical.

## Project boundary

| Setting | Default | Meaning |
|---------|---------|---------|
| `buildtools.projects.allowed-roots` | empty | Comma-separated existing directories the server may access. With no roots, project access is denied. |
| Tool argument `projectDir` | required by path-bearing tools | `.` selects the first allowed root; relative children resolve below it. Existing absolute paths are accepted only when they resolve inside an allowed root. |

Set roots in the server's **local launch configuration**, not in a prompt.
For example, `-Dbuildtools.projects.allowed-roots=/workspace/projects`
allows a client to pass `{"projectDir":"service-a"}` without showing an
absolute host path to the model. Canonical path checks restrict the server's
file access, but a build script still runs with the server process's operating
system permissions. Isolate untrusted projects in a container.

## Transports and access

| Setting | Default | Meaning |
|---------|---------|---------|
| `spring.profiles.active` | default/stdio | `http` enables stateless Streamable HTTP at `/mcp` and disables stdio. |
| `server.address` | `127.0.0.1` in the HTTP profile | Listen address. A wider bind requires roots, configured credentials, enforced bearer authentication, and restricted CORS at startup. |
| `server.port` | `8080` | HTTP listen port. |
| `BUILDTOOLS_API_KEY_<NAME>` | absent | A bearer credential loaded from the server environment. Set it from a local secret manager; never copy its value into a prompt or repository. |
| `BUILDTOOLS_API_KEY_<NAME>_SCOPES` | empty | Comma-separated scopes for that key. No scopes means no public tool call is authorized. Grant only the scopes needed; see the [tool catalog](tool-catalog.md). |
| `buildtools.oauth.resource-server.enabled` | `true` in the HTTP profile | Require a configured bearer key for MCP requests. `tools/call` also checks the key's tool scope and returns 403 when it is absent. |
| `mcp.transport.cors.allowed-origins` | local origins on port 8080 | Browser Origin allowlist for `/mcp`. Use explicit trusted origins; the server rejects an invalid Origin with 403. |
| `mcp.transport.allowed-hosts` | empty | Additional Host names accepted by the loopback Host guard, useful with a trusted local reverse proxy. |
| `mcp.transport.max-validation-body-bytes` | `1048576` | Maximum MCP POST body size accepted by header and auth inspection; oversized requests receive 413. |

The HTTP profile binds to loopback and enforces bearer authentication by
default. Set a key and scopes before starting it. For example, a read-only
client could have `build:read,dependency:read`; add `build:execute` only for
build execution. This scope permits local Maven `install`, Gradle
`publishToMavenLocal`, and sbt `publishLocal`, but rejects Maven `deploy` and
sbt `publish` so an ordinary build call cannot directly publish artifacts to a
remote repository. Build scripts and configured plugins can still perform
arbitrary side effects; use an isolated workspace for untrusted projects.
The `buildtools.auth.enabled` and `buildtools.auth.mode`
properties belong to the older in-process authorization service; HTTP
`tools/call` scope checks happen in the bearer filter independently of
those properties. Do not disable HTTP bearer enforcement for a shared server.

A CLI MCP client may omit `Origin`; a browser must send a trusted origin.
The loopback server also rejects non-local `Host` values unless explicitly
allowed. A TLS-terminating reverse proxy needs a matching CORS origin and,
when its Host header reaches the loopback listener, an allowed Host.

## Results and runtime limits

| Setting | Default | Meaning |
|---------|---------|---------|
| Process capture | 32 KiB head + 96 KiB tail per stream | Maven, Gradle, and sbt output is bounded before privacy projection. |
| Model-visible diagnostics | at most 12 | Structured, normalized, redacted diagnostics; raw logs and commands stay local. |

The supported SDK protocol revision is MCP `2025-11-25`. The
[architecture review](design-v2.md) explains the transport, privacy
boundary, and container workflow in more detail.
