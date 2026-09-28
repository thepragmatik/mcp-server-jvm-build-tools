# Configure the 2.0 server

For a local stdio client, choose one existing project directory and put
`-Dbuildtools.projects.allowed-roots=/workspace/projects` in the **server
process arguments**. Then call a path-bearing tool with `projectDir: "."`
or a relative child such as `"service-a"`. Keep absolute host paths in
local configuration rather than prompts or tool arguments sent to a model.

For Streamable HTTP, set `BUILDTOOLS_API_KEY_LOCAL` from a secret manager
and `BUILDTOOLS_API_KEY_LOCAL_SCOPES` to the minimum scopes your client
needs. Start the server with `--spring.profiles.active=http`. It listens
on `127.0.0.1:8080` by default and requires a bearer key at `/mcp`.
The HTTP filter checks a tool's scope on each `tools/call` request.
A browser must use a trusted `Origin`; a CLI client may omit it.

For example, `build:read` permits build-tool detection, while
`build:execute` permits build execution. The
[current tool catalog](../reference/tool-catalog.md) lists each tool's
required scope. The [configuration reference](../reference/configuration-v2.md)
lists defaults and HTTP controls. Start with the
[quickstart](quickstart-v2.md) for a runnable example.

A configured root restricts which project paths the server accepts. It
does not sandbox build scripts. Use the
[container workflow](../reference/design-v2.md#container-isolation) for
untrusted projects, and keep keys and build logs outside prompts and commits.
