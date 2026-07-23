# MCP Test Tool Recommendations for JVM Build Tools Server

> **Context:** This document recommends open-source CLI-based MCP clients for testing the JVM Build Tools MCP server in Docker. The server exposes build tool automation via the Model Context Protocol (MCP) over stdio and/or HTTP transport.  
> **Date:** 2026-07-23  
> **Author:** RSRCH-2

---

## Table of Contents

1. [Evaluation Criteria](#1-evaluation-criteria)
2. [Tool Landscape Overview](#2-tool-landscape-overview)
3. [Primary Recommendation: MCP Inspector](#3-primary-recommendation-mcp-inspector)
4. [Secondary Recommendation: MCP Tools (f/mcptools)](#4-secondary-recommendation-mcp-tools-fmcptools)
5. [Honorable Mentions](#5-honorable-mentions)
6. [Comparison Matrix](#6-comparison-matrix)
7. [Testing Strategies for Docker](#7-testing-strategies-for-docker)
8. [Decision Summary](#8-decision-summary)

---

## 1. Evaluation Criteria

Each tool was evaluated against the following requirements for testing a JVM Build Tools MCP server running in Docker:

| Criterion | Weight | Rationale |
|-----------|--------|-----------|
| stdio transport support | Critical | Docker containers communicate via stdio or TCP; stdio is the most common transport for local MCP servers |
| HTTP/SSE transport support | High | Server may also expose streamable HTTP for remote access |
| CLI/headless mode | Critical | Must work in CI pipelines and scripted test suites |
| tools/list + tools/call | Critical | Must be able to discover and invoke build tool operations |
| Docker-compatible | High | Must run alongside or inside a Docker container without a browser |
| Programmable output | High | JSON output for piping into test assertions / jq |
| Active maintenance | Medium | Community health, recent commits, stars |
| Ease of installation | Medium | Zero-friction setup on macOS and in Docker |

## 2. Tool Landscape Overview

A survey of the open-source MCP CLI ecosystem identified these primary candidates:

| Tool | Language | Transport | CLI Mode | Stars (approx.) | Key Differentiator |
|------|----------|-----------|----------|-----------------|--------------------|
| **MCP Inspector** | Node.js | stdio, HTTP/SSE | Yes (--cli) | 2.1k+ | Official Anthropic/MCP tool |
| **MCP Tools (f/mcptools)** | Go | stdio, HTTP | Yes (native) | 1.5k+ | Fast binary, Swiss-Army-Knife |
| **mcp-tester (Rust)** | Rust | stdio, HTTP | Yes (native) | ~200 | Protocol compliance validation |
| **mcp-cli (wong2)** | Node.js | stdio | Yes | ~200 | Simple, minimal |
| **IBM/mcp-cli** | Node.js | stdio, MCP Apps | Yes (interactive) | ~400 | IBM enterprise-grade |

## 3. Primary Recommendation: MCP Inspector

**Repository:** [modelcontextprotocol/inspector](https://github.com/modelcontextprotocol/inspector)  
**Type:** Official MCP testing tool  
**Install:** `npx @modelcontextprotocol/inspector` (no permanent install needed)  
**License:** MIT

### Why It's the Primary Choice

The MCP Inspector is the **official testing and debugging tool** for MCP servers, maintained by the same team that develops the MCP specification. It ships with a dual-mode architecture:

1. **Web UI mode** — A React-based interactive dashboard for exploratory testing (like Postman for MCP)
2. **CLI mode** — Headless, scriptable interaction for CI and automation

### CLI Mode Usage

```bash
# Basic: connect to a stdio-based server and list tools
npx @modelcontextprotocol/inspector --cli node build/index.js --method tools/list

# Call a specific tool with arguments
npx @modelcontextprotocol/inspector --cli node build/index.js \
  --method tools/call \
  --tool-name compile \
  --tool-arg projectPath=/workspace/my-project

# Connect to an HTTP/SSE server
npx @modelcontextprotocol/inspector --cli \
  --transport sse \
  --url http://localhost:8080/mcp \
  --method tools/list

# Get JSON output for scripting
npx @modelcontextprotocol/inspector --cli \
  node build/index.js \
  --method tools/list \
  --json
```

### Docker Usage

```bash
# Run the Inspector as a Docker container connecting to a host-side MCP server
docker run --rm -it \
  -v /var/run/docker.sock:/var/run/docker.sock \
  ghcr.io/modelcontextprotocol/inspector \
  --cli docker exec -i jvm-build-tools-server node server.js --method tools/list

# Or connect via HTTP from another container on the same Docker network
docker run --rm -it \
  --network jvm-build-tools-net \
  ghcr.io/modelcontextprotocol/inspector \
  --cli --transport sse --url http://jvm-build-tools:8080/mcp --method tools/list
```

### Key Features

- **Dual transport support:** stdio, SSE, and streamable HTTP
- **Tool contract validation:** Automatically validates JSON-RPC request/response shapes
- **Resource browsing:** List and read MCP resources exposed by the server
- **Prompt testing:** Execute MCP prompts with custom arguments
- **Export configs:** Generate `mcp.json` snippets for client configuration
- **Docker image:** `ghcr.io/modelcontextprotocol/inspector` for containerized testing
- **Structured output:** Machine-readable JSON for test assertions

### Limitations

- Requires Node.js runtime (npx startup adds ~2s overhead)
- CLI mode is newer than the web UI; some edge cases are less polished
- The web UI requires a browser, which may not be available in all Docker setups

---

## 4. Secondary Recommendation: MCP Tools (f/mcptools)

**Repository:** [f/mcptools](https://github.com/f/mcptools)  
**Type:** Multipurpose MCP CLI  
**Install:** `brew tap f/mcptools && brew install mcp`  
**Binary name:** `mcp` (or `mcpt` to avoid conflicts)  
**License:** MIT

### Why It's the Secondary Choice

MCP Tools is a **Go-based, single-binary CLI** that goes beyond simple inspection to offer proxy, mock, scaffolding, and guard modes. It's faster than Node-based tools and equally capable for the core testing workflow.

### Usage

```bash
# List tools exposed by a stdio server
mcp tools -- node server.js

# List tools from an HTTP server
mcp tools -- http://localhost:8080/mcp

# Call a tool with arguments
mcp call compile projectPath=/workspace/my-project \
  -- node server.js

# Interactive shell mode
mcp shell -- node server.js

# Output as JSON (pipe to jq)
mcp tools --json -- node server.js | jq '.tools[].name'
```

### Docker Usage

```bash
# Run against a Docker-based MCP server via stdio
docker exec -i jvm-build-tools-server mcp tools -- node server.js

# Or from a sidecar container (install mcptools in the test container)
# In Dockerfile: RUN brew tap f/mcptools && brew install mcp
mcp tools -- http://jvm-build-tools:8080/mcp
```

### Additional Capabilities

- **Proxy mode:** Expose shell scripts as MCP tools (`mcp proxy start`)
- **Mock server:** Create mock MCP servers for testing clients (`mcp mock`)
- **Guard mode:** Restrict access to specific tools/resources
- **Scaffold new projects:** `mcp init` creates TypeScript MCP project templates
- **Format options:** JSON, pretty-print, table output
- **HTTP transport:** Supports streamable HTTP and legacy SSE

### Limitations

- Not the official tool (community-maintained)
- Requires separate install (Homebrew or Go toolchain)
- Smaller documentation ecosystem than the official Inspector

---

## 5. Honorable Mentions

### mcp-tester (Rust)

- **Site:** [lib.rs/crates/mcp-tester](https://lib.rs/crates/mcp-tester)
- **Use case:** Protocol compliance conformance testing
- Tests 5 domains: Core, Tools, Resources, Prompts, Tasks
- Pre-built binaries for macOS/Linux/Windows
- **Best for:** Formal validation in CI, not daily interactive debugging

### mcp-cli (wong2/mcp-cli)

- **Site:** [github.com/wong2/mcp-cli](https://github.com/wong2/mcp-cli)
- **Use case:** Simple, minimal CLI inspector
- Interactive and non-interactive modes
- **Best for:** Quick smoke tests; less active development than the leaders

### IBM/mcp-cli

- **Site:** [github.com/IBM/mcp-cli](https://github.com/IBM/mcp-cli)
- **Use case:** Enterprise MCP client with MCP App support
- Interactive shell, server management commands
- **Best for:** When the JVM Build Tools server eventually supports MCP Apps (HTML UIs)

---

## 6. Comparison Matrix

| Capability | MCP Inspector | MCP Tools (f/mcptools) | mcp-tester (Rust) | wong2/mcp-cli |
|------------|--------------|----------------------|-------------------|---------------|
| **stdio** | Yes | Yes | Yes | Yes |
| **HTTP/SSE** | Yes | Yes | Yes | No |
| **CLI only (no browser)** | Yes (--cli) | Yes (native) | Yes (native) | Yes |
| **JSON output** | Yes (--json) | Yes (--json) | Yes | Yes |
| **tools/list** | Yes | Yes | Yes | Yes |
| **tools/call** | Yes | Yes | Yes | Yes |
| **resources/list** | Yes | Yes | Yes | Yes |
| **prompts/list** | Yes | No | Yes | Yes |
| **Interactive shell** | No (Web UI only) | Yes (mcp shell) | No | Yes |
| **Protocol compliance tests** | Partial | No | **Full (5 domains)** | No |
| **Mock server** | No | **Yes** | No | No |
| **Proxy mode** | No | **Yes** | No | No |
| **Docker image** | **Yes (ghcr.io)** | No | No | No |
| **Install complexity** | Zero (npx) | Homebrew/go install | Binary download | npm install |
| **Runtime deps** | Node.js | None (Go binary) | None (Rust binary) | Node.js |
| **Stars** | ~2.1k | ~1.5k | ~200 | ~200 |
| **Maintainer** | Anthropic/MCP team | Community (f) | Community | Community |

## 7. Testing Strategies for Docker

### Strategy A: Inspector Sidecar (Recommended)

Run the MCP Inspector as a **sidecar container** alongside the JVM Build Tools server on the same Docker network.

```yaml
# docker-compose snippet
services:
  jvm-build-tools-server:
    build: .
    ports:
      - "8080:8080"
    networks:
      - test-net

  mcp-test-runner:
    image: ghcr.io/modelcontextprotocol/inspector:latest
    command: >
      --cli --transport sse
      --url http://jvm-build-tools-server:8080/mcp
      --method tools/list
    networks:
      - test-net
    depends_on:
      jvm-build-tools-server:
        condition: service_healthy

networks:
  test-net:
```

### Strategy B: Host-side Inspector + Docker stdio

Use MCP Tools (fast binary) on the host machine, connecting to the server via `docker exec`:

```bash
# Smoke test: list tools
mcp tools -- docker exec -i jvm-build-tools-server java -jar server.jar

# Integration test: call a build tool
mcp call --json compile projectPath=/workspace/test-project \
  -- docker exec -i jvm-build-tools-server java -jar server.jar
```

### Strategy C: CI Pipeline (GitHub Actions)

```yaml
- name: Start MCP server
  run: docker compose up -d jvm-build-tools-server

- name: Wait for server readiness
  run: |
    until curl -s http://localhost:8080/health | grep -q "ok"; do
      sleep 1
    done

- name: Test MCP tools
  run: |
    npx @modelcontextprotocol/inspector --cli \
      --transport sse --url http://localhost:8080/mcp \
      --method tools/list --json > /tmp/tools.json
    # Assert tools exist
    test $(jq '.tools | length' /tmp/tools.json) -gt 0
    echo "✅ Server exposes $(jq '.tools | length' /tmp/tools.json) tools"
```

## 8. Decision Summary

**Use both MCP Inspector and MCP Tools — they complement each other:**

| Phase | Tool | Why |
|-------|------|-----|
| **Development** (exploratory) | MCP Inspector (Web UI) | Interactive debugging, visualize tool responses, resource tree browsing |
| **Smoke testing** (scripted) | MCP Tools (f/mcptools) | Fast Go binary, JSON output, no startup delay |
| **CI pipeline** (automated) | MCP Inspector (--cli) | Official Docker image, zero install via npx, well-documented |
| **Protocol compliance** | mcp-tester (Rust) | Formal validation of 5 MCP protocol domains |

### Quick Start

```bash
# Option A — MCP Inspector (zero install, works immediately)
npx @modelcontextprotocol/inspector --cli node server.js --method tools/list

# Option B — MCP Tools (faster, more features)
brew tap f/mcptools && brew install mcp
mcp tools -- node server.js
```

Both tools support the core MCP operations (tools/list, tools/call, resources/list) needed to validate that the JVM Build Tools server's build, compile, test, and deploy tools are working correctly.
