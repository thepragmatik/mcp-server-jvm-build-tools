"""Fail-closed contract tests for the keyless release alert audit."""

import importlib.util
import io
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch
from contextlib import redirect_stderr


spec = importlib.util.spec_from_file_location(
    "dependabot_release_gate", Path(__file__).with_name("dependabot_release_gate.py")
)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class DependabotReleaseGateTest(unittest.TestCase):
    def test_empty_pages_are_an_explicit_zero_alert_result(self):
        self.assertEqual(0, gate.open_alert_count([[]]))

    def test_counts_every_page(self):
        pages = [[{"state": "open"}], [{"state": "open"}, {"state": "open"}]]
        self.assertEqual(3, gate.open_alert_count(pages))

    def test_malformed_or_unexpected_results_fail_closed(self):
        for pages in ([], {}, [None], [[{}]], [[{"state": "fixed"}]]):
            with self.subTest(pages=pages), self.assertRaises(gate.GateError):
                gate.open_alert_count(pages)

    def test_api_failure_does_not_print_its_response(self):
        private_response = "synthetic-private-canary.invalid"
        failed = subprocess.CompletedProcess(
            args=[], returncode=1, stdout=private_response, stderr=private_response
        )
        with patch.object(gate.subprocess, "run", return_value=failed):
            with self.assertRaisesRegex(gate.GateError, "access denied") as result:
                gate.gh("repos/example.invalid/repo/dependabot/alerts")
        self.assertNotIn(private_response, str(result.exception))

    def test_stale_checkout_fails_before_alert_query(self):
        with patch.object(gate, "gh", side_effect=["main", "a" * 40]) as api:
            with patch.object(
                gate.subprocess,
                "run",
                return_value=subprocess.CompletedProcess([], 0, "b" * 40, ""),
            ):
                with patch.object(gate.sys, "argv", ["gate", "--repo", "owner/repo"]):
                    with redirect_stderr(io.StringIO()):
                        self.assertEqual(2, gate.main())
        self.assertEqual(2, api.call_count)

    def test_open_alert_blocks_release_without_identifying_package(self):
        private_package = "synthetic-private-canary.invalid"
        head = "a" * 40
        pages = [[{"state": "open", "dependency": {"package": private_package}}]]
        with patch.object(
            gate, "gh", side_effect=["main", head, gate.json.dumps(pages), head]
        ):
            with patch.object(
                gate.subprocess,
                "run",
                side_effect=[
                    subprocess.CompletedProcess([], 0, head, ""),
                    subprocess.CompletedProcess([], 0, "", ""),
                ],
            ):
                with patch.object(gate.sys, "argv", ["gate", "--repo", "owner/repo"]):
                    output = io.StringIO()
                    with redirect_stderr(output):
                        self.assertEqual(1, gate.main())
        self.assertIn("1 open alert", output.getvalue())
        self.assertNotIn(private_package, output.getvalue())

    def test_default_branch_advance_during_query_fails_closed(self):
        head = "a" * 40
        with patch.object(gate, "gh", side_effect=["main", head, "[[]]", "b" * 40]):
            with patch.object(
                gate.subprocess,
                "run",
                side_effect=[
                    subprocess.CompletedProcess([], 0, head, ""),
                    subprocess.CompletedProcess([], 0, "", ""),
                ],
            ):
                with patch.object(gate.sys, "argv", ["gate", "--repo", "owner/repo"]):
                    with redirect_stderr(io.StringIO()):
                        self.assertEqual(2, gate.main())

    def test_dirty_checkout_fails_before_alert_query(self):
        head = "a" * 40
        with patch.object(gate, "gh", side_effect=["main", head]) as api:
            with patch.object(
                gate.subprocess,
                "run",
                side_effect=[
                    subprocess.CompletedProcess([], 0, head, ""),
                    subprocess.CompletedProcess([], 0, " M pom.xml", ""),
                ],
            ):
                with patch.object(gate.sys, "argv", ["gate", "--repo", "owner/repo"]):
                    with redirect_stderr(io.StringIO()):
                        self.assertEqual(2, gate.main())
        self.assertEqual(2, api.call_count)


if __name__ == "__main__":
    unittest.main()
