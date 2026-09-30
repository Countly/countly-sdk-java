import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

RELEASE = Path(__file__).resolve().parents[1] / "release.py"


def run_cli(*args, output=""):
    """Runs release.py like the workflow does; GitHub's step output file is written only when `output` names one."""
    env = dict(os.environ, GITHUB_OUTPUT=output, GITHUB_STEP_SUMMARY="")
    return subprocess.run([sys.executable, str(RELEASE), *args], capture_output=True, text=True, env=env)


class ReleaseCliTest(unittest.TestCase):
    def setUp(self):
        self.folder = Path(tempfile.mkdtemp())

    def test_plan_writes_plan_json_and_the_task_lists(self):
        cases = [
            ("26.8.1", ["java"], ":sdk-java:check", ":sdk-java:publishAllPublicationsToReleaseStagingRepository :sdk-java:cyclonedxDirectBom"),
            ("ui-26.8.1", ["java-ui"], ":sdk-java-ui:check", ":sdk-java-ui:publishAllPublicationsToReleaseStagingRepository :sdk-java-ui:cyclonedxDirectBom"),
        ]
        for tag, artifacts, check_tasks, publish_tasks in cases:
            with self.subTest(tag=tag):
                out = self.folder / "plan.json"
                output = self.folder / "output"
                output.write_bytes(b"")
                result = run_cli("plan", "--tag", tag, "--target", "test", "--out", str(out), output=str(output))
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(json.loads(out.read_text(encoding="utf-8"))["artifacts"], artifacts)
                lines = dict(line.split("=", 1) for line in output.read_text(encoding="utf-8").splitlines())
                self.assertEqual(lines["environment"], "maven-test")
                self.assertEqual(lines["check_tasks"], check_tasks)
                self.assertEqual(lines["publish_tasks"], publish_tasks)
                self.assertEqual(lines["published_modules"], ":sdk-java,:sdk-java-ui")

    def test_plan_refuses_an_unknown_tag(self):
        result = run_cli("plan", "--tag", "v1", "--target", "test", "--out", str(self.folder / "plan.json"))
        self.assertEqual(result.returncode, 1)
        self.assertIn("::error::", result.stdout)

    def test_checkout_tags_are_a_json_list_the_matrix_can_read(self):
        output = self.folder / "output"
        output.write_bytes(b"")
        result = run_cli("checkout-tags", "--branch", "staging", output=str(output))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        line = output.read_text(encoding="utf-8").strip()
        self.assertTrue(line.startswith("tags="), line)
        tags = json.loads(line[len("tags="):])
        self.assertEqual(len(tags), 2)
        self.assertTrue(tags[1].startswith("ui-"), tags)

    def test_a_branch_without_releases_gets_the_empty_list_the_workflow_skips_on(self):
        output = self.folder / "output"
        output.write_bytes(b"")
        result = run_cli("checkout-tags", "--branch", "master", output=str(output))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(output.read_text(encoding="utf-8").strip(), "tags=[]")

    def test_prerelease_flag_must_match(self):
        cases = [("26.8.1", "true", 1), ("26.8.1-rc1", "false", 1), ("26.8.1", "unknown", 1), ("26.8.1-rc1", "true", 0), ("26.8.1", "false", 0)]
        for tag, prerelease, code in cases:
            with self.subTest(tag=tag, prerelease=prerelease):
                result = run_cli("plan", "--tag", tag, "--target", "production", "--prerelease", prerelease, "--out", str(self.folder / "plan.json"))
                self.assertEqual(result.returncode, code, result.stdout)


if __name__ == "__main__":
    unittest.main()
