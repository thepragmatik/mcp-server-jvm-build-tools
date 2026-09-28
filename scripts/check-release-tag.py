#!/usr/bin/env python3
"""Fail closed when a release tag and both published version fields disagree."""

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RELEASE_TAG = re.compile(r"v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-rc\.(?:0|[1-9][0-9]*))?\Z")


def validate(tag: str, pom: Path, registry: Path) -> None:
    if RELEASE_TAG.fullmatch(tag) is None:
        raise ValueError("Release tag must be vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-rc.N")

    root = ET.parse(pom).getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    pom_version = root.findtext("m:version", namespaces=namespace)
    if not pom_version:
        raise ValueError("Project version is missing from pom.xml")

    registry_version = json.loads(registry.read_text(encoding="utf-8")).get("version")
    if registry_version != pom_version:
        raise ValueError("Registry version does not match pom.xml")
    if tag != f"v{pom_version}":
        raise ValueError("Release tag does not match pom.xml")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--pom", type=Path, default=Path("pom.xml"))
    parser.add_argument("--registry", type=Path, default=Path("mcp-registry.json"))
    args = parser.parse_args()
    try:
        validate(args.tag, args.pom, args.registry)
    except (ET.ParseError, OSError, json.JSONDecodeError, ValueError) as error:
        print(f"Release metadata check failed: {error}", file=sys.stderr)
        return 1
    print("Release metadata check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
