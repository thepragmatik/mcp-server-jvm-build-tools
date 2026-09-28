"""Privacy and completeness checks for the optional requirement-set audit."""

import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/conformance_matrix_audit.py"
SPEC = importlib.util.spec_from_file_location("conformance_matrix_audit", SCRIPT)
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)


class ConformanceMatrixAuditTest(unittest.TestCase):
    def test_frozen_server_set_shape(self):
        self.assertEqual(30, len(audit.SCORED))
        self.assertEqual(3, len(audit.UNSCORED))
        self.assertFalse(audit.SCORED & audit.UNSCORED)

    def populate(self, directory):
        for scenario in audit.SCENARIOS:
            folder = directory / f"server-{scenario}-2026-01-01T00-00-00-000Z"
            folder.mkdir()
            (folder / "checks.json").write_text(json.dumps([{
                "id": "wire-schema-valid", "status": "SUCCESS",
                "errorMessage": "private@example.invalid /workspace/private/token.txt",
                "details": {"credential": "synthetic-secret-canary"},
            }]), encoding="utf-8")

    def test_complete_summary_never_prints_private_report_fields(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.populate(root)
            results = audit.read_results(root)
            summary = audit.format_summary(results)
            self.assertIn("AUDIT ONLY / NONBLOCKING", summary)
            self.assertIn("scored scenarios: total=30; pass=30", summary)
            self.assertIn("unscored scenarios: total=3; pass=3", summary)
            self.assertIn("check outcomes: success=33", summary)
            for private in ("private@example.invalid", "/workspace/private", "synthetic-secret-canary"):
                self.assertNotIn(private, summary)

    def test_unknown_check_id_is_replaced_before_summary(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "checks.json"
            report.write_text(json.dumps([{
                "id": "private-user-secret", "status": "FAILURE",
                "errorMessage": "private@example.invalid",
            }]), encoding="utf-8")
            self.assertEqual([("unlisted-check", "FAILURE")], audit.parse_checks(report))

    def test_malformed_check_id_is_rejected_without_traceback_content(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "checks.json"
            report.write_text(json.dumps([{
                "id": {"private": "private@example.invalid"}, "status": "FAILURE",
            }]), encoding="utf-8")
            with self.assertRaises(audit.AuditError):
                audit.parse_checks(report)

    def test_missing_report_rejects_incomplete_audit(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.populate(root)
            missing = next(root.iterdir())
            (missing / "checks.json").unlink()
            with self.assertRaises(audit.AuditError):
                audit.read_results(root)

    def test_failed_check_prints_only_allowlisted_id(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.populate(root)
            report = next(root.glob("server-ping-*/checks.json"))
            report.write_text(json.dumps([{
                "id": "ping", "status": "FAILURE",
                "errorMessage": "private@example.invalid",
            }]), encoding="utf-8")
            summary = audit.format_summary(audit.read_results(root))
            self.assertIn("ping: FAIL; checks=1; failed-checks=ping", summary)
            self.assertIn("Full scored requirement set: NOT PASSED", summary)
            self.assertNotIn("private@example.invalid", summary)

    def test_unknown_scenario_directory_rejected_without_echo(self):
        with self.assertRaises(audit.AuditError) as caught:
            audit.scenario_for_directory("server-private-user-secret-2026-01-01")
        self.assertNotIn("private", str(caught.exception))

    def test_runner_timeout_ends_private_process_group(self):
        with tempfile.TemporaryDirectory() as temporary:
            old_timeout = audit.RUNNER_TIMEOUT_SECONDS
            audit.RUNNER_TIMEOUT_SECONDS = 0.1
            try:
                with self.assertRaises(subprocess.TimeoutExpired):
                    audit.run_runner([sys.executable, "-c", "import time; time.sleep(2)"],
                                     Path(temporary), audit.clean_environment(Path(temporary)))
            finally:
                audit.RUNNER_TIMEOUT_SECONDS = old_timeout


if __name__ == "__main__":
    unittest.main()
