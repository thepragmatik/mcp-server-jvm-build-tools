#!/usr/bin/env python3
"""Measure the packaged MCP server with disposable, dependency-free JVM projects.

This is a measurement harness, not a pass/fail microbenchmark. It prints aggregate
numbers only; protocol messages, build logs, and temporary project paths stay local.
"""

import argparse
import hashlib
import json
import math
import os
import platform
import select
import shlex
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path


FIXTURES = {
    "maven": {
        "pom.xml": """<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>benchmark.synthetic</groupId><artifactId>tiny</artifactId><version>1.0.0</version></project>\n""",
        "src/main/java/example/Tiny.java": "package example; public final class Tiny { public static final int REVISION = 0; private Tiny() {} }\n",
    },
    "gradle": {
        "settings.gradle": "rootProject.name = 'tiny'\n",
        "build.gradle": "plugins { id 'java' }\n",
        "src/main/java/example/Tiny.java": "package example; public final class Tiny { public static final int REVISION = 0; private Tiny() {} }\n",
    },
    "sbt": {
        "build.sbt": "name := \"tiny\"\nversion := \"1.0.0\"\nscalaVersion := \"2.13.16\"\n",
        "project/build.properties": "sbt.version=2.0.9\n",
        "src/main/java/example/Tiny.java": "package example; public final class Tiny { public static final int REVISION = 0; private Tiny() {} }\n",
    },
}


def quantile(values, fraction):
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(len(ordered) * fraction) - 1)], 2)


def descendants_rss_kib(root_pid):
    try:
        rows = subprocess.check_output(
            ["ps", "-e", "-o", "pid=,ppid=,rss="], text=True, stderr=subprocess.DEVNULL
        )
    except (OSError, subprocess.CalledProcessError):
        return None
    children = {}
    sizes = {}
    for row in rows.splitlines():
        fields = row.split()
        if len(fields) != 3:
            continue
        pid, parent, rss = map(int, fields)
        children.setdefault(parent, []).append(pid)
        sizes[pid] = rss
    pending = [root_pid]
    seen = set()
    while pending:
        pid = pending.pop()
        if pid not in seen:
            seen.add(pid)
            pending.extend(children.get(pid, ()))
    return sum(sizes.get(pid, 0) for pid in seen)


class RssSampler:
    def __init__(self, pid):
        self.pid = pid
        self.peak_kib = 0
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self.sample, daemon=True)

    def sample(self):
        while not self.stop_event.is_set():
            amount = descendants_rss_kib(self.pid)
            if amount is not None:
                self.peak_kib = max(self.peak_kib, amount)
            self.stop_event.wait(0.1)

    def __enter__(self):
        self.peak_kib = descendants_rss_kib(self.pid) or 0
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.stop_event.set()
        self.thread.join(timeout=2)


class McpClient:
    def __init__(self, jar, project_root, timeout):
        env = os.environ.copy()
        env["BUILDTOOLS_PROJECTS_ALLOWED_ROOTS"] = str(project_root)
        self.timeout = timeout
        self.next_id = 0
        self.process = subprocess.Popen(
            ["java", "-jar", str(jar)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            env=env,
            start_new_session=True,
        )

    def call(self, method, params=None, notification=False):
        self.next_id += 1
        request = {"jsonrpc": "2.0", "method": method}
        if not notification:
            request["id"] = self.next_id
        if params is not None:
            request["params"] = params
        self.process.stdin.write(json.dumps(request, separators=(",", ":")).encode() + b"\n")
        self.process.stdin.flush()
        if notification:
            return None, 0
        deadline = time.monotonic() + self.timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            ready, _, _ = select.select([self.process.stdout], [], [], min(0.5, remaining))
            if not ready:
                continue
            line = self.process.stdout.readline()
            if not line:
                raise RuntimeError("MCP server closed stdout")
            response = json.loads(line)
            if response.get("id") == self.next_id:
                if "error" in response:
                    raise RuntimeError("MCP request failed; response withheld")
                return response.get("result", {}), len(line)
        raise TimeoutError("MCP request timed out; response withheld")

    def close(self):
        # The build executable can outlive its MCP parent after an aborted run.
        if self.process.poll() is not None:
            return
        os.killpg(self.process.pid, 15)
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(self.process.pid, 9)
            self.process.wait(timeout=5)


def fixture(root, tool):
    directory = root / tool
    for name, content in FIXTURES[tool].items():
        path = directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    if tool == "sbt":
        # sbt 2 otherwise selects its native client, which cannot run in some
        # minimal libc containers; JVM batch mode is also easier to compare.
        wrapper = directory / "sbt"
        wrapper.write_text(
            "#!/bin/sh\nexec " + shlex.quote(shutil.which("sbt")) + " --server \"$@\"\n",
            encoding="utf-8",
        )
        wrapper.chmod(0o700)
    return directory


def output_fixture(root, count):
    """A synthetic Gradle wrapper emits one long line; no real tool is invoked."""
    directory = root / ("output-" + str(count))
    directory.mkdir()
    (directory / "build.gradle").write_text("plugins { id 'java' }\n", encoding="utf-8")
    emitter = directory / "emit.py"
    emitter.write_text(
        "import sys\nfrom pathlib import Path\n"
        "remaining = " + str(count) + "\nwritten = 0\nchunk = b'A' * 65536\n"
        "while remaining:\n"
        "    size = sys.stdout.buffer.write(chunk[:min(len(chunk), remaining)])\n"
        "    if size is None or size <= 0:\n"
        "        raise RuntimeError('output write failed')\n"
        "    written += size\n    remaining -= size\n"
        "sys.stdout.buffer.flush()\n"
        "Path('emitted.bytes').write_text(str(written), encoding='ascii')\n",
        encoding="utf-8",
    )
    wrapper = directory / "gradlew"
    wrapper.write_text(
        "#!/bin/sh\nexec " + shlex.quote(sys.executable) + " emit.py\n",
        encoding="utf-8",
    )
    wrapper.chmod(0o700)
    return directory


def force_compile(project, revision):
    source = project / "src/main/java/example/Tiny.java"
    source.write_text(
        "package example; public final class Tiny { public static final int REVISION = "
        + str(revision) + "; private Tiny() {} }\n",
        encoding="utf-8",
    )
    # Removing only generated classes preserves warm dependency and tool caches.
    for compiled in project.rglob("Tiny.class"):
        compiled.unlink()


def compiled_fingerprint(project, previous):
    classes = sorted(project.rglob("Tiny.class"))
    if not classes:
        raise RuntimeError("Build did not compile the synthetic Java source")
    fingerprint = hashlib.sha256(b"".join(path.read_bytes() for path in classes)).digest()
    if fingerprint == previous:
        raise RuntimeError("Build reused an unchanged class file")
    return fingerprint


def verify_emitted(marker, expected):
    try:
        amount = int(marker.read_text(encoding="ascii"))
    except (OSError, ValueError):
        raise RuntimeError("Synthetic output byte counter is absent or invalid") from None
    if amount != expected:
        raise RuntimeError("Synthetic output byte counter did not match fixture")


def has_raw_output(value):
    if isinstance(value, dict):
        return any(key in ("rawOutput", "command") or has_raw_output(item)
                   for key, item in value.items())
    if isinstance(value, list):
        return any(has_raw_output(item) for item in value)
    return False


def timed_call(client, method, params):
    start = time.perf_counter()
    result, response_bytes = client.call(method, params)
    return result, round((time.perf_counter() - start) * 1000, 2), response_bytes


def summarize(samples):
    return {
        "latency_ms_p50": quantile([item[0] for item in samples], 0.5),
        "latency_ms_p95": quantile([item[0] for item in samples], 0.95),
        "peak_process_tree_rss_mib_p95": quantile([item[1] / 1024 for item in samples], 0.95),
        "response_bytes_max": max(item[2] for item in samples),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=Path("target/mcp-server-jvm-build-tools.jar"))
    parser.add_argument("--tools", default="maven,gradle,sbt", help="comma-separated fixture tools")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--warmups", type=int, default=1)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--output", type=Path, help="write aggregate JSON to this file")
    parser.add_argument("--cache-state", choices=("cold", "warm", "unspecified"),
                        default="unspecified")
    parser.add_argument("--network-mode", choices=("none", "bridge", "host", "unspecified"),
                        default="unspecified")
    args = parser.parse_args()
    tools = args.tools.split(",")
    if not args.jar.is_file() or not tools or any(tool not in FIXTURES for tool in tools):
        parser.error("provide a packaged jar and a comma-separated subset of maven,gradle,sbt")
    if args.runs < 1 or args.warmups < 0 or args.timeout < 1:
        parser.error("runs and timeout must be positive; warmups must be nonnegative")
    missing = [tool for tool in tools if shutil.which("mvn" if tool == "maven" else tool) is None]
    if missing:
        parser.error("required build executables unavailable: " + ", ".join(missing))

    with tempfile.TemporaryDirectory(prefix="mcp-bench-") as temp:
        root = Path(temp)
        projects = {tool: fixture(root, tool) for tool in tools}
        projects["output-1m"] = output_fixture(root, 1024 * 1024)
        projects["output-24m"] = output_fixture(root, 24 * 1024 * 1024)
        client = McpClient(args.jar.resolve(), root, args.timeout)
        try:
            initialized, _ = client.call("initialize", {
                "protocolVersion": "2025-11-25", "capabilities": {},
                "clientInfo": {"name": "synthetic-benchmark", "version": "1.0"},
            })
            if not initialized.get("protocolVersion"):
                raise RuntimeError("MCP initialization failed")
            negotiated_protocol = initialized["protocolVersion"]
            server_version = initialized.get("serverInfo", {}).get("version", "unknown")
            client.call("notifications/initialized", notification=True)
            catalogue, _ = client.call("tools/list", {})
            names = {entry["name"] for entry in catalogue.get("tools", [])}
            if not {"list_build_tools", "execute_build_command"} <= names:
                raise RuntimeError("Required MCP tools are not registered")
            output_sizes = {"output-1m": 1024 * 1024, "output-24m": 24 * 1024 * 1024}
            results = {}
            cases = [("callback", "list_build_tools", {})]
            cases += [(tool, "execute_build_command", {
                "buildToolName": tool,
                "projectDir": str(projects[tool]),
                "command": "compile" if tool != "gradle" else "compileJava",
            }) for tool in tools]
            cases += [(label, "execute_build_command", {
                "buildToolName": "gradle", "projectDir": str(projects[label]),
                "command": "compileJava",
            }) for label in output_sizes]
            for label, method, params in cases:
                revision = 0
                previous_class = None
                output_marker = projects[label] / "emitted.bytes" if label.startswith("output-") else None
                for _ in range(args.warmups):
                    if label in tools:
                        revision += 1
                        force_compile(projects[label], revision)
                    if output_marker is not None:
                        output_marker.unlink(missing_ok=True)
                    warmup_result, _ = client.call("tools/call", {"name": method, "arguments": params})
                    if warmup_result.get("isError"):
                        raise RuntimeError("Warmup build failed; response withheld")
                    if label in tools:
                        previous_class = compiled_fingerprint(projects[label], previous_class)
                    if output_marker is not None:
                        verify_emitted(output_marker, output_sizes[label])
                samples = []
                for _ in range(args.runs):
                    if label in tools:
                        revision += 1
                        force_compile(projects[label], revision)
                    if output_marker is not None:
                        output_marker.unlink(missing_ok=True)
                    with RssSampler(client.process.pid) as sampler:
                        result, latency, response_bytes = timed_call(
                            client, "tools/call", {"name": method, "arguments": params}
                        )
                    if result.get("isError"):
                        raise RuntimeError("Build tool returned an error; response withheld")
                    if has_raw_output(result):
                        raise RuntimeError("Raw process output or command reached MCP result")
                    if label in tools:
                        previous_class = compiled_fingerprint(projects[label], previous_class)
                    if output_marker is not None:
                        verify_emitted(output_marker, output_sizes[label])
                    samples.append((latency, sampler.peak_kib, response_bytes))
                results[label] = summarize(samples)
            if results["output-24m"]["response_bytes_max"] > 2 * results["output-1m"]["response_bytes_max"]:
                raise RuntimeError("MCP response scaled with unbounded process output")
        finally:
            client.close()
    report = {
        "schema_version": 1,
        "runtime": {"os": platform.system(), "architecture": platform.machine(),
                    "java": subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT,
                                                    text=True).splitlines()[0],
                    "python": platform.python_version()},
        "method": {"transport": "stdio", "warmups": args.warmups, "runs": args.runs,
                   "rss_sample_period_ms": 100, "fixtures": tools,
                   "cache_state": args.cache_state, "network_mode": args.network_mode,
                   "compile_check": "source revision changed and class fingerprint changed on every call",
                   "output_stress": "synthetic Gradle wrapper; verified 1 MiB and 24 MiB unterminated stdout"},
        "server": {"version": server_version, "negotiated_protocol": negotiated_protocol},
        "results": results,
    }
    encoded = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.write_text(encoded, encoding="utf-8")
    else:
        sys.stdout.write(encoded)


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as exc:
        print(f"benchmark failed: {exc}", file=sys.stderr)
        sys.exit(1)
    except (OSError, TimeoutError, ValueError):
        print("benchmark failed: local process or fixture error; details withheld", file=sys.stderr)
        sys.exit(1)
