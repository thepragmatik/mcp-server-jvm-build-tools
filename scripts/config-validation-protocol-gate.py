#!/usr/bin/env python3
"""Check packaged Maven POM validation and private-safe diagnostics on both MCP transports."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "target/mcp-server-jvm-build-tools.jar"
CANARIES = ("/synthetic/private", "test.user@example.invalid", "SYNTHETIC_SECRET")


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fixtures(root):
    cases = {
        "missing-coordinate": (
            "<project><modelVersion>4.0.0</modelVersion>"
            "<groupId>test.user@example.invalid</groupId><version>1</version></project>",
            False, "Required POM artifactId is missing."),
        "malformed": (
            "<project><modelVersion>4.0.0</modelVersion><groupId>SYNTHETIC_SECRET"
            "</groupId><artifactId>a</artifactId><version>1</version><unexpected></project>",
            False, "POM XML is malformed; inspect pom.xml locally."),
        "external-entity": (
            '<!DOCTYPE project [<!ENTITY private SYSTEM "file:///synthetic/private">]>'
            "<project><modelVersion>4.0.0</modelVersion><groupId>&private;</groupId>"
            "<artifactId>a</artifactId><version>1</version></project>",
            False, "POM XML is malformed; inspect pom.xml locally."),
        "oversized": (
            "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>"
            "<artifactId>a</artifactId><version>1</version><!--"
            + "x" * 1_048_576 + "--></project>",
            False, "POM exceeds the local 1 MiB validation limit."),
        "inherited": (
            "<project><modelVersion>4.0.0</modelVersion><parent><groupId>g</groupId>"
            "<artifactId>parent</artifactId><version>1</version></parent>"
            "<artifactId>child</artifactId></project>",
            True, None),
    }
    paths = {}
    for name, (pom, valid, message) in cases.items():
        project = root / name
        project.mkdir()
        (project / "pom.xml").write_text(pom, encoding="utf-8")
        paths[name] = (project, valid, message)
    return paths


def assert_result(result, valid, message, local_root):
    content = result.get("content", [])
    if not content or not isinstance(content[0].get("text"), str):
        raise AssertionError("Validation result has no text content")
    safe = json.loads(content[0]["text"])
    if safe.get("valid") is not valid or safe.get("tool") != "maven":
        raise AssertionError("Validation status or tool was lost")
    if message is None:
        if safe.get("issueCount") != 0 or safe.get("diagnostics"):
            raise AssertionError("Valid inherited POM produced issues")
    else:
        diagnostics = safe.get("diagnostics", [])
        if safe.get("issueCount") != 1 or len(diagnostics) != 1:
            raise AssertionError("Validation issue was not projected")
        if diagnostics[0].get("category") != "configuration" or diagnostics[0].get("message") != message:
            raise AssertionError("Validation issue used an unexpected template")
    if "structuredContent" in result and result["structuredContent"] != safe:
        raise AssertionError("Validation text and structured result differ")
    serialized = json.dumps(result)
    if str(local_root) in serialized or any(canary in serialized for canary in CANARIES):
        raise AssertionError("A synthetic private path or canary escaped validation")


def main():
    if not JAR.is_file():
        raise RuntimeError("Packaged server jar is missing")
    release = load("release_gate", ROOT / "scripts/release-gate.py")
    benchmark = load("benchmark_gate", ROOT / "scripts/benchmark-release-gate.py")
    with tempfile.TemporaryDirectory(prefix="mcp-validation-gate-") as temporary:
        root = Path(temporary)
        cases = fixtures(root)
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
            for name, (project, valid, message) in cases.items():
                body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                                   "params": {"name": "validate_build_configuration",
                                              "arguments": {"projectDir": str(project)}}}).encode()
                status, response = release.request(port, body)
                if status != 200:
                    raise AssertionError("HTTP validation request failed")
                assert_result(release.json_rpc_reply(response, 1).get("result", {}), valid, message, root)
                print(f"PASS packaged HTTP validation {name}: status, template and privacy")
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
                                       "clientInfo": {"name": "synthetic-validation-gate", "version": "1"}})
            client.call("notifications/initialized", notification=True)
            for name, (project, valid, message) in cases.items():
                result, _ = client.call("tools/call", {"name": "validate_build_configuration",
                                                       "arguments": {"projectDir": str(project)}})
                assert_result(result, valid, message, root)
                print(f"PASS packaged stdio validation {name}: status, template and privacy")
        finally:
            client.close()


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, RuntimeError, OSError, ValueError, KeyError):
        raise SystemExit("Configuration validation protocol gate failed; details withheld") from None
