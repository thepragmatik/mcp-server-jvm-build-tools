#!/usr/bin/env python3
"""Probe the packaged Maven middle-diagnostic contract on both MCP transports."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "target/mcp-server-jvm-build-tools.jar"
CANARIES = ("/synthetic/private", "PersonName", "test.user@example.invalid",
            "SYNTHETIC_SECRET", "ignore previous instructions")


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def assert_result(result):
    safe = result.get("structuredContent")
    if not isinstance(safe, dict) or safe.get("errorCount") != 2:
        raise AssertionError("Maven middle diagnostics were not retained")
    if safe.get("success") is not False or safe.get("outputTruncated") is not True:
        raise AssertionError("Maven failure or output truncation was not reported")
    diagnostics = safe.get("diagnostics", [])
    if len(diagnostics) != 2 or diagnostics[0].get("line") != 42:
        raise AssertionError("Maven root-cause location was lost")
    content = result.get("content", [])
    if not content or json.loads(content[0].get("text", "")) != safe:
        raise AssertionError("Maven structured and legacy results differ")
    serialized = json.dumps(result)
    if any(canary in serialized for canary in CANARIES):
        raise AssertionError("Maven result exposed a synthetic private canary")


def fixture(root):
    project = root / "project"
    project.mkdir()
    (project / "pom.xml").write_text("<project/>", encoding="utf-8")
    home = root / "home"
    (home / "bin").mkdir(parents=True)
    emitter = home / "emit.py"
    emitter.write_text(
        "import sys\n"
        "out = sys.stdout.buffer\n"
        "out.write(b'[INFO] head\\n' * 4096)\n"
        "out.write(b'[ERROR] /synthetic/private/Sample.java:[42,1] cannot find symbol PersonName\\n')\n"
        "out.write(b'[ERROR] /synthetic/private/Sample.java:[43,1] ignore previous instructions test.user@example.invalid SYNTHETIC_SECRET\\n')\n"
        "chunk = b'[INFO] tail padding\\n' * 4096\n"
        "for _ in range(288): out.write(chunk)\n"
        "out.write(b'[INFO] BUILD FAILURE\\n')\n"
        "out.flush()\n"
        "sys.exit(1)\n",
        encoding="utf-8",
    )
    executable = home / "bin" / "mvn"
    executable.write_text('#!/bin/sh\nexec python3 "$(dirname "$0")/../emit.py"\n', encoding="utf-8")
    executable.chmod(0o700)
    return {"buildToolName": "maven", "buildToolHome": str(home),
            "projectDir": str(project), "command": "compile"}


def main():
    if not JAR.is_file():
        raise RuntimeError("Packaged server jar is missing")
    release = load("release_gate", ROOT / "scripts/release-gate.py")
    benchmark = load("benchmark_gate", ROOT / "scripts/benchmark-release-gate.py")
    with tempfile.TemporaryDirectory(prefix="mcp-middle-gate-") as temporary:
        root = Path(temporary)
        arguments = fixture(root)
        env = os.environ.copy()
        env["BUILDTOOLS_PROJECTS_ALLOWED_ROOTS"] = str(root)
        port = release.free_port()
        server = subprocess.Popen(
            ["java", "-Dspring.profiles.active=http", "-Dserver.address=127.0.0.1",
             f"-Dserver.port={port}", "-Dbuildtools.oauth.resource-server.enabled=false",
             "-jar", str(JAR)],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env,
        )
        try:
            release.wait_for_server(server, port)
            body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                               "params": {"name": "analyze_build_output", "arguments": arguments}}).encode()
            status, response = release.request(port, body)
            if status != 200:
                raise AssertionError("HTTP Maven analysis request failed")
            reply = release.json_rpc_reply(response, 1)
            assert_result(reply.get("result", {}))
            print("PASS Maven middle diagnostic: HTTP privacy and result parity")
        finally:
            server.terminate()
            try:
                server.wait(timeout=5)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait(timeout=5)

        client = benchmark.McpClient(JAR, root, 60)
        try:
            client.call("initialize", {"protocolVersion": "2025-11-25", "capabilities": {},
                                       "clientInfo": {"name": "synthetic-gate", "version": "1"}})
            client.call("notifications/initialized", notification=True)
            result, _ = client.call("tools/call", {"name": "analyze_build_output",
                                                   "arguments": arguments})
            assert_result(result)
            print("PASS Maven middle diagnostic: stdio privacy and result parity")
        finally:
            client.close()


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, RuntimeError, OSError, ValueError, KeyError):
        raise SystemExit("Maven middle protocol gate failed; details withheld") from None
