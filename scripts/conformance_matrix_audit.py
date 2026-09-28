#!/usr/bin/env python3
"""Opt-in, privacy-bounded audit of the frozen MCP 2025-11-25 server set.

This is exploratory coverage, not the six-scenario release gate or a claim of
MCP conformance. The official runner and server use private scratch directories;
their stdout, stderr, transcripts, and free-form check fields are never printed.
"""

import argparse
from collections import Counter
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import time


RUNNER = "@modelcontextprotocol/conformance@0.2.0-alpha.11"
REVISION = "2025-11-25"
JAR = Path(__file__).resolve().parents[1] / "target/mcp-server-jvm-build-tools.jar"
RUNNER_TIMEOUT_SECONDS = 240
REPORT_LIMIT_BYTES = 1_048_576

# Frozen by the pinned runner's `list --requirements 2025-11-25` command.
SCORED = frozenset({
    "server-initialize", "logging-set-level", "ping", "completion-complete",
    "tools-list", "tools-call-simple-text", "tools-call-image", "tools-call-audio",
    "tools-call-embedded-resource", "tools-call-mixed-content", "tools-call-with-logging",
    "tools-call-error", "tools-call-with-progress", "tools-call-sampling",
    "tools-call-elicitation", "elicitation-sep1034-defaults",
    "server-sse-multiple-streams", "elicitation-sep1330-enums", "resources-list",
    "resources-read-text", "resources-read-binary", "resources-templates-read",
    "resources-subscribe", "resources-unsubscribe", "prompts-list",
    "prompts-get-simple", "prompts-get-with-args", "prompts-get-embedded-resource",
    "prompts-get-with-image", "dns-rebinding-protection",
})
UNSCORED = frozenset({
    "server-session-lifecycle",  # added after release
    "json-schema-2020-12",       # pending upstream fixture
    "server-sse-polling",        # pending upstream fixture
})
SCENARIOS = SCORED | UNSCORED

# Only these pinned, static IDs may appear in terminal output. All other report
# fields, especially errorMessage, description, and details, remain local.
SAFE_CHECK_IDS = frozenset({
    "completion-complete", "elicitation-sep1034-general",
    "elicitation-sep1330-general", "incoming-response",
    "json-schema-2020-12-tool-found", "localhost-host-rebinding-rejected",
    "localhost-host-valid-accepted", "logging-set-level", "outgoing-request",
    "ping", "prompts-get-embedded-resource", "prompts-get-simple",
    "prompts-get-with-args", "prompts-get-with-image", "prompts-list",
    "resources-list", "resources-read-binary", "resources-read-text",
    "resources-subscribe", "resources-templates-read", "resources-unsubscribe",
    "server-initialize", "server-session-id-visible-ascii",
    "server-session-lifecycle-skipped", "server-sse-content-type",
    "server-sse-multiple-streams-session", "server-sse-polling-session",
    "tools-call-audio", "tools-call-elicitation", "tools-call-embedded-resource",
    "tools-call-error", "tools-call-image", "tools-call-mixed-content",
    "tools-call-sampling", "tools-call-simple-text", "tools-call-with-logging",
    "tools-call-with-progress", "tools-list", "tools-name-format",
    "wire-schema-valid",
})
STATUSES = frozenset({"SUCCESS", "FAILURE", "WARNING", "INFO"})
UNLISTED_CHECK = "unlisted-check"


class AuditError(Exception):
    """A private runner or report error; never include its raw data in output."""


def scenario_for_directory(name):
    matches = [scenario for scenario in SCENARIOS
               if name.startswith(f"server-{scenario}-")]
    if len(matches) != 1:
        raise AuditError()
    return matches[0]


def parse_checks(report):
    if report.stat().st_size > REPORT_LIMIT_BYTES:
        raise AuditError()
    try:
        checks = json.loads(report.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, ValueError) as error:
        raise AuditError() from error
    if not isinstance(checks, list) or not checks or len(checks) > 1000:
        raise AuditError()
    result = []
    for check in checks:
        if not isinstance(check, dict):
            raise AuditError()
        check_id, status = check.get("id"), check.get("status")
        if (not isinstance(check_id, str) or not isinstance(status, str)
                or status not in STATUSES):
            raise AuditError()
        result.append((check_id if check_id in SAFE_CHECK_IDS else UNLISTED_CHECK,
                       status))
    return result


def read_results(report_directory):
    results = {}
    for report in report_directory.rglob("checks.json"):
        scenario = scenario_for_directory(report.parent.name)
        if scenario in results:
            raise AuditError()
        results[scenario] = parse_checks(report)
    if set(results) != SCENARIOS:
        raise AuditError()
    return results


def verdict(checks):
    statuses = {status for _, status in checks}
    if "FAILURE" in statuses:
        return "FAIL"
    if "WARNING" in statuses:
        return "WARN"
    if "SUCCESS" in statuses:
        return "PASS"
    return "INFO"


def format_summary(results):
    lines = [f"AUDIT ONLY / NONBLOCKING — MCP {REVISION} server requirements; "
             "this is not a conformance certification"]
    for label, scenarios in (("scored", SCORED), ("unscored", UNSCORED)):
        counts = Counter(verdict(results[scenario]) for scenario in scenarios)
        lines.append(f"{label} scenarios: total={len(scenarios)}; "
                     + ", ".join(f"{state.lower()}={counts[state]}"
                                 for state in ("PASS", "FAIL", "WARN", "INFO")))
        for scenario in sorted(scenarios):
            checks = results[scenario]
            failed = sorted({check_id for check_id, status in checks if status == "FAILURE"})
            lines.append(f"  {scenario}: {verdict(checks)}; checks={len(checks)}"
                         + (f"; failed-checks={','.join(failed)}" if failed else ""))
    check_counts = Counter(status for checks in results.values() for _, status in checks)
    lines.append("check outcomes: " + ", ".join(f"{status.lower()}={check_counts[status]}"
                                               for status in ("SUCCESS", "FAILURE", "WARNING", "INFO")))
    if any(verdict(results[scenario]) == "FAIL" for scenario in SCORED):
        lines.append("Full scored requirement set: NOT PASSED")
    lines.append("Release gate remains the six selected official scenarios plus adversarial checks.")
    return "\n".join(lines)


def clean_environment(scratch):
    environment = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"),
                   "HOME": str(scratch), "TMPDIR": str(scratch), "CI": "true",
                   "npm_config_cache": str(Path.home() / ".npm")}
    if os.environ.get("JAVA_HOME"):
        environment["JAVA_HOME"] = os.environ["JAVA_HOME"]
    return environment


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def wait_for_server(process, port):
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise AuditError()
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                return
        except OSError:
            time.sleep(0.2)
    raise AuditError()


def stop_process(process):
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def run_runner(command, work, environment):
    # npx starts a Node child. Keep both in one private process group so a
    # timeout or interruption cannot leave the runner behind.
    process = subprocess.Popen(command, stdin=subprocess.DEVNULL,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               cwd=work, env=environment, start_new_session=True)
    try:
        return process.wait(timeout=RUNNER_TIMEOUT_SECONDS)
    finally:
        if process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()


def run_audit():
    if not JAR.is_file():
        raise AuditError()
    with tempfile.TemporaryDirectory(prefix="mcp-requirement-audit-") as temporary:
        scratch = Path(temporary)
        work = scratch / "work"
        reports = scratch / "reports"
        work.mkdir(mode=0o700)
        reports.mkdir(mode=0o700)
        environment = clean_environment(scratch)
        port = free_port()
        server = subprocess.Popen([
            "java", "-Dspring.profiles.active=http", "-Dserver.address=127.0.0.1",
            f"-Dserver.port={port}", "-Dbuildtools.oauth.resource-server.enabled=false",
            "-jar", str(JAR),
        ], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, cwd=work, env=environment)
        try:
            wait_for_server(server, port)
            returncode = run_runner([
                "npx", "--yes", RUNNER, "server", "--url",
                f"http://127.0.0.1:{port}/mcp", "--requirements", REVISION,
                "--output-dir", str(reports),
            ], work, environment)
            results = read_results(reports)
            scored_failed = any(verdict(results[scenario]) == "FAIL" for scenario in SCORED)
            if (returncode not in (0, 1)
                    or (returncode == 0 and scored_failed)
                    or (returncode == 1 and not scored_failed)):
                raise AuditError()
            return results
        finally:
            stop_process(server)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fail-on-scored-failure", action="store_true",
                        help="Return 1 when a scored scenario fails; default is an exploratory audit")
    args = parser.parse_args()
    try:
        results = run_audit()
    except (AuditError, OSError, subprocess.TimeoutExpired):
        print("Requirement audit could not complete; inspect local environment privately.",
              file=sys.stderr)
        return 2
    print(format_summary(results))
    return int(args.fail_on_scored_failure
               and any(verdict(results[scenario]) == "FAIL" for scenario in SCORED))


if __name__ == "__main__":
    sys.exit(main())
