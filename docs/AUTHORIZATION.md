# Authorization Model — MCP 2026-07-28 Alignment

## OAuth/OIDC Mapping

The server uses API key-based authorization with fine-grained scopes,
mapping to the MCP 2026-07-28 authorization model:

| MCP Spec Requirement | Implementation |
|---------------------|---------------|
| OAuth Bearer token | BUILDTOOLS_API_KEY_* env vars |
| OIDC scopes | 12 fine-grained scopes (build:read, dependency:read, etc.) |
| Token validation | HMAC-SHA256 + SHA-256 token comparison |
| Audit logging | ToolAuditLogger (OWASP MCP06) |
| Permission model | Scope-based with wildcard (*) support |

## Why API Keys Instead of Full OAuth

The server is an MCP server for build tools — typically used
in CI/CD pipelines and developer machines. API keys provide:
- Simpler setup than full OAuth flows
- Fine-grained scoping (12 scopes vs binary access)
- Audit logging per invocation
- Compatible with OAuth Bearer token format

## Future OAuth Provider Support

When OAuth provider integration is added:
1. Add OAuth discovery endpoint (/.well-known/oauth-authorization-server)
2. Support token exchange for BUILDTOOLS_API_KEY_*
3. Add JWKS endpoint for key rotation
