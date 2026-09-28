#!/usr/bin/env python3
"""Check packaged OSV scan privacy without making an outbound OSV request."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "target/mcp-server-jvm-build-tools.jar"
CANARIES = ("test.user@example.invalid", "SYNTHETIC_SECRET", "/synthetic/private")
SUMMARY = {"totalDeps": 1, "vulnerableDeps": 0, "criticalCount": 0, "highCount": 0}


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fixtures(root):
    cases = {}
    outside = root.parent / "outside"
    outside.mkdir()
    (outside / "pom.xml").write_text(
        "<project><dependencies><dependency><groupId>test.user@example.invalid</groupId>"
        "<artifactId>SYNTHETIC_SECRET</artifactId><version>1</version>"
        "</dependency></dependencies></project>", encoding="utf-8")
    project = root / "symlink"
    project.mkdir()
    (project / "pom.xml").symlink_to(outside / "pom.xml")
    cases["symlink"] = (project, None)

    project = root / "oversized"
    project.mkdir()
    (project / "pom.xml").write_bytes(b"x" * (1_048_576 + 1))
    cases["oversized"] = (project, None)

    project = root / "invalid-utf8"
    project.mkdir()
    (project / "pom.xml").write_bytes(b"\xfftest.user@example.invalid")
    cases["invalid-utf8"] = (project, None)

    project = root / "unsupported-coordinate"
    project.mkdir()
    (project / "pom.xml").write_text(
        "<project><dependencies><dependency>"
        "<groupId>test.user@example.invalid</groupId>"
        "<artifactId>SYNTHETIC_SECRET</artifactId><version>1</version>"
        "</dependency></dependencies></project>", encoding="utf-8")
    cases["unsupported-coordinate"] = (project, SUMMARY)
    return cases, outside


def assert_result(result, expected_summary, root, outside):
    content = result.get("content", [])
    if not content or not isinstance(content[0].get("text"), str):
        raise AssertionError("OSV scan result has no text content")
    text = content[0]["text"]
    if text == "Tool execution failed; details withheld by privacy policy":
        if expected_summary is not None or result.get("isError") is not True:
            raise AssertionError("Unexpected private tool failure")
        safe = None
    else:
        safe = json.loads(text)
    if expected_summary is None:
        if result.get("isError") is not True:
            raise AssertionError("Unsafe build file did not produce a private error")
        if safe is not None and (safe.get("completed") is not True
                                 or safe.get("isError") is not True):
            raise AssertionError("Unsafe build file did not produce a fixed private error")
        if safe is not None and any(key in safe for key in SUMMARY):
            raise AssertionError("Unsafe build file was scanned")
    else:
        if safe is None or safe.get("completed") is not True or result.get("isError") is True:
            raise AssertionError("Filtered coordinate did not return a scan summary")
        if {key: safe.get(key) for key in SUMMARY} != expected_summary:
            raise AssertionError("Filtered coordinate has unexpected aggregate counts")
    if "structuredContent" in result and result["structuredContent"] != safe:
        raise AssertionError("OSV scan text and structured result differ")
    serialized = json.dumps(result)
    if any(value in serialized for value in (*CANARIES, str(root), str(outside))):
        raise AssertionError("A synthetic private value escaped the OSV scan")


def main():
    if not JAR.is_file():
        raise RuntimeError("Packaged server jar is missing")
    release = load("release_gate", ROOT / "scripts/release-gate.py")
    benchmark = load("benchmark_gate", ROOT / "scripts/benchmark-release-gate.py")
    with tempfile.TemporaryDirectory(prefix="mcp-osv-privacy-gate-") as temporary:
        root = Path(temporary) / "allowed"
        root.mkdir()
        cases, outside = fixtures(root)
        env = os.environ.copy()
        env["BUILDTOOLS_PROJECTS_ALLOWED_ROOTS"] = str(root)
        port = release.free_port()
        server = subprocess.Popen(
            ["java", "-Dspring.profiles.active=http", "-Dserver.address=127.0.0.1",
             f"-Dserver.port={port}", "-Dbuildtools.oauth.resource-server.enabled=false",
             "-jar", str(JAR)],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env)
        try:
            release.wait_for_server(server, port)
            for name, (project, expected) in cases.items():
                body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                                   "params": {"name": "scan_dependency_cves",
                                              "arguments": {"projectDir": str(project)}}}).encode()
                status, response = release.request(port, body)
                if status != 200:
                    raise AssertionError("HTTP OSV scan request failed")
                assert_result(release.json_rpc_reply(response, 1).get("result", {}), expected, root, outside)
                print(f"PASS packaged HTTP OSV privacy {name}")
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
                                       "clientInfo": {"name": "synthetic-osv-privacy-gate", "version": "1"}})
            client.call("notifications/initialized", notification=True)
            for name, (project, expected) in cases.items():
                result, _ = client.call("tools/call", {"name": "scan_dependency_cves",
                                                       "arguments": {"projectDir": str(project)}})
                assert_result(result, expected, root, outside)
                print(f"PASS packaged stdio OSV privacy {name}")
        finally:
            client.close()


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, RuntimeError, OSError, ValueError, KeyError):
        raise SystemExit("OSV privacy protocol gate failed; details withheld") from None
