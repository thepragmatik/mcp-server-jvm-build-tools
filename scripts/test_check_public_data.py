"""Focused tests for the public-data scanner's narrow exceptions."""

import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest import mock


SPEC = importlib.util.spec_from_file_location(
    "check_public_data", Path(__file__).with_name("check-public-data.py")
)
SCANNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCANNER)


class PublicDataScannerTest(unittest.TestCase):
    def test_detects_private_home_and_email(self):
        self.assertIn("home path", SCANNER.issues("/Users/" + "synthetic-employee/project"))
        self.assertIn("email", SCANNER.issues("employee@" + "company.example.net"))

    def test_allows_only_scm_git_url_context(self):
        scm_url = "<connection>scm:git:git@" + "github.com:owner/repo</connection>"
        self.assertEqual([], SCANNER.issues(scm_url, "pom.xml"))
        self.assertIn("email", SCANNER.issues(scm_url, "other.xml"))
        self.assertIn("email", SCANNER.issues("maintainer=git@" + "github.com"))

    def test_ignores_local_method_results_but_detects_literal_secret(self):
        self.assertEqual([], SCANNER.issues("String accessToken = jwtTokenService.generateToken();", "Example.java"))
        self.assertEqual([], SCANNER.issues("String password = getChildText(element);", "Example.java"))
        self.assertEqual([], SCANNER.issues("client" + "Secret = basicCredentials[1];", "Example.java"))
        self.assertEqual([], SCANNER.issues("client" + "Secret = clientSecretParam != null ? clientSecretParam : \"\";", "Example.java"))
        self.assertIn("secret assignment", SCANNER.issues("password = " + "syntheticcredential123456"))
        self.assertIn("secret assignment", SCANNER.issues("pass" + "word = " + "syntheticcredential123456!", "gradle.properties"))
        self.assertIn("secret assignment", SCANNER.issues("pass" + "word = " + "syntheticcredential123456(", "gradle.properties"))
        self.assertEqual([], SCANNER.issues("password = integration-test-only"))


    def test_camel_case_secret_name_is_detected(self):
        candidate = "nexus" + "Password=" + "syntheticcredential123456"
        self.assertIn("secret assignment", SCANNER.issues(candidate, "gradle.properties"))

    def test_common_prefixed_credential_names_are_detected(self):
        keys = (
            "aws_access_key_id", "aws_secret_access_key", "github_token", "auth_token",
            "secretKey", "private_key", "db_pass", "bearerToken",
        )
        for key in keys:
            with self.subTest(key=key):
                self.assertIn("secret assignment", SCANNER.issues(key + "=" + "syntheticcredential123456", "settings.properties"))

    def test_gradle_and_env_variants_receive_config_credential_rules(self):
        for path in ("settings.gradle", "build.gradle.kts", ".env", ".env.production"):
            with self.subTest(path=path):
                self.assertIn(
                    "secret assignment",
                    SCANNER.issues("github_token=" + "syntheticcredential123456", path),
                )

    def test_common_private_key_headers_are_detected(self):
        for variant in ("ENCRYPTED PRIVATE KEY", "DSA PRIVATE KEY", "PGP PRIVATE KEY BLOCK"):
            with self.subTest(variant=variant):
                self.assertIn("private key", SCANNER.issues("-----BEGIN " + variant + "-----"))

    def test_symlinks_scan_targets_without_dereferencing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = root / "repo"
            repo.mkdir()
            (root / "outside.txt").write_text("person@" + "private.example.net\n")
            (repo / "tracked-link").symlink_to("../outside.txt")
            (repo / "untracked-link").symlink_to("../outside.txt")
            original = Path.cwd()
            try:
                os.chdir(repo)
                subprocess.run(["git", "init", "-q"], check=True)
                subprocess.run(["git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                                "commit", "--allow-empty", "-q", "-m", "init"], check=True)
                subprocess.run(["git", "add", "tracked-link"], check=True)
                tracked = list(SCANNER.tracked_lines())
                changed = list(SCANNER.added_lines("HEAD"))
            finally:
                os.chdir(original)

            self.assertIn(("tracked-link", 1, "../outside.txt"), tracked)
            self.assertIn(("untracked-link", 1, "../outside.txt"), changed)
            self.assertFalse(any("person@" in line for _, _, line in tracked + changed))

    def test_finding_output_hides_malicious_filename(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            filename = "person@" + "private.example.net\n::error::flag"
            (repo / filename).write_text("password=" + "syntheticcredential123456\n")
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            subprocess.run(["git", "add", "--", filename], cwd=repo, check=True)
            scan = subprocess.run(
                ["python3", str(Path(__file__).resolve().with_name("check-public-data.py")), "--tracked"],
                cwd=repo, capture_output=True, text=True,
            )
            self.assertEqual(1, scan.returncode)
            self.assertIn("possible secret assignment", scan.stdout)
            self.assertNotIn("person@", scan.stdout)
            self.assertNotIn("::error::", scan.stdout)
            self.assertNotIn("syntheticcredential123456", scan.stdout)

    def test_reference_resolution_requires_human_terminal(self):
        class TerminalOutput(io.StringIO):
            def isatty(self):
                return True

        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            filename = "person@" + "private.example.net\n::error::flag"
            (repo / filename).write_text("synthetic fixture\n")
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            subprocess.run(["git", "add", "--", filename], cwd=repo, check=True)
            ref = SCANNER.file_ref(filename)
            original = Path.cwd()
            try:
                os.chdir(repo)
                with mock.patch.object(sys, "argv", ["scanner", "--resolve-ref", ref]):
                    with mock.patch.dict(os.environ, {"CI": ""}):
                        human_output = TerminalOutput()
                        with redirect_stdout(human_output):
                            self.assertEqual(0, SCANNER.main())
                        self.assertEqual(filename, json.loads(human_output.getvalue()))
                        self.assertNotIn("\n::error::", human_output.getvalue())

                        agent_output = io.StringIO()
                        with redirect_stdout(agent_output), redirect_stderr(io.StringIO()):
                            self.assertEqual(2, SCANNER.main())
                        self.assertEqual("", agent_output.getvalue())

                    with mock.patch.dict(os.environ, {"CI": "true"}):
                        ci_output = TerminalOutput()
                        with redirect_stdout(ci_output), redirect_stderr(io.StringIO()):
                            self.assertEqual(2, SCANNER.main())
                        self.assertEqual("", ci_output.getvalue())
            finally:
                os.chdir(original)


if __name__ == "__main__":
    unittest.main()
