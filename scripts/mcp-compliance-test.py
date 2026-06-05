#!/usr/bin/env python3
"""MCP stdio compliance test — newline-delimited JSON transport (SDK 0.8.0)."""
import subprocess, json, os

JAR = os.path.expanduser("~/hermes-working/mcp-server-jvm-build-tools-enhancement/repo/target/mcp-server-jvm-build-tools.jar")

proc = subprocess.Popen(
    ["java", "-jar", JAR],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
)

def send(msg):
    line = json.dumps(msg) + "\n"
    proc.stdin.write(line)
    proc.stdin.flush()

def recv(timeout=5):
    import select
    ready, _, _ = select.select([proc.stdout], [], [], timeout)
    if not ready:
        return None
    line = proc.stdout.readline()
    if not line.strip():
        return None
    return json.loads(line)

results = []
try:
    send({"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}})
    r = recv()
    results.append(("initialize", bool(r and "result" in r)))

    send({"jsonrpc":"2.0","method":"notifications/initialized"})

    send({"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}})
    r = recv()
    tools = r.get("result",{}).get("tools",[]) if r else []
    results.append(("tools/list (%d tools)" % len(tools), len(tools) > 0))

    send({"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_build_tool_version","arguments":{"buildToolName":"maven"}}})
    r = recv()
    results.append(("get_build_tool_version", bool(r and "result" in r)))

    send({"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list_build_tools","arguments":{}}})
    r = recv()
    results.append(("list_build_tools", bool(r and "result" in r)))

    send({"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"detect_build_tool","arguments":{"projectDir":JAR.replace("/target/mcp-server-jvm-build-tools.jar","")}}})
    r = recv()
    results.append(("detect_build_tool", bool(r and "result" in r)))

    print("MCP Compliance Test:")
    for name, ok in results:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}")

    passed = all(ok for _, ok in results)
    print(f"\nVerdict: {'MCP STDIO COMPLIANT' if passed else 'NON-COMPLIANT'}")

finally:
    proc.terminate()
