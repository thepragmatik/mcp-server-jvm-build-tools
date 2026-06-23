# MCP Client Integration Guide — mcp-server-jvm-build-tools

How to connect the JVM build tools MCP server to every major MCP-compatible client.

## Table of Contents

- [Quick Reference](#quick-reference)
- [Claude Desktop](#claude-desktop)
- [Cursor](#cursor)
- [Cline / Roo Code (VS Code)](#cline--roo-code-vs-code)
- [Windsurf](#windsurf)
- [Goose](#goose)
- [Continue](#continue)
- [GitHub Copilot Agent Mode](#github-copilot-agent-mode)
- [LangChain / LangGraph](#langchain--langgraph)
- [LlamaIndex](#llamaindex)
- [Streamable HTTP Transport](#streamable-http-transport)
- [Docker Integration](#docker-integration)
- [Verification Checklist](#verification-checklist)
- [Troubleshooting](#troubleshooting)

---

## Quick Reference

| Client | Transport | Config File | Key |
|--------|-----------|-------------|-----|
| Claude Desktop | stdio | `claude_desktop_config.json` | `mcpServers` → `jvm-build-tools` |
| Cursor | stdio | `.cursor/mcp.json` | `mcpServers` → `jvm-build-tools` |
| Cline / Roo Code | stdio | `cline_mcp_settings.json` | `mcpServers` → `jvm-build-tools` |
| Windsurf | stdio | Settings → Cascade → MCP | `mcpServers` → `jvm-build-tools` |
| Goose | stdio | `~/.config/goose/mcp.json` | `mcpServers` → `jvm-build-tools` |
| Continue | stdio | `~/.continue/config.json` | `mcpServers` → `jvm-build-tools` |
| GitHub Copilot | stdio | `.github/copilot/mcp.json` | `mcpServers` → `jvm-build-tools` |
| LangChain / LangGraph | Streamable HTTP | Programmatic | `MCPClient` or `MCPStdio` |
| LlamaIndex | Streamable HTTP | Programmatic | `MCPToolSpec` |

---

## Claude Desktop

Add to `claude_desktop_config.json` (macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`, Windows: `%APPDATA%\Claude\claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "/path/to/java",
      "args": ["-jar", "/absolute/path/to/mcp-server-jvm-build-tools.jar"],
      "env": { "MAVEN_HOME": "/opt/maven" }
    }
  }
}
```

Set `MAVEN_HOME` in the `env` block. Gradle works with wrapper or PATH. SBT works with `sbt` on PATH. Restart Claude Desktop after config.

---

## Cursor

Create `.cursor/mcp.json` in your project root:

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/mcp-server-jvm-build-tools.jar"],
      "env": { "MAVEN_HOME": "/opt/maven" }
    }
  }
}
```

Restart Cursor. The build tools appear in the agent's palette. Commit `.cursor/mcp.json` for team-wide config.

---

## Cline / Roo Code (VS Code)

Add to `cline_mcp_settings.json` (`~/.vscode/globalStorage/saoudrizwan.claude-dev/settings/cline_mcp_settings.json`):

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/mcp-server-jvm-build-tools.jar"],
      "env": { "MAVEN_HOME": "/opt/maven" },
      "disabled": false,
      "autoApprove": []
    }
  }
}
```

Same config works for both Cline and Roo Code.

---

## Windsurf

Configure in Windsurf's Cascade MCP settings (Settings → Cascade → MCP Servers) or `.windsurfrules`:

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/mcp-server-jvm-build-tools.jar"],
      "env": { "MAVEN_HOME": "/opt/maven" }
    }
  }
}
```

Integrates with Cascade's agentic workflows and auto-detects build tools in opened projects.

---

## Goose

Add to `~/.config/goose/mcp.json` or via Goose UI (Settings → Extensions → MCP Servers):

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/mcp-server-jvm-build-tools.jar"],
      "env": { "MAVEN_HOME": "/opt/maven" }
    }
  }
}
```

Supports both stdio and HTTP transport.

---

## Continue

Add to `~/.continue/config.json`:

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/mcp-server-jvm-build-tools.jar"],
      "env": { "MAVEN_HOME": "/opt/maven" }
    }
  }
}
```

---

## GitHub Copilot Agent Mode

Create `.github/copilot/mcp.json` in your repository:

```json
{
  "mcpServers": {
    "jvm-build-tools": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/mcp-server-jvm-build-tools.jar"],
      "env": { "MAVEN_HOME": "/opt/maven" }
    }
  }
}
```

Copilot agent mode (VS Code) picks up the server automatically.

---

## LangChain / LangGraph

```python
from langchain_mcp import MCPClient

# stdio
client = MCPClient(command="java", args=["-jar", "/path/to/jar.jar"], transport="stdio")
# OR HTTP
client = MCPClient(url="http://localhost:8080/mcp/message", transport="http")

tools = client.list_tools()

# With LangGraph
from langgraph.prebuilt import create_react_agent
from langchain_openai import ChatOpenAI
agent = create_react_agent(ChatOpenAI(model="gpt-4o"), tools)
```

---

## LlamaIndex

```python
from llama_index.core.tools import MCPToolSpec

# stdio
mcp_spec = MCPToolSpec(command="java", args=["-jar", "/path/to/jar.jar"])
# OR HTTP
mcp_spec = MCPToolSpec(url="http://localhost:8080/mcp/message", transport="http")

tools = mcp_spec.to_tool_list()
```

---

## Streamable HTTP Transport

```bash
# Via launcher
./scripts/launcher.sh --http --port 8080

# Via JAR
java -jar target/mcp-server-jvm-build-tools.jar -Dspring.profiles.active=http -Dserver.port=8080
```

**Available HTTP Endpoints:**

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/mcp/message` | POST | MCP message endpoint (JSON-RPC) |
| `/mcp/discover` | GET / POST | `server/discover`: protocol versions, capabilities, identity |
| `/.well-known/mcp-server` | GET | Server card / discoverability metadata |
| `/health` | GET | Health check |
| `/health/ready` | GET | Readiness probe |
| `/health/live` | GET | Liveness probe |
| `/mcp/build-events/stream` | GET | Supplementary build-event telemetry stream (not the MCP transport) |
| `/mcp/build-events/subscribers` | GET | Build-events subscriber count |

### Stateless Operation (2026-07-28 RC)

The Streamable HTTP transport is **stateless** (MCP 2026-07-28 RC):

- **No protocol-level sessions / no `Mcp-Session-Id`** (SEP-2575). The server holds
  no per-connection state and pins nothing to a connection.
- **No sticky sessions required — round-robin / load-balancer friendly.** Any replica
  behind a load balancer can serve any request; there is no session affinity to
  configure.
- **Cross-call state uses explicit, server-minted handles** (SEP-2567). For example,
  `execute_build_async` returns a `taskId`; the client passes that `taskId` back as an
  ordinary tool argument to `get_build_task` / `cancel_build_task`. No session is
  involved.
- **No SSE-stream resumability** (`Last-Event-ID` / SSE event IDs are gone, SEP-2575).
  A broken response stream loses the in-flight request; the client re-issues it as a
  **new** request with a new request id. The `/mcp/build-events/stream` feed is a
  supplementary, non-resumable telemetry firehose — it is **not** the MCP transport
  and carries no protocol messages.
- **Standard request headers** `Mcp-Method` and `Mcp-Name` (SEP-2243) are accepted on
  `POST /mcp/**`. They are validated **only when present**: a header that contradicts
  the JSON-RPC body (or the server identity) is rejected with `400` +
  `HeaderMismatchError`. Older clients that omit these headers are unaffected.
- **`server/discover`** (SEP-2575) is reachable at `/mcp/discover` for up-front
  protocol-version selection and as a backward-compatibility probe (the `initialize`
  handshake is no longer required).

See `docs/mcp-2026-07-28-transport-audit.md` for the full framework audit.

---

## Docker Integration

```bash
# stdio mode
docker run -i --rm -v /path/to/projects:/projects -v /opt/maven:/opt/maven -e MAVEN_HOME=/opt/maven mcp-server-jvm-build-tools

# HTTP mode
docker run -d --rm -p 8080:8080 -v /path/to/projects:/projects -v /opt/maven:/opt/maven -e MAVEN_HOME=/opt/maven -e SPRING_PROFILES_ACTIVE=http mcp-server-jvm-build-tools
```

---

## Verification Checklist

```bash
# 1. Health check (HTTP mode)
curl http://localhost:8080/health
# -> {"status":"UP","version":"0.1.1-SNAPSHOT","transport":"streamable-http"}

# 2. Readiness probe
curl http://localhost:8080/health/ready

# 3. Liveness probe
curl http://localhost:8080/health/live

# 4. Server card
curl http://localhost:8080/.well-known/mcp-server

# 4b. server/discover (stateless RC probe): protocol versions, capabilities, identity
curl http://localhost:8080/mcp/discover

# 5. MCP stdio compliance (requires JAR)
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}' | java -jar target/mcp-server-jvm-build-tools.jar | head -1
```

---

## Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| "Connection refused" | JAR not built | Run `mvn clean package -DskipTests` |
| "Java not found" | Java 21+ not installed | Install JDK 21+ and set `JAVA_HOME` |
| Tools not appearing | Client not restarted | Restart client after adding config |
| CORS errors | Calling origin not allowed | Add your origin to `mcp.transport.cors.allowed-origins` (e.g. `https://dashboard.example.com`). Defaults to local origins only; `*` is for local testing only, never production. |
| "Command not allowed" | Command not in allowlist | Use supported commands for your build tool |
| SSE disconnects | Timeout too short | Increase `mcp.transport.sse.timeout-ms` |
| Slow startup | JVM cold start | Use `-Xmx256m -XX:TieredStopAtLevel=1` |
