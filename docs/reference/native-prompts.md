# Native MCP prompts

The 2.0 development line advertises the MCP `prompts` capability and exposes three static, server-authored workflows through `prompts/list` and `prompts/get`. They work over stdio and stateless HTTP. The list does not change at runtime, so `listChanged` is false. No project file, process output, URL, argument value, or local path is read when a native prompt is listed or retrieved.

| Native prompt | Use it for |
|---|---|
| `diagnose_build_failure` | Triage a Maven, Gradle, or sbt failure from redacted diagnostics. |
| `review_dependency_updates` | Assess one dependency update at a time. |
| `plan_test_strategy` | Choose narrow tests first, then expand based on evidence. |

Ask your MCP client to list prompts, select one, and retrieve it. The equivalent protocol calls are:

```json
{"jsonrpc":"2.0","id":1,"method":"prompts/list"}
{"jsonrpc":"2.0","id":2,"method":"prompts/get","params":{"name":"diagnose_build_failure"}}
```

These prompts take **no arguments**. Unknown names or supplied arguments receive a generic invalid-params error; input values are not echoed. On HTTP, both `prompts/list` and `prompts/get` require a configured bearer key with `prompt:read` (or `*`). For example, grant `BUILDTOOLS_API_KEY_LOCAL_SCOPES=prompt:read,build:read` in the server environment. Stdio uses the local process trust boundary rather than HTTP bearer scopes.

These new workflows are not one-to-one aliases for the existing `prompt_build_and_test`, `prompt_build_diagnosis`, and `prompt_dependency_audit` tools in the [tool catalog](tool-catalog.md). Those legacy workflows use `tools/call` and the tool permission map. Native prompts are discovered with `prompts/list`, not `tools/list`, and bypass the guarded tool callback because they are immutable text. A client may send that text to a model provider; select and inspect prompts before use, and do not add private information to the conversation.

The server advertises an empty native resources capability and returns an empty `resources/list`; it does not expose project contents. The `list_build_resources` and `list_dependency_resources` tools remain available as count-only tools.
