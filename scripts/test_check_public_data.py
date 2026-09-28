"""Focused tests for the public-data scanner's narrow exceptions."""

import importlib.util
from pathlib import Path
import unittest


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


if __name__ == "__main__":
    unittest.main()
