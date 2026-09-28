#!/usr/bin/env python3
"""Probe packaged Gradle/sbt middle diagnostics on both MCP transports."""

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


def fixture(root, tool, case):
    final_partial = case == "final-partial"
    label = tool + "-" + case
    home = root / label
    (home / "bin").mkdir(parents=True)
    project = root / (label + "-project")
    project.mkdir()
    (project / ("build.gradle" if tool == "gradle" else "build.sbt")).write_text("")
    diagnostic = (
        "error: cannot find symbol /synthetic/private/Sample.java:42 "
        "test.user@example.invalid token=SYNTHETIC_SECRET"
        if tool == "gradle" else
        "[error] /synthetic/private/Sample.scala:42: cannot find symbol "
        "test.user@example.invalid token=SYNTHETIC_SECRET"
    )
    if case == "colored-kotlin":
        diagnostic = (
            "\\x1b[31me: unresolved reference: /synthetic/private/Sample.kt:42 "
            "test.user@example.invalid token=SYNTHETIC_SECRET\\x1b[0m"
        )
    emitter = home / "emit.py"
    if case == "test-summary":
        summary = (
            "1 test completed, 1 failed\\nBUILD FAILED in 1s\\n"
            if tool == "gradle" else
            "[info] Passed: Total 1, Failed 1, Errors 0, Passed 0\\n[error] Total time: 1 s\\n"
        )
        program = (
            "import sys\n"
            f"out = sys.{'stdout' if tool == 'gradle' else 'stderr'}.buffer\n"
            "out.write(b'[info] expected ready but was stale test.user@example.invalid SYNTHETIC_SECRET\\n')\n"
            f"out.write(b'{summary}')\n"
            "out.flush()\n"
            "sys.exit(1)\n"
        )
    elif final_partial:
        program = (
            "import sys\n"
            "out = sys.stderr.buffer\n"
            "other = sys.stdout.buffer\n"
            "chunk = b'x' * (1024 * 1024)\n"
            "for _ in range(14): out.write(chunk)\n"
            f"out.write(b'\\n{diagnostic}')\n"
            "out.flush()\n"
            "for _ in range(14): other.write(chunk)\n"
            "other.flush()\n"
            "sys.exit(1)\n"
        )
    else:
        program = (
            "import sys\n"
            f"out = sys.{'stdout' if tool == 'gradle' else 'stderr'}.buffer\n"
            "chunk = b'x' * (1024 * 1024)\n"
            "for _ in range(14): out.write(chunk)\n"
            f"out.write(b'\\n{diagnostic}\\n')\n"
            "for _ in range(14): out.write(chunk)\n"
            "out.flush()\n"
            "sys.exit(1)\n"
        )
    emitter.write_text(program, encoding="utf-8")
    executable = home / "bin" / tool
    executable.write_text('#!/bin/sh\nexec python3 "$(dirname "$0")/../emit.py"\n')
    executable.chmod(0o700)
    return {"buildToolName": tool, "buildToolHome": str(home),
            "projectDir": str(project), "command": "build" if tool == "gradle" else "compile"}


def assert_result(result, analysis):
    safe = result.get("structuredContent")
    if not isinstance(safe, dict):
        raise AssertionError("Missing structured result")
    if safe.get("success") is not False or safe.get("outputTruncated") is not True:
        raise AssertionError("Failed build status or truncation signal was lost")
    if not analysis and safe.get("exitCode") != 1:
        raise AssertionError("Completed process exit status was lost")
    if analysis and safe.get("errorCount") != 1:
        raise AssertionError("Middle compiler error was not parsed")
    diagnostics = safe.get("diagnostics", [])
    if not diagnostics or diagnostics[0].get("category") != "compilation":
        raise AssertionError("Middle root-cause diagnostic was lost")
    content = result.get("content", [])
    if not content or json.loads(content[0].get("text", "")) != safe:
        raise AssertionError("Structured and legacy text results differ")
    if any(canary in json.dumps(result) for canary in CANARIES):
        raise AssertionError("A synthetic private canary escaped the output policy")


def assert_summary_result(result):
    safe = result.get("structuredContent")
    if not isinstance(safe, dict) or safe.get("success") is not False:
        raise AssertionError("Failed test status was lost")
    if safe.get("testSummary", {}).get("failed") != 1 or safe.get("errorCount") != 1:
        raise AssertionError("Failed test summary lacks one generic diagnostic")
    diagnostics = safe.get("diagnostics", [])
    if len(diagnostics) != 1 or diagnostics[0].get("category") != "test":
        raise AssertionError("Generic failed-test category was lost")
    content = result.get("content", [])
    if not content or json.loads(content[0].get("text", "")) != safe:
        raise AssertionError("Failed-test structured and text results differ")
    if any(canary in json.dumps(result) for canary in CANARIES + ("ready", "stale")):
        raise AssertionError("Failed-test result exposed a synthetic private canary")


def main():
    if not JAR.is_file():
        raise RuntimeError("Packaged server jar is missing")
    release = load("release_gate", ROOT / "scripts/release-gate.py")
    benchmark = load("benchmark_gate", ROOT / "scripts/benchmark-release-gate.py")
    with tempfile.TemporaryDirectory(prefix="mcp-middle-gate-") as temporary:
        root = Path(temporary)
        fixtures = {
            (tool, case): fixture(root, tool, case)
            for tool in ("gradle", "sbt")
            for case in (("middle", "final-partial", "colored-kotlin")
                         if tool == "gradle" else ("middle", "final-partial"))
        }
        summary_fixtures = {tool: fixture(root, tool, "test-summary") for tool in ("gradle", "sbt")}
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
            for (tool, case), args in fixtures.items():
                for analysis, method in ((True, "analyze_build_output"),
                                         (False, "execute_build_command")):
                    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                                       "params": {"name": method, "arguments": args}}).encode()
                    status, response = release.request(port, body)
                    if status != 200:
                        raise AssertionError("HTTP build request failed")
                    assert_result(release.json_rpc_reply(response, 1).get("result", {}), analysis)
                print(f"PASS packaged HTTP {tool} {case}: analysis, execution, privacy and parity")
            for tool, args in summary_fixtures.items():
                body = json.dumps({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                                   "params": {"name": "analyze_build_output", "arguments": args}}).encode()
                status, response = release.request(port, body)
                if status != 200:
                    raise AssertionError("HTTP test-summary request failed")
                assert_summary_result(release.json_rpc_reply(response, 2).get("result", {}))
                print(f"PASS packaged HTTP {tool} test summary: privacy and parity")
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
            for (tool, case), args in fixtures.items():
                for analysis, method in ((True, "analyze_build_output"),
                                         (False, "execute_build_command")):
                    result, _ = client.call("tools/call", {"name": method, "arguments": args})
                    assert_result(result, analysis)
                print(f"PASS packaged stdio {tool} {case}: analysis, execution, privacy and parity")
            for tool, args in summary_fixtures.items():
                result, _ = client.call("tools/call", {"name": "analyze_build_output", "arguments": args})
                assert_summary_result(result)
                print(f"PASS packaged stdio {tool} test summary: privacy and parity")
        finally:
            client.close()


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, RuntimeError, OSError, ValueError, KeyError):
        raise SystemExit("Gradle/sbt middle protocol gate failed; details withheld") from None
