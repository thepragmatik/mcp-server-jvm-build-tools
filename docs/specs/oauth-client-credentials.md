# OAuth 2.1 Client Credentials Grant — Design Spec

**Date:** 2026-07-26  
**Target Release:** v1.2.0  
**Status:** Draft  
**Priority:** P1 (HIGH value, MEDIUM effort)  
**Author:** Architecture profile (Phase 1 research), mcp-server-jvm-build-tools

---

## 1. Overview / Motivation

The server already implements the **OAuth 2.1 Resource Server** profile in its HTTP
transport:
- `OAuthResourceServerConfig` — configures resource server metadata and bearer enforcement.
- `OAuthResourceServerFilter` — validates `Authorization: Bearer ***` tokens on `/mcp/**`.
- `ToolAuthorizationService` — local opaque-token validation via `BUILDTOOLS_API_KEY_*`.
- `OAuthProtectedResourceMetadataController` — RFC9728 metadata endpoint.

**What's missing:** A **Client Credentials Grant** (RFC 6749 §4.4) endpoint so that
CI/CD systems, headless agents, and machine-to-machine callers can **obtain** tokens
rather than requiring them to be configured out-of-band as `BUILDTOOLS_API_KEY_*` env vars.

Without this, every MCP client that wants to talk to the server must:
1. Know about `BUILDTOOLS_API_KEY_*` env vars.
2. Coordinate key rotation through deployment (env var change + restart).
3. Share long-lived static keys — a security anti-pattern.

With a client credentials grant endpoint:
1. Services authenticate with `client_id` + `client_secret` (or a JWT assertion).
2. The server issues short-lived access tokens with scoped permissions.
3. Key rotation is a configuration change, not a deployment operation.

### Key Design Goals

1. **Support both `client_secret_basic` and `private_key_jwt`** (RFC 7523) token
   issuance, aligning with OAuth 2.1 best practices.
2. **Integrate with existing `ToolAuthorizationService`** — issued tokens flow through
   the existing resource-server filter chain without modification.
3. **Scope-aware token issuance** — clients request specific scopes; the server grants
   a subset based on the client's registered scopes.
4. **Configurable via static client registration** — `buildtools.oauth.clients.*`
   properties in `application.properties` (no database required for MVP).
5. **Production-grade** — support token expiry (`expires_in`), audience (`aud`),
   issuer (`iss`), and token type hints.
6. **No breaking changes** — existing `BUILDTOOLS_API_KEY_*` clients continue working
   unchanged.

---

## 2. Design Decisions

### Decision 1: JWT Bearer Assertion (RFC 7523) + client_secret

**Chosen: Support both `private_key_jwt` and `client_secret_basic`.**

| Method | Use Case | Complexity |
|--------|----------|------------|
| `client_secret_basic` | Simple CI/CD integrations; agent frameworks | Low |
| `private_key_jwt` (RFC 7523) | Production deployments with PKI; no shared secret | Medium |
| `client_secret_post` | Legacy clients | Low (omit — OAuth 2.1 discourages) |

**Rationale from Phase 1 research:** The competitive landscape includes `build-scout`
(multi-build-system, Java) and `gradle-mcp` (Python). Neither offers client credentials
grant. Adding this capability differentiates the project and addresses a real gap for
CI/CD integration. JWT assertions are preferred for production because they eliminate
shared secrets — the CI/CD system holds a private key and presents a signed assertion.

For MVP (`v1.2.0`), `client_secret_basic` is sufficient. `private_key_jwt` is added
in the same release as a configuration option, with the heavy lifting done by JJWT or
Nimbus JOSE+JWT (already on the classpath via Spring Security if present, or added
as a dependency).

### Decision 2: Integration with existing filter chain

**Chosen: Add an `OAuthTokenEndpointController` at `POST /oauth/token`, NOT in the MCP filter chain.**

The token endpoint is:
- **NOT** behind `OAuthResourceServerFilter` (it's the unauthenticated endpoint where
  credentials are presented).
- Served on a separate path (`/oauth/token`) outside `/mcp/**`.
- CORS-configured independently (allows credentials from the CI/CD provider's origin).

This keeps the MCP transport concerns separate from the token issuance concern, and
matches the common OAuth pattern where `/oauth/token` and `.well-known/oauth-protected-resource`
live alongside but outside the protected resource.

### Decision 3: Static client registration (no database)

**Chosen: Static configuration via `application.properties`.**

```properties
# Client 1: CI/CD pipeline (client_secret_basic)
buildtools.oauth.clients.0.client-id=ci-pipeline
buildtools.oauth.clients.0.client-secret={cipher}encrypted-secret
buildtools.oauth.clients.0.scopes=build:execute,credential:read
buildtools.oauth.clients.0.grant-types=client_credentials
buildtools.oauth.clients.0.token-ttl-seconds=3600

# Client 2: Agent platform (private_key_jwt)
buildtools.oauth.clients.1.client-id=agent-platform
buildtools.oauth.clients.1.jwk-set-uri=https://platform.example.com/.well-known/jwks.json
buildtools.oauth.clients.1.scopes=build:read,dependency:read,java:read
buildtools.oauth.clients.1.grant-types=client_credentials
buildtools.oauth.clients.1.token-ttl-seconds=900
```

No database dependency. This matches the existing pattern of `BUILDTOOLS_API_KEY_*`
environment variables. For production deployments with dynamic client registration,
an external OAuth authorization server (Keycloak, Okta, etc.) can be fronted by a
reverse proxy — this endpoint is for simpler deployments.

### Decision 4: Scope mapping for tool-level authorization

**Chosen: Issued scopes are the `ToolPermission` OAuth scope strings.**

`ToolPermission` already defines 12 fine-grained scopes (`build:read`, `build:execute`,
`dependency:read`, etc.). The token endpoint grants a subset of the client's registered
scopes based on the `scope` parameter in the token request. The token's `scope` claim
is then checked by `OAuthResourceServerFilter` → `ToolAuthorizationService` exactly
like the static API keys.

This means:
- No new permission model.
- No changes to `ToolAuthorizationService`.
- Existing scope-checking code `ToolPermission.isToolAuthorized()` works unchanged.

---

## 3. Token Model

### 3.1 Access Token (JWT format)

```json
{
  "iss": "https://mcp-build-tools.example.com",
  "sub": "ci-pipeline",
  "aud": ["https://mcp-build-tools.example.com/mcp"],
  "exp": 1722000000,
  "iat": 1721996400,
  "nbf": 1721996400,
  "jti": "unique-token-id-abc123",
  "scope": "build:execute credential:read",
  "client_id": "ci-pipeline",
  "token_type": "Bearer"
}
```

### 3.2 Token Response

```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsImtpZCI6ImtleS0xIn0...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "scope": "build:execute credential:read"
}
```

### 3.3 Error Response (RFC 6749 §5.2)

```json
{
  "error": "invalid_client",
  "error_description": "Client authentication failed",
  "error_uri": "https://docs.example.com/oauth-errors"
}
```

---

## 4. API / Tool Definitions

### 4.1 `POST /oauth/token` (New REST Endpoint)

Issue an access token via the Client Credentials grant.

**Request (client_secret_basic):**

```
POST /oauth/token
Authorization: Basic Y2ktcGlwZWxpbmU6c2VjcmV0...
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&scope=build%3Aexecute%20credential%3Aread
```

**Request (private_key_jwt):**

```
POST /oauth/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&
scope=build%3Aread%20dependency%3Aread&
client_assertion_type=urn%3Aietf%3Aparams%3Aoauth%3Aclient-assertion-type%3Ajwt-bearer&
client_assertion=eyJhbGciOiJSUzI1NiIsImtpZCI...
```

**Validation:**

| Field | Rule |
|-------|------|
| `grant_type` | MUST be `client_credentials` |
| `client_id` / `client_secret` | Validated against registered clients (basic auth or form params) |
| `scope` | Requested scopes MUST be subset of registered client scopes |
| `client_assertion` | (if jwt-bearer) Validated: signature, `iss`=client_id, `sub`=client_id, `aud`=server token endpoint, `exp` not expired |
| `scope` | Requested scopes MUST be subset of registered scopes |

**Returns:** `200 OK` with access token JSON, or `400`/`401` with OAuth error JSON.

### 4.2 `introspect_token` (New REST Endpoint — Optional)

Token introspection endpoint (RFC 7662) for gateways that need to validate tokens
without parsing JWT.

```
POST /oauth/introspect
Authorization: Basic <server-creds>
Content-Type: application/x-www-form-urlencoded

token=<access-token>
```

**Returns:** `200 OK` with token metadata:

```json
{
  "active": true,
  "scope": "build:execute credential:read",
  "client_id": "ci-pipeline",
  "sub": "ci-pipeline",
  "exp": 1722000000,
  "iat": 1721996400,
  "token_type": "Bearer"
}
```

### 4.3 Existing Tools — No Changes

The existing tools operate unchanged:
- `validate_access_token` — validates both static API keys and OAuth-issued tokens.
- `check_tool_authorization` — checks any token's scopes against tool permissions.
- `list_available_scopes` — same scopes list, no changes needed.

---

## 5. Token Validation Flow

```
Client                          Server
  │                                │
  │  POST /oauth/token             │
  │  (client_id + client_secret    │
  │   + grant_type + scope)        │
  │ ──────────────────────────────▶│
  │                                ├─ OAuthTokenEndpointController
  │                                │  validates client credentials
  │                                │  checks scope ⊆ registered
  │                                │  signs JWT with server key
  │                                │  stores jti in in-flight cache
  │  200 {access_token, ...}       │
  │ ◀──────────────────────────────│
  │                                │
  │  POST /mcp/tools/call          │
  │  (Authorization: Bearer <jwt>) │
  │ ──────────────────────────────▶│
  │                                ├─ OAuthResourceServerFilter
  │                                │  validates JWT signature
  │                                │  checks exp, iss, aud
  │                                │  checks jti not revoked
  │                                │  extracts scope claim
  │                                ├─ delegates to MCP SDK
  │                                │  (no scope enforcement here—
  │                                │   enforcement is opt-in ToolAuthZ)
  │  200 (tool result)             │
  │ ◀──────────────────────────────│
```

### Validation Rules (OAuthResourceServerFilterJwt — new filter)

The existing `OAuthResourceServerFilter` validates opaque tokens (API keys). A new
`OAuthResourceServerJwtFilter` (or an enhanced `OAuthResourceServerFilter`) handles
JWT tokens:

1. **Signature** — verified against the server's signing key (or the registered client's
   JWK Set URI for `private_key_jwt` assertions).
2. **`exp`** — token not expired (leeway: 30 seconds).
3. **`nbf`** — token not before current time (leeway: 30 seconds).
4. **`iss`** — matches the server's issuer URL.
5. **`aud`** — must include the server's resource identifier or be absent.
6. **`jti`** — not in the revocation list (TBD: in-memory set, wiped on restart;
   revocation for long-lived tokens is out of scope for MVP).
7. **`scope`** — extracted; available for `ToolAuthorizationService` checks.

The filter chain becomes:

```
@Order(1) OAuthResourceServerFilter        (opaque token: API keys)
@Order(2) OAuthResourceServerJwtFilter     (JWT: client credentials tokens, NEW)
@Order(3) McpHeaderValidationFilter        (existing)
```

Both filters set a request attribute `TOKEN_SCOPES` that `ToolAuthorizationService`
can read. If neither filter sets scopes, the request is unauthenticated (and may be
blocked if enforcement is enabled).

---

## 6. Configuration Properties

```properties
# ─── OAuth 2.1 Resource Server (existing) ────────────────────
buildtools.oauth.resource-server.enabled=false
buildtools.oauth.resource=
buildtools.oauth.authorization-servers=

# ─── OAuth 2.1 Client Credentials Grant (NEW) ────────────────
# Enable the /oauth/token endpoint
buildtools.oauth.token-endpoint.enabled=false

# Server's issuer URL (used in 'iss' claim of issued JWTs)
buildtools.oauth.token-endpoint.issuer=https://mcp-build-tools.example.com

# JWT signing key (for issued tokens). Use a key file or inline base64-encoded key.
# Format: PEM-encoded RSA private key (PKCS#8)
buildtools.oauth.token-endpoint.signing-key-file=config/jwt-signing-key.pem

# Token TTL (default: 3600 seconds = 1 hour)
buildtools.oauth.token-endpoint.default-ttl-seconds=3600

# ─── Static Client Registration (NEW) ─────────────────────────
# Format: buildtools.oauth.clients.<index>.client-id=...
#         buildtools.oauth.clients.<index>.client-secret=...
#         buildtools.oauth.clients.<index>.scopes=...
#         buildtools.oauth.clients.<index>.grant-types=...
#         buildtools.oauth.clients.<index>.token-ttl-seconds=...
#         buildtools.oauth.clients.<index>.jwk-set-uri=... (for private_key_jwt)
```

---

## 7. Error Handling

| Scenario | HTTP Status | Error Code | Behaviour |
|----------|-------------|------------|-----------|
| Missing `grant_type` | 400 | `invalid_request` | Error JSON |
| Unsupported `grant_type` | 400 | `unsupported_grant_type` | Error JSON |
| Invalid client credentials | 401 | `invalid_client` | Error JSON, no WWW-Authenticate |
| Requested scope exceeds registered scopes | 400 | `invalid_scope` | Error JSON with valid scopes |
| Unknown client_id | 401 | `invalid_client` | Error JSON |
| Expired/malformed JWT assertion | 401 | `invalid_grant` | Error JSON |
| `/oauth/token` disabled | 404 | — | Standard 404 |
| Token endpoint rate limit exceeded | 429 | — | Retry-After header |

---

## 8. Implementation Plan

### Files to Create

| File | Purpose |
|------|---------|
| `src/main/java/com/pragmatik/buildtools/oauth/OAuthTokenEndpointController.java` | `POST /oauth/token` REST controller |
| `src/main/java/com/pragmatik/buildtools/oauth/OAuthTokenService.java` | Token issuance logic (JWT signing, scope validation) |
| `src/main/java/com/pragmatik/buildtools/oauth/OAuthClientRegistration.java` | Client record: client-id, secret hash, scopes, grant types |
| `src/main/java/com/pragmatik/buildtools/oauth/OAuthClientRegistrationRepository.java` | Loads clients from `application.properties` |
| `src/main/java/com/pragmatik/buildtools/oauth/OAuthJwtFilter.java` | @Order(2) — JWT validation filter for `/mcp/**` |
| `src/main/java/com/pragmatik/buildtools/oauth/OAuthIntrospectController.java` | Optional RFC 7662 introspection endpoint |
| `src/main/java/com/pragmatik/buildtools/oauth/OAuthTokenConfig.java` | @Configuration — bean setup for Nimbus/JJWT |
| `src/test/java/com/pragmatik/buildtools/oauth/OAuthTokenEndpointTest.java` | Token issuance tests |
| `src/test/java/com/pragmatik/buildtools/oauth/OAuthJwtFilterTest.java` | JWT validation tests |

### Files to Modify

| File | Change |
|------|--------|
| `application.properties` | Add client credentials grant configuration properties |
| `TransportConfig.java` | Add CORS config for `/oauth/token` endpoint |
| `McpHeaderValidationFilter.java` | Update `@Order` to `@Order(3)` (JWT filter at 2) |
| `docs/AUTHORIZATION.md` | Document client credentials grant endpoint |
| `docs/reference/security.md` | Add token endpoint to security reference |

### Dependencies to Add

```xml
<dependency>
    <groupId>com.nimbusds</groupId>
    <artifactId>nimbus-jose-jwt</artifactId>
    <version>10.0.1</version>
</dependency>
```

Nimbus JOSE+JWT provides: JWT serialization, RSA/EC signing and verification, JWK Set
loading, and JWT claim validation. It is lightweight, well-maintained, and avoids
pulling Spring Security into the classpath just for token handling.

---

## 9. Test Scenarios

### Scenario 1: Valid client_credentials token issuance
```
POST /oauth/token
Authorization: Basic Y2ktcGlwZWxpbmU6c2VjcmV0...
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&scope=build%3Aexecute

Expect: 200 OK
Response: {access_token: "<JWT>", token_type: "Bearer",
           expires_in: 3600, scope: "build:execute"}
JWT decodes: iss=server-issuer, sub=ci-pipeline, aud=[server-mcp-url],
             scope="build:execute", exp in future
```

### Scenario 2: JWT-issued token validated by filter
```
Step 1: POST /oauth/token → gets JWT
Step 2: POST /mcp/tools/call
        Authorization: Bearer <jwt-from-step-1>
        Body: {method: "tools/call", params: {name: "execute_build_command", ...}}

Expect: 200 OK — request passes through OAuthResourceServerJwtFilter
        JWT signature verified, exp not expired, scope extracted
        Tool executes normally
```

### Scenario 3: Expired token rejected
```
POST /mcp/tools/call
Authorization: Bearer <expired-jwt>

Expect: 401 Unauthorized
        WWW-Authenticate: Bearer error="invalid_token",
                          error_description="The access token has expired"
```

### Scenario 4: Requested scope exceeds registered scope
```
POST /oauth/token
Authorization: Basic Y2ktcGlwZWxpbmU6c2VjcmV0...
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&scope=build%3Aexecute%20admin%3Awildcard

Expect: 400 Bad Request
Response: {error: "invalid_scope",
           error_description: "Requested scope 'admin:wildcard' is not allowed"}
```

### Scenario 5: Invalid client credentials
```
POST /oauth/token
Authorization: Basic <base64-of-"bad-client:wrong-secret">
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&scope=build%3Aread

Expect: 401 Unauthorized
Response: {error: "invalid_client",
           error_description: "Client authentication failed"}
```

### Scenario 6: Missing grant_type
```
POST /oauth/token
Authorization: Basic <valid-creds>
Content-Type: application/x-www-form-urlencoded

scope=build%3Aread

Expect: 400 Bad Request
Response: {error: "invalid_request",
           error_description: "Missing required parameter: grant_type"}
```

### Scenario 7: Client secret rotation
```
Step 1: Update buildtools.oauth.clients.0.client-secret in properties
Step 2: Restart server
Step 3: POST /oauth/token with old secret

Expect: 401 — old secret rejected

Step 4: POST /oauth/token with new secret

Expect: 200 — new secret accepted
```

### Scenario 8: OAuth-issued token works with existing tools
```
Step 1: POST /oauth/token (scope: dependency:read) → get JWT
Step 2: POST /mcp/tools/call
        Authorization: Bearer <jwt>
        Tool: check_dependency_version

Expect: Tool executes successfully (scope covers dependency:read)

Step 3: Same token used for execute_build_command

Expect: Tool executes (scope dependency:read doesn't include build:execute,
        but enforcement is opt-in — by default all tools work)
```

### Scenario 9: private_key_jwt assertion validation
```
POST /oauth/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&
client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&
client_assertion=<signed-jwt-assertion>&
scope=build%3Aread

Expect: 200 OK (if signature valid, iss=sub=client_id, aud matches, exp valid)
        401 invalid_grant (if any of the above fails)
```

### Scenario 10: Token introspection
```
POST /oauth/introspect
Authorization: Basic <server-creds>
Content-Type: application/x-www-form-urlencoded

token=<jwt-token>

Expect: 200 OK
Response: {active: true, scope: "build:execute",
           client_id: "ci-pipeline", exp: ..., ...}
```

### Scenario 11: Revoked token (jti checked)
```
Step 1: POST /oauth/token → get JWT with jti
Step 2: Server receives a revocation command or jti added to deny list
        (mechanism: TBD — placeholder for future admin endpoint)
Step 3: POST /mcp/tools/call with revoked token

Expect: 401 Unauthorized
        error: "invalid_token", description: "Token has been revoked"
```

---

## 10. Acceptance Criteria

- [ ] `POST /oauth/token` issues valid JWT access tokens for registered clients.
- [ ] Both `client_secret_basic` and `private_key_jwt` client authentication are
      supported.
- [ ] Requested scopes are validated against registered client scopes.
- [ ] Issued JWT tokens are accepted by a new or enhanced `OAuthResourceServerFilter`.
- [ ] Expired tokens are rejected with `401` and RFC6750 `WWW-Authenticate` challenge.
- [ ] Existing `BUILDTOOLS_API_KEY_*` clients continue to work without changes.
- [ ] Token endpoint is disabled by default (`buildtools.oauth.token-endpoint.enabled=false`).
- [ ] Token endpoint is NOT behind any MCP auth filter (no chicken-and-egg problem).
- [ ] Rate limiting is applied (configurable, default 100 req/min per client).
- [ ] All 11 test scenarios pass.
- [ ] Nimbus JOSE+JWT dependency added to pom.xml.
- [ ] Documentation in `docs/AUTHORIZATION.md` updated.

---

## 11. Security Considerations

| Concern | Mitigation |
|---------|------------|
| Token theft | Short TTL (default 1h), optional JWT binding, TLS required in production |
| Client secret exposure | Hash stored secrets (BCrypt); never log or return them |
| JWT key compromise | Key rotation procedure documented; `kid` header supports key rollover |
| Rate limiting | Guard against brute-force on token endpoint (configurable limit) |
| Scope escalation | Server grants intersection of requested and registered scopes only |
| JWK Set URI SSRF | Validate and cache JWK Set responses; limit to HTTPS URLs |
| Revocation gap | In-memory jti deny list; full revocation via admin endpoint deferred to v1.3.0 |

**Recommended production deployment:**

```
CI/CD system ──TLS──> OAuth Gateway / Reverse Proxy ──> This MCP Server
                         │                                    │
                         │ (validates tokens,                 │ (issues tokens,
                         │  terminates TLS,                   │  validates JWT,
                         │  rate-limits)                      │  scope-gates MCP
                         │                                    │   tools)
```

The `/oauth/token` endpoint can be fronted by an external authorization server
(Keycloak, Okta, Auth0) for production deployments that need dynamic client
registration, refresh tokens, or social login. This server's implementation serves
as the default for simpler deployments and CI/CD integration without external
dependencies.
