"""Validity checks for the synthetic benchmark evidence."""

import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path


SOURCE = Path(__file__).with_name("benchmark-release-gate.py")
SPEC = importlib.util.spec_from_file_location("benchmark_release_gate", SOURCE)
BENCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BENCH)


class BenchmarkEvidenceTest(unittest.TestCase):
    def test_output_case_proves_emitted_bytes_and_rejects_zero_output(self):
        with tempfile.TemporaryDirectory() as temp:
            project = BENCH.output_fixture(Path(temp), 1024 * 1024)
            marker = project / "emitted.bytes"
            with open(project / "payload.bin", "wb") as output:
                completed = subprocess.run(
                    [str(project / "gradlew"), "compileJava"], cwd=project,
                    stdout=output, stderr=subprocess.DEVNULL, check=False,
                )
            self.assertEqual(completed.returncode, 0)
            self.assertEqual((project / "payload.bin").stat().st_size, 1024 * 1024)
            BENCH.verify_emitted(marker, 1024 * 1024)

            marker.unlink()
            (project / "gradlew").write_text("#!/bin/sh\nexit 0\n", encoding="ascii")
            completed = subprocess.run(
                [str(project / "gradlew"), "compileJava"], cwd=project,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False,
            )
            self.assertEqual(completed.returncode, 0)
            with self.assertRaisesRegex(RuntimeError, "byte counter"):
                BENCH.verify_emitted(marker, 1024 * 1024)

    def test_compile_case_changes_source_and_removes_old_class(self):
        with tempfile.TemporaryDirectory() as temp:
            project = BENCH.fixture(Path(temp), "maven")
            compiled = project / "target/classes/example/Tiny.class"
            compiled.parent.mkdir(parents=True)
            compiled.write_bytes(b"old")
            BENCH.force_compile(project, 2)
            self.assertFalse(compiled.exists())
            self.assertIn("REVISION = 2", (project / "src/main/java/example/Tiny.java").read_text())


if __name__ == "__main__":
    unittest.main()
