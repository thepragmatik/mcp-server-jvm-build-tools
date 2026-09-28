#!/usr/bin/env python3
"""Fail on likely private data without printing the matched value."""

import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys

EMAIL = re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")
HOME = re.compile(r"(?:/Users/|/home/)[A-Za-z0-9._-]+")
PRIVATE_KEY = re.compile(r"-----BEGIN (?:ENCRYPTED |RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY(?: BLOCK)?-----")
SECRET_ASSIGNMENT = re.compile(
    r"(?i)\b(?:api[_-]?key|access[_-]?token|password|passwd|client[_-]?secret)"
    r"\s*[:=]\s*['\"]?([A-Za-z0-9+/_-]{16,})(?![A-Za-z0-9+/_-])"
)
CONFIG_SECRET_ASSIGNMENT = re.compile(
    r"(?i)(?<![A-Za-z0-9])(?:[A-Za-z0-9_.-]*?"
    r"(?:password|passwd|pass|api[_-]?key|access[_-]?key(?:[_-]?id)?|"
    r"secret(?:[_-]?access)?[_-]?key|private[_-]?key|secret|token))"
    r"\s*[:=]\s*['\"]?([A-Za-z0-9+/_-]{16,})(?![A-Za-z0-9+/_-])"
)
CONFIG_SUFFIXES = (".properties", ".yaml", ".yml", ".json", ".toml", ".xml", ".ini", ".gradle", ".gradle.kts")


def is_config_path(path: str) -> bool:
    filename = pathlib.Path(path).name
    return path.endswith(CONFIG_SUFFIXES) or filename == ".env" or filename.startswith(".env.")


def file_ref(path: str) -> str:
    return hashlib.sha256(path.encode("utf-8", "surrogateescape")).hexdigest()[:12]
SAFE_EMAIL_SUFFIXES = (".invalid", ".example", "@example.com", "@example.org")
SAFE_HOME_NAMES = {"private-user", "test-user", "user", "buildtools"}
SAFE_VALUES = {"synthetic-value", "integration-test-only"}


def issues(line: str, path: str = "") -> list[str]:
    found = []
    scm_connection = path == "pom.xml" and bool(
        re.search(r"<\/?(?:developerConnection|connection)>.*scm:git:git@github\.com:", line)
    )
    if any(
        not email.group().lower().endswith(SAFE_EMAIL_SUFFIXES)
        and not (scm_connection and email.group().lower() == "git@" + "github.com")
        for email in EMAIL.finditer(line)
    ):
        found.append("email")
    if any(path.group().split("/")[-1] not in SAFE_HOME_NAMES for path in HOME.finditer(line)):
        found.append("home path")
    if PRIVATE_KEY.search(line):
        found.append("private key")
    secret_pattern = CONFIG_SECRET_ASSIGNMENT if is_config_path(path) else SECRET_ASSIGNMENT
    if any(
        match.group(1) not in SAFE_VALUES
        and not (
            path.endswith(".java")
            and re.match(r"\s*(?:[.([?!+*:=]|!=)", line[match.end():])
        )
        for match in secret_pattern.finditer(line)
    ):
        found.append("secret assignment")
    return found


def added_lines(base: str):
    changed = subprocess.run(
        ["git", "diff", "--name-only", "-z", "--no-ext-diff", base, "--"],
        check=True, capture_output=True,
    ).stdout.split(b"\0")
    for raw_path in changed:
        if not raw_path:
            continue
        path = os.fsdecode(raw_path)
        diff = subprocess.run(
            ["git", "diff", "--unified=0", "--no-ext-diff", base,
             "--", ":(literal)" + path],
            check=True, capture_output=True, text=True, errors="replace",
        ).stdout
        line_number = 0
        in_hunk = False
        for line in diff.splitlines():
            if line.startswith("@@"):
                match = re.search(r"\+(\d+)", line)
                if match:
                    line_number = int(match.group(1))
                    in_hunk = True
            elif in_hunk and line.startswith("+"):
                yield path, line_number, line[1:]
                line_number += 1
            elif in_hunk and line.startswith(" "):
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
            for number, line in local_file_lines(file):
                yield str(file), number, line


def local_file_lines(file: pathlib.Path):
    """Read local tracked content; inspect symlink text without following it."""
    try:
        if file.is_symlink():
            yield 1, os.readlink(file)
        elif file.is_file():
            for number, line in enumerate(file.read_text().splitlines(), 1):
                yield number, line
    except (UnicodeDecodeError, OSError):
        return


def tracked_lines():
    """Scan every tracked text file, including legacy content outside the current diff."""
    files = subprocess.run(
        ["git", "ls-files", "-z"], check=True, capture_output=True
    ).stdout.split(b"\0")
    for raw in files:
        if not raw:
            continue
        file = pathlib.Path(raw.decode())
        for number, line in local_file_lines(file):
            yield str(file), number, line


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="HEAD")
    parser.add_argument("--tracked", action="store_true", help="scan all tracked text files")
    parser.add_argument("--resolve-ref", metavar="REF", help="identify one finding file in a human terminal only")
    args = parser.parse_args()
    if args.resolve_ref is not None:
        if not re.fullmatch(r"[0-9a-f]{12}", args.resolve_ref):
            print("Invalid file reference", file=sys.stderr)
            return 2
        if os.environ.get("CI") or not sys.stdout.isatty():
            print("File references can only be resolved in a local interactive terminal", file=sys.stderr)
            return 2
        files = subprocess.run(
            ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
            check=True, capture_output=True,
        ).stdout.split(b"\0")
        candidates = (path.decode("utf-8", "surrogateescape") for path in files if path)
        matches = [path for path in candidates if file_ref(path) == args.resolve_ref]
        if len(matches) != 1:
            print("No unique local file matches this reference", file=sys.stderr)
            return 2
        print(json.dumps(matches[0]))
        return 0
    findings = []
    lines = tracked_lines() if args.tracked else added_lines(args.base)
    for path, number, line in lines:
        for kind in issues(line, path):
            findings.append((path, number, kind))
    for path, number, kind in findings:
        print(f"file:{file_ref(str(path))}:{number}: possible {kind}; value and filename withheld")
    print(f"Privacy scan: {len(findings)} finding(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
