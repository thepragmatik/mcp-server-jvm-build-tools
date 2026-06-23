# Authorization Model — MCP OAuth 2.1 resource-server alignment

This document is the **recorded decision** (ADR) for how this server's HTTP transport
relates to the MCP authorization spec, which profiles an MCP server as an **OAuth 2.1
resource server** (RFC9728 Protected Resource Metadata, RFC6750 `WWW-Authenticate`
challenges, access-token validation).

- Spec: <https://modelcontextprotocol.io/specification/draft/basic/authorization>
- RFC9728 (OAuth 2.0 Protected Resource Metadata)
- RFC6750 (Bearer Token Usage)
- RFC9207 (`iss` in authorization responses — a **client-side** validation obligation)

> Note: the **stdio** transport (the default) has no network surface — no HTTP, no
> port, no tokens. Everything below applies only to the **opt-in Streamable HTTP
> transport** (`--http` / the `http` Spring profile).

## Decision

The HTTP transport adopts the **server-side obligations of the OAuth 2.1
resource-server model additively and without breaking existing clients**, and keeps a
documented, deliberate divergence for token *issuance*:

1. **Serve RFC9728 Protected Resource Metadata** at
   `/.well-known/oauth-protected-resource` (always available under the HTTP profile).
   This is a new, additive discovery surface — OAuth-capable clients can discover the
   resource server; clients that do not speak OAuth simply never request it.
2. **Emit RFC6750 `WWW-Authenticate: Bearer resource_metadata="..."` challenges** and
   **validate access tokens** when bearer-token enforcement is enabled. Enforcement is
   **opt-in** (`buildtools.oauth.resource-server.enabled`, default `false`), so the
   default behaviour is byte-for-byte unchanged for existing clients.
3. **Tokens are opaque bearer credentials validated locally** against the configured
   credential store (`BUILDTOOLS_API_KEY_*`), i.e. RFC7662-style local introspection
   over RFC6750 bearer transport. **Full authorization-server integration** (JWT/JWKS
   verification, audience binding, `iss` checks, dynamic client registration) is
   **deliberately delegated to a fronting OAuth-aware gateway / reverse proxy** in the
   production deployment topology — see *Divergence & threat model* below. This is a
   legitimate, RFC9728-blessed topology: the resource server advertises its metadata
   and accepts validated bearer tokens; the gateway performs the heavyweight AS work.
4. `scopes_supported` is derived from the server's fine-grained `ToolPermission`
   scopes. Per the spec, **`offline_access` is never advertised** (nor is the internal
   `*` wildcard).
5. The unsafe **`dev-key-unsafe-do-not-use-in-production` default is guarded**: it is
   never created under a production profile (`prod`/`production`) or when
   `buildtools.auth.mode=enforcing` (complements #83).

## OAuth/OIDC mapping

| MCP / OAuth 2.1 requirement | Implementation in this server |
|---|---|
| Resource server advertises Protected Resource Metadata (RFC9728) | `GET /.well-known/oauth-protected-resource` (`OAuthProtectedResourceMetadataController`) |
| `WWW-Authenticate: Bearer resource_metadata="..."` on 401 (RFC6750/RFC9728) | `OAuthResourceServerFilter` (opt-in) |
| Resource server validates access tokens | Local validation against `BUILDTOOLS_API_KEY_*` (`ToolAuthorizationService#isAccessTokenValid`); full AS/JWT validation delegated to a fronting gateway |
| Bearer token transport | `Authorization: Bearer <token>` header (`bearer_methods_supported: ["header"]`) |
| Scopes | 12 fine-grained `ToolPermission` scopes (`build:read`, `dependency:read`, …) |
| `offline_access` NOT advertised | Excluded from `scopes_supported` by construction |
| Audit logging | `ToolAuditLogger` (OWASP MCP06) |
| `iss` validation (RFC9207) | **Client-side** obligation; out of scope for a resource server |

## Configuration

```properties
# Protected Resource Metadata is ALWAYS served at /.well-known/oauth-protected-resource
# under the HTTP profile (additive). Bearer-token ENFORCEMENT is opt-in:
buildtools.oauth.resource-server.enabled=false
# Canonical resource identifier; blank => derived per-request as <scheme>://<host>[:<port>]/mcp.
# Behind a reverse proxy set this to the external URL, e.g. https://mcp.example.com/mcp
buildtools.oauth.resource=
# Optional OAuth authorization-server issuer URLs (RFC9728 authorization_servers):
buildtools.oauth.authorization-servers=
```

Example metadata document:

```json
{
  "resource": "https://mcp.example.com/mcp",
  "authorization_servers": ["https://auth.example.com"],
  "scopes_supported": ["build:read", "build:execute", "dependency:read", "..."],
  "bearer_methods_supported": ["header"],
  "resource_name": "MCP Server - Build Tools for the JVM",
  "resource_documentation": "https://github.com/thepragmatik/mcp-server-jvm-build-tools/blob/main/docs/AUTHORIZATION.md"
}
```

Challenge on a missing/invalid token (enforcement enabled):

```
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer error="invalid_token", error_description="The access token is invalid or expired", resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource"
```

## Divergence & threat model (local opaque-token scheme)

For local / trusted deployments the server validates opaque bearer tokens locally
rather than integrating a full authorization server. This is a deliberate divergence
from the "validate JWTs minted by an external AS" reading of the spec. Its threat model:

- **In scope (mitigated):** unauthenticated access to `/mcp/**` (when enforcement is
  enabled), token presence/validity, scope-gated tool authorization, audit logging,
  and never shipping a usable default credential in production.
- **Out of scope (delegate to the deployment):**
  - **Token issuance, rotation, revocation, and expiry** — opaque keys are minted out
    of band; there is no built-in expiry. Rotate via `BUILDTOOLS_API_KEY_*` and a
    redeploy, or front the server with an OAuth gateway that issues short-lived tokens.
  - **JWT/JWKS verification, audience binding, `iss`/`aud` validation** — performed by
    the fronting gateway, not the server.
  - **Transport confidentiality** — terminate TLS at a reverse proxy; the server
    speaks plaintext HTTP by default.
  - **Replay protection / mTLS / sender-constrained tokens** — gateway concern.
- **Residual risk:** a leaked opaque key grants its scopes until rotated. Keep keys
  out of source control, scope them minimally (least privilege), prefer a fronting
  OAuth gateway for untrusted exposure, and enable enforcement
  (`buildtools.oauth.resource-server.enabled=true`) whenever the HTTP transport faces
  anything other than a trusted local caller.

## Recommended production topology

```
Untrusted client ──TLS──> Reverse proxy / OAuth gateway ──> this MCP server (resource server)
                          (validates AS-issued tokens,        (advertises RFC9728 metadata,
                           terminates TLS, rate-limits)         enforces bearer presence,
                                                                scope-gates tools)
```
