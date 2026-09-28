"""Focused checks for the tag-to-artifact release guard."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "check-release-tag.py"


class ReleaseTagTest(unittest.TestCase):
    def check(self, tag: str, pom_version: str, registry_version: str) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pom = root / "pom.xml"
            registry = root / "mcp-registry.json"
            pom.write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                f"<version>{pom_version}</version></project>",
                encoding="utf-8",
            )
            registry.write_text(json.dumps({"version": registry_version}), encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(SCRIPT), "--tag", tag, "--pom", str(pom), "--registry", str(registry)],
                capture_output=True,
                text=True,
                check=False,
            )

    def test_matching_rc(self) -> None:
        self.assertEqual(0, self.check("v2.0.0-rc.1", "2.0.0-rc.1", "2.0.0-rc.1").returncode)

    def test_matching_stable(self) -> None:
        self.assertEqual(0, self.check("v2.0.0", "2.0.0", "2.0.0").returncode)

    def test_mismatched_registry_fails(self) -> None:
        self.assertNotEqual(0, self.check("v2.0.0-rc.1", "2.0.0-rc.1", "2.0.0-SNAPSHOT").returncode)

    def test_mismatched_tag_fails(self) -> None:
        self.assertNotEqual(0, self.check("v2.0.0-rc.2", "2.0.0-rc.1", "2.0.0-rc.1").returncode)

    def test_snapshot_tag_fails(self) -> None:
        self.assertNotEqual(0, self.check("v2.0.0-SNAPSHOT", "2.0.0-SNAPSHOT", "2.0.0-SNAPSHOT").returncode)

    def test_unexpected_suffix_fails(self) -> None:
        self.assertNotEqual(0, self.check("v2.0.0-rc.1-extra", "2.0.0-rc.1", "2.0.0-rc.1").returncode)


if __name__ == "__main__":
    unittest.main()
