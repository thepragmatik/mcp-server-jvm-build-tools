# Quickstart for 2.0

Build with Java 21+ and `./mvnw -B verify --no-transfer-progress`.

## Stdio in three steps

1. Set `buildtools.projects.allowed-roots` to an existing directory in the server's local process configuration.
2. Launch the jar from an MCP client with stdin/stdout attached:

```sh
java -Dbuildtools.projects.allowed-roots=/workspace/projects -jar target/mcp-server-jvm-build-tools.jar
```

3. Call `detect_build_tool` with `{"projectDir":"."}`. A relative path such as `"service-a"` selects a child of the first allowed root. Absolute home paths stay out of the conversation.

For HTTP, set `BUILDTOOLS_API_KEY_LOCAL` from a secret store and `BUILDTOOLS_API_KEY_LOCAL_SCOPES=build:read,dependency:read`. Add `build:execute` only when builds are needed. Start the jar with `--spring.profiles.active=http`. It binds to `127.0.0.1:8080` and accepts MCP at `/mcp` with an `Authorization: Bearer` header. A wider bind requires project roots, a configured key, bearer enforcement, and restricted CORS.

```mermaid
flowchart LR
    A["🟣 Agent<br/>projectDir: ."] --> B["🔵 Local MCP server"]
    B --> C{"🟠 Allowed root?"}
    C -- yes --> D["🟢 Maven / Gradle / sbt"]
    C -- no --> X["🔴 Reject"]
    D --> E["🟡 Redact diagnostics"]
    E --> A
    classDef agent fill:#eee5ff,stroke:#7c3aed,color:#24114b
    classDef server fill:#dbeafe,stroke:#2563eb,color:#102a56
    classDef decision fill:#ffedd5,stroke:#ea580c,color:#512600
    classDef tool fill:#dcfce7,stroke:#16a34a,color:#073b1e
    classDef output fill:#fef9c3,stroke:#ca8a04,color:#443400
    classDef reject fill:#fee2e2,stroke:#dc2626,color:#520909
    class A agent
    class B server
    class C decision
    class D tool
    class E output
    class X reject
```

Results contain aggregate counts and up to 12 structured diagnostics, with errors before warnings. Each diagnostic has `severity`, `category`, an optional file type and positive line number, and a bounded message describing a known failure; raw paths, source text, commands, dependency identities, and logs are withheld. For example, a compiler error can return `{"severity":"error","category":"compilation","fileType":"java","line":42,"message":"Compiler cannot find a symbol; check local imports and declarations near the reported line."}`. Unknown text gets a generic local-inspection hint. Build scripts can run other programs and make network calls, so use the [container workflow](../reference/design-v2.md#container-isolation) for untrusted projects.
