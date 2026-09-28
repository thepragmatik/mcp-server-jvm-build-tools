# HTTP authentication and OAuth discovery

The HTTP profile binds to loopback and requires a configured, scoped **opaque API key** by default. Set `BUILDTOOLS_API_KEY_<NAME>` and `BUILDTOOLS_API_KEY_<NAME>_SCOPES` in the server environment, then send the key in `Authorization: Bearer` on each MCP request. A missing key receives `401` and a plain `WWW-Authenticate: Bearer` challenge. A key without the required tool scope receives `403`.

The stdio transport has no HTTP authorization exchange. Project allowlisted roots and model-visible output redaction apply on both transports. For the current configuration and diagrams, see [HTTP authentication 2.0](reference/http-authentication.md), [configuration 2.0](reference/configuration-v2.md), and the [current tool catalog](reference/tool-catalog.md).

## Optional protected-resource metadata

The OAuth metadata endpoint `GET /.well-known/oauth-protected-resource` returns **404** in default local-key mode. Configure at least one issuer with `buildtools.oauth.authorization-servers` while keeping `buildtools.oauth.resource-server.enabled=true` to enable RFC 9728 metadata containing `authorization_servers`. A 401 challenge then points to that metadata with `resource_metadata`. Metadata lists the server's public tool scopes, excluding `offline_access` and the internal wildcard.

Set `buildtools.oauth.resource` to the canonical **external** resource URL when a reverse proxy terminates TLS. The configured URL supplies the public origin of the metadata link; the proxy must route `/.well-known/oauth-protected-resource` at that origin to this server. The resource URL and issuer URLs must be absolute HTTPS URLs, except loopback HTTP for local development. URLs with user information, query strings, or fragments are rejected at startup. Untrusted forwarding headers do not override the configured public URL.

Issuer configuration enables discovery only; **the built-in filter does not validate OAuth-issued JWTs or remote opaque tokens**. It checks locally configured key digests and tool scopes. For an OAuth deployment, a trusted gateway must validate the issuer, signature or introspection result, audience, expiry, and scopes; prevent direct bypass; and map verified requests to locally recognized credentials. A JWT forwarded unchanged to this server receives 401. Test that complete path before advertising an issuer.

## Security boundary

- Keep keys in a secret manager or local environment. Never place them in a repository, prompt, URL, or log. Rotate a leaked key and restart the server; keys have no built-in expiry.
- Grant only the tool scopes needed. `build:execute` can run project build scripts with the server process's permissions; isolate untrusted projects and withhold publishing credentials.
- Use TLS and a trusted gateway for network exposure. The server's default loopback bind is deliberately narrow; changing it requires explicit protections.
- Treat the configured resource and authorization server URLs as deployment configuration, not model or request input.

The [MCP 2025-11-25 authorization specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization) requires `authorization_servers` and audience-bound token validation when claiming an OAuth protected-resource flow. This server's default local API-key mode intentionally does not claim that flow. Issuer metadata is an integration point, not a claim of complete OAuth conformance.
