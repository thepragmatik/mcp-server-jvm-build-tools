#!/usr/bin/env python3
"""Black-box release checks against the packaged, loopback-only MCP HTTP server.

All requests contain synthetic data. Server output and conformance transcripts are
deliberately withheld from CI logs because build-server responses may contain paths.
"""

import argparse
import http.client
import json
import os
import select
import socket
import subprocess
import sys
import time
from pathlib import Path


RUNNER = "@modelcontextprotocol/conformance@0.2.0-alpha.11"
PROTOCOL = "2025-11-25"
SCENARIOS = (
    "server-initialize",
    "ping",
    "tools-list",
    "resources-list",
    "prompts-list",
    "dns-rebinding-protection",
)
JAR = Path(__file__).resolve().parents[1] / "target/mcp-server-jvm-build-tools.jar"


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def wait_for_server(process, port):
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("Packaged server exited during startup")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                return
        except OSError:
            time.sleep(0.2)
    raise RuntimeError("Packaged server did not listen within 40 seconds")


def request(port, body, *, host=None, origin=None, bearer=None, path="/mcp"):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "MCP-Protocol-Version": PROTOCOL,
        "Host": host or f"127.0.0.1:{port}",
    }
    if origin is not None:
        headers["Origin"] = origin
    if bearer is not None:
        headers["Authorization"] = f"Bearer {bearer}"
    try:
        connection.request("POST", path, body=body, headers=headers)
        response = connection.getresponse()
        # Read the entire bounded error/result body. A prefix-only read can miss
        # a reflected canary that appears after a verbose JSON-RPC envelope.
        body = response.read(65_537)
        if len(body) > 65_536:
            raise AssertionError("MCP response exceeded the release-gate inspection cap")
        return response.status, body
    finally:
        connection.close()


def json_rpc_reply(body, identifier):
    """Decode JSON or a finite SSE response without printing its content."""
    if body.lstrip().startswith(b"{"):
        candidates = [json.loads(body)]
    else:
        candidates = [
            json.loads(line[5:].strip())
            for line in body.splitlines()
            if line.startswith(b"data:")
        ]
    return next((item for item in candidates if item.get("id") == identifier), None)


def assert_safe_error(body, identifier, canary, label):
    if len(body) > 4096 or canary.encode() in body:
        raise AssertionError(f"{label} exposed caller data or an oversized error")
    reply = json_rpc_reply(body, identifier)
    if not isinstance(reply, dict) or set(reply) != {"jsonrpc", "id", "error"}:
        raise AssertionError(f"{label} returned an unsafe JSON-RPC envelope")
    error = reply.get("error")
    allowed_messages = {"Parse error", "Invalid Request", "Method not found", "Invalid params", "Internal error", "Request failed"}
    if not isinstance(error, dict) or set(error) != {"code", "message"} or error.get("message") not in allowed_messages:
        raise AssertionError(f"{label} returned unbounded error fields")


def adversarial_checks(port):
    canary = "synthetic-private-canary.invalid"
    invalid = b'{"jsonrpc":"2.0","id":1,"method":"ping","params":{"x":"' + canary.encode() + b'"}'
    status, response = request(port, invalid)
    expected_error = {
        "jsonrpc": "2.0", "id": None,
        "error": {"code": -32700, "message": "Parse error"},
    }
    try:
        safe_error = json.loads(response) == expected_error
    except ValueError:
        safe_error = False
    if status != 400 or len(response) > 256 or not safe_error or canary.encode() in response:
        raise AssertionError(f"Malformed JSON returned unsafe error envelope: HTTP {status}")
    print("PASS malformed JSON: generic bounded JSON-RPC parse error")

    invalid_shapes = (
        {"jsonrpc": "2.0", "id": 1, "params": {}},
        {"jsonrpc": "2.0", "id": 1, "method": 7},
        {"jsonrpc": "2.0", "id": {"bad": 1}, "method": "ping"},
        {"jsonrpc": "2.0", "id": 1, "method": "ping", "params": None},
        {"jsonrpc": "2.0", "id": 1, "method": "ping", "params": "bad"},
        {"jsonrpc": "2.0", "id": 1, "method": "ping", "params": []},
        {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
         "params": {"name": "list_build_tools", "arguments": "bad"}},
        {"jsonrpc": "2.0", "id": 1, "method": "prompts/get", "params": "bad"},
    )
    expected_invalid = {
        "jsonrpc": "2.0", "id": None,
        "error": {"code": -32600, "message": "Invalid Request"},
    }
    for index, shape in enumerate(invalid_shapes):
        invalid_status, invalid_response = request(port, json.dumps(shape).encode())
        try:
            safe_invalid = json.loads(invalid_response) == expected_invalid
        except ValueError:
            safe_invalid = False
        if invalid_status != 400 or len(invalid_response) > 256 or not safe_invalid:
            raise AssertionError(f"Malformed request shape {index} exposed an unsafe response")
    print(f"PASS invalid JSON-RPC shapes: {len(invalid_shapes)} generic bounded errors")

    for path in ("/mcp", "/mcp/discover"):
        discover_status, discover_response = request(port, b"", path=path)
        if discover_status != 200 or b'"result"' not in discover_response:
            raise AssertionError(f"Empty discovery probe failed on {path}: HTTP {discover_status}")
    print("PASS empty discovery probes: both HTTP endpoints")

    unknown = (
        ("method", {"jsonrpc": "2.0", "id": 21, "method": canary}),
        ("tool", {"jsonrpc": "2.0", "id": 22, "method": "tools/call",
                  "params": {"name": canary, "arguments": {}}}),
        ("resource", {"jsonrpc": "2.0", "id": 23, "method": "resources/read",
                      "params": {"uri": "file:///synthetic-private-canary.invalid"}}),
        ("prompt", {"jsonrpc": "2.0", "id": 24, "method": "prompts/get",
                    "params": {"name": canary}}),
    )
    for label, message in unknown:
        _, error_response = request(port, json.dumps(message).encode())
        assert_safe_error(error_response, message["id"], canary, f"HTTP unknown {label}")
    print("PASS HTTP unknown identifiers: generic bounded errors")

    large = b"{" + b" " * 1_048_576 + b"}"
    status, _ = request(port, large)
    if status != 413:
        raise AssertionError(f"Oversized request returned HTTP {status}, expected 413")
    print("PASS oversized JSON-RPC request: HTTP 413")

    ping = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "ping"}).encode()
    status, _ = request(port, ping, host="evil.example.invalid", origin="http://evil.example.invalid")
    if status != 403:
        raise AssertionError(f"Rebound Host/Origin returned HTTP {status}, expected 403")
    print("PASS DNS rebinding: hostile Host/Origin rejected")


def conformance_checks(port):
    failures = []
    for scenario in SCENARIOS:
        command = [
            "npx", "--yes", RUNNER, "server", "--url", f"http://127.0.0.1:{port}/mcp",
            "--scenario", scenario, "--spec-version", PROTOCOL,
        ]
        try:
            result = subprocess.run(command, capture_output=True, timeout=90, check=False)
            passed = result.returncode == 0
        except subprocess.TimeoutExpired:
            passed = False
        print(f"{'PASS' if passed else 'FAIL'} official {scenario}")
        if not passed:
            failures.append(scenario)
    if failures:
        raise AssertionError(f"Official conformance failed: {', '.join(failures)}")


def scope_check():
    """An authenticated read-only caller must not reach the build executor."""
    port = free_port()
    token = "synthetic-release-gate-key"
    command = [
        "java", "-Dspring.profiles.active=http", "-Dserver.address=127.0.0.1",
        f"-Dserver.port={port}", f"-Dbuildtools.api.key.release={token}",
        "-Dbuildtools.api.key.release.scopes=build:read", "-jar", str(JAR),
    ]
    process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        wait_for_server(process, port)
        discover_status, discover_response = request(port, b"")
        if discover_status != 200 or b'"result"' not in discover_response:
            raise AssertionError(f"Unauthenticated empty discovery failed: HTTP {discover_status}")
        for label, body in (
            ("whitespace", b" "),
            ("ping", b'{"jsonrpc":"2.0","id":1,"method":"ping"}'),
        ):
            protected_status, _ = request(port, body)
            if protected_status != 401:
                raise AssertionError(f"Unauthenticated {label} reached MCP: HTTP {protected_status}")
        print("PASS empty discovery remains public; whitespace and RPC need authentication")
        call = json.dumps({
            "jsonrpc": "2.0", "id": 2, "method": "tools/call",
            "params": {"name": "execute_build_command", "arguments": {}},
        }).encode()
        status, _ = request(port, call, bearer=token)
        if status != 403:
            raise AssertionError(f"Read-only bearer reached executor: HTTP {status}")
        read_call = json.dumps({
            "jsonrpc": "2.0", "id": 3, "method": "tools/call",
            "params": {"name": "list_build_tools", "arguments": {}},
        }).encode()
        read_status, read_response = request(port, read_call, bearer=token)
        reply = json_rpc_reply(read_response, 3) if read_status == 200 else None
        result = reply.get("result") if reply else None
        if read_status != 200 or not isinstance(result, dict) or result.get("isError") is True:
            raise AssertionError(f"Read-only bearer could not call build:read tool: HTTP {read_status}")
        print("PASS read-only bearer: build execution denied and build read allowed")
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def stdio_check():
    """Check the packaged jar's second transport without exposing protocol payloads."""
    process = subprocess.Popen(
        ["java", "-jar", str(JAR)], stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0,
    )
    pending = bytearray()

    def exchange(message, identifier, *, expect_error=False):
        process.stdin.write(json.dumps(message).encode() + b"\n")
        process.stdin.flush()
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            while b"\n" in pending:
                line, _, rest = pending.partition(b"\n")
                pending[:] = rest
                try:
                    reply = json.loads(line)
                except ValueError:
                    raise AssertionError("Stdio emitted a non-JSON protocol line") from None
                if reply.get("id") == identifier:
                    if expect_error:
                        return reply
                    if "result" not in reply:
                        raise AssertionError(f"Stdio request {identifier} returned an error")
                    return reply["result"]
            ready, _, _ = select.select([process.stdout], [], [], max(0, deadline - time.monotonic()))
            if not ready:
                break
            chunk = os.read(process.stdout.fileno(), 65536)
            if not chunk:
                break
            pending.extend(chunk)
        raise AssertionError(f"Stdio request {identifier} timed out")

    try:
        initialized = exchange({
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"protocolVersion": PROTOCOL, "capabilities": {},
                       "clientInfo": {"name": "release-gate", "version": "1"}},
        }, 1)
        if initialized.get("protocolVersion") != PROTOCOL:
            raise AssertionError("Stdio negotiated an unexpected protocol version")
        process.stdin.write(b'{"jsonrpc":"2.0","method":"notifications/initialized"}\n')
        process.stdin.flush()
        exchange({"jsonrpc": "2.0", "id": 2, "method": "ping"}, 2)
        listed = exchange({"jsonrpc": "2.0", "id": 3, "method": "tools/list"}, 3)
        if not listed.get("tools"):
            raise AssertionError("Stdio tools/list returned an empty catalog")
        print("PASS packaged stdio: initialize, ping, tools/list")
        canary = "synthetic-private-canary.invalid"
        for label, message in (
            ("method", {"jsonrpc": "2.0", "id": 21, "method": canary}),
            ("tool", {"jsonrpc": "2.0", "id": 22, "method": "tools/call",
                      "params": {"name": canary, "arguments": {}}}),
            ("resource", {"jsonrpc": "2.0", "id": 23, "method": "resources/read",
                          "params": {"uri": "file:///synthetic-private-canary.invalid"}}),
            ("prompt", {"jsonrpc": "2.0", "id": 24, "method": "prompts/get",
                        "params": {"name": canary}}),
        ):
            reply = exchange(message, message["id"], expect_error=True)
            assert_safe_error(json.dumps(reply).encode(), message["id"], canary, f"stdio unknown {label}")
        print("PASS stdio unknown identifiers: generic bounded errors")
        invalid_shape = {"jsonrpc": "2.0", "id": 25, "method": "tools/call", "params": canary}
        reply = exchange(invalid_shape, 25, expect_error=True)
        assert_safe_error(json.dumps(reply).encode(), 25, canary, "stdio malformed shape")
        print("PASS stdio malformed shape: generic bounded error")
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-conformance", action="store_true", help="Run local adversarial checks only")
    args = parser.parse_args()
    if not JAR.is_file():
        parser.error("Build the packaged jar first with ./mvnw -B package -DskipTests")
    port = free_port()
    command = [
        "java", "-Dspring.profiles.active=http", "-Dserver.address=127.0.0.1",
        f"-Dserver.port={port}", "-Dbuildtools.oauth.resource-server.enabled=false", "-jar", str(JAR),
    ]
    process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        wait_for_server(process, port)
        adversarial_checks(port)
        if not args.skip_conformance:
            conformance_checks(port)
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
    scope_check()
    stdio_check()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (AssertionError, RuntimeError, OSError) as error:
        print(f"Release gate failed: {error}", file=sys.stderr)
        sys.exit(1)
