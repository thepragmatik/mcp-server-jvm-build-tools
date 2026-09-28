# Install the 2.0 server

Use Java 21 or later and an MCP client that can launch a local stdio process. The repository includes a Maven wrapper, so a separate Maven installation is not required to **build this server**. Running a Maven *project* requires an available Maven installation through `MAVEN_HOME`, the `maven.home` JVM property, or `mvn` on `PATH`. A project-local `mvnw` alone is not auto-selected. Gradle and sbt use a project wrapper when present or a tool on `PATH`.

## Build and verify

```sh
git clone https://github.com/thepragmatik/mcp-server-jvm-build-tools.git
cd mcp-server-jvm-build-tools
./mvnw -B verify --no-transfer-progress
```

The executable JAR is `target/mcp-server-jvm-build-tools.jar`. Use the JAR from the [2.0 release](https://github.com/thepragmatik/mcp-server-jvm-build-tools/releases) if you prefer a published artifact; check the release notes and match its version to these docs.

## Connect a local MCP client

Configure your client to launch Java with these arguments, substituting an **existing local project directory** and the absolute path to your JAR in the client's local settings:

```text
-Dbuildtools.projects.allowed-roots=/workspace/projects
-jar
/absolute/path/to/mcp-server-jvm-build-tools.jar
```

Set the executable to `java`. Keep the project root in the client's local server configuration. A relative `projectDir` such as `.` or `service-a` selects a project under that root without sending the absolute host path to the model. Ask the client to call `tools/list`, then `detect_build_tool` with `{"projectDir":"."}`. The [quickstart](quickstart-v2.md) shows the first tool flow and result shape.

The default stdio transport uses stdin/stdout and opens no listening port. For optional Streamable HTTP, configure a key and minimum [per-tool scopes](../reference/configuration-v2.md#transports-and-access); the HTTP profile listens on loopback at `/mcp` by default. Do not put the key in a prompt or repository.

Build scripts inherit the server's OS permissions and may reach the network. A configured root restricts accepted project paths; it is not a sandbox. Use the [container workflow](../reference/design-v2.md#container-isolation) for untrusted code.
