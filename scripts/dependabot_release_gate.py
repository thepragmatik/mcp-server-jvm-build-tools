#!/usr/bin/env python3
"""Fail closed on open Dependabot alerts for the current default-branch commit.

Only counts and status are printed. Package names, manifest paths, advisory text,
and the GitHub credential stay out of release logs and model-visible output.
"""

import argparse
import json
import re
import subprocess
import sys


REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z")


class GateError(Exception):
    """The release audit could not establish a trustworthy result."""


def gh(*arguments: str) -> str:
    try:
        result = subprocess.run(
            ["gh", "api", *arguments],
            check=False,
            capture_output=True,
            text=True,
            timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise GateError("GitHub alert API unavailable") from exc
    if result.returncode != 0:
        raise GateError("GitHub alert API unavailable or access denied")
    return result.stdout.strip()


def open_alert_count(pages: object) -> int:
    if not isinstance(pages, list) or not pages:
        raise GateError("Invalid alert API response")
    count = 0
    for page in pages:
        if not isinstance(page, list):
            raise GateError("Invalid alert API response")
        for alert in page:
            if not isinstance(alert, dict) or alert.get("state") != "open":
                raise GateError("Invalid alert API response")
            count += 1
    return count


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="GitHub OWNER/REPO")
    args = parser.parse_args()
    if not REPOSITORY.fullmatch(args.repo):
        print("Dependabot release gate: invalid repository name", file=sys.stderr)
        return 2

    try:
        default_branch = gh(f"repos/{args.repo}", "--jq", ".default_branch")
        if not default_branch or not re.fullmatch(r"[A-Za-z0-9_./-]+", default_branch):
            raise GateError("Invalid default branch response")
        remote_head = gh(
            f"repos/{args.repo}/commits/{default_branch}", "--jq", ".sha"
        )
        local_head = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
            timeout=10,
        ).stdout.strip()
        if not re.fullmatch(r"[0-9a-f]{40}", remote_head) or local_head != remote_head:
            raise GateError("Local checkout is not the current default-branch commit")
        dirty = subprocess.run(
            ["git", "status", "--porcelain"],
            check=True,
            capture_output=True,
            text=True,
            timeout=10,
        ).stdout
        if dirty:
            raise GateError("Local checkout has uncommitted changes")
        pages = json.loads(
            gh(
                f"repos/{args.repo}/dependabot/alerts?state=open&per_page=100",
                "--paginate",
                "--slurp",
            )
        )
        count = open_alert_count(pages)
        if gh(f"repos/{args.repo}/commits/{default_branch}", "--jq", ".sha") != local_head:
            raise GateError("Default branch moved during the alert audit")
    except (
        GateError,
        json.JSONDecodeError,
        subprocess.CalledProcessError,
        subprocess.TimeoutExpired,
        OSError,
    ) as exc:
        reason = str(exc) if isinstance(exc, GateError) else "audit unavailable"
        print(f"Dependabot release gate: FAIL ({reason})", file=sys.stderr)
        return 2

    if count:
        print(f"Dependabot release gate: FAIL ({count} open alert(s))", file=sys.stderr)
        return 1
    print("Dependabot release gate: PASS (0 open alerts)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
