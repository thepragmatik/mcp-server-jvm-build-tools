#!/usr/bin/env python3
"""Fail on likely private data in added lines without printing the matched value."""

import argparse
import pathlib
import re
import subprocess
import sys

EMAIL = re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")
HOME = re.compile(r"(?:/Users/|/home/)[A-Za-z0-9._-]+")
PRIVATE_KEY = re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")
SECRET_ASSIGNMENT = re.compile(
    r"(?i)\b(?:api[_-]?key|access[_-]?token|password|passwd|client[_-]?secret)"
    r"\s*[:=]\s*['\"]?([A-Za-z0-9+/_-]{16,})"
)
SAFE_EMAIL_SUFFIXES = (".invalid", ".example", "@example.com", "@example.org")
SAFE_HOME_NAMES = {"private-user", "test-user", "user", "buildtools"}
SAFE_VALUES = {"synthetic-value", "integration-test-only"}


def issues(line: str) -> list[str]:
    found = []
    if any(not email.group().lower().endswith(SAFE_EMAIL_SUFFIXES) for email in EMAIL.finditer(line)):
        found.append("email")
    if any(path.group().split("/")[-1] not in SAFE_HOME_NAMES for path in HOME.finditer(line)):
        found.append("home path")
    if PRIVATE_KEY.search(line):
        found.append("private key")
    if any(match.group(1) not in SAFE_VALUES for match in SECRET_ASSIGNMENT.finditer(line)):
        found.append("secret assignment")
    return found


def added_lines(base: str):
    command = ["git", "diff", "--unified=0", "--no-ext-diff", base]
    diff = subprocess.run(command, check=True, capture_output=True, text=True).stdout
    path = None
    line_number = 0
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            path = line[6:]
        elif line.startswith("@@"):
            match = re.search(r"\+(\d+)", line)
            if match:
                line_number = int(match.group(1))
        elif line.startswith("+") and not line.startswith("+++"):
            yield path, line_number, line[1:]
            line_number += 1
        elif line.startswith(" "):
            line_number += 1

    if base == "HEAD":
        files = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard", "-z"],
            check=True, capture_output=True,
        ).stdout.split(b"\0")
        for raw in files:
            if not raw:
                continue
            file = pathlib.Path(raw.decode())
            if not file.is_file():
                continue
            try:
                for number, line in enumerate(file.read_text().splitlines(), 1):
                    yield str(file), number, line
            except UnicodeDecodeError:
                continue


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="HEAD")
    args = parser.parse_args()
    findings = []
    for path, number, line in added_lines(args.base):
        for kind in issues(line):
            findings.append((path, number, kind))
    for path, number, kind in findings:
        print(f"{path}:{number}: possible {kind}; value withheld")
    print(f"Privacy scan: {len(findings)} finding(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
