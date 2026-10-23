import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from tests.support import write_core, write_ui

RELEASE = Path(__file__).resolve().parents[1] / "release.py"


def run_cli(*args, output="", script=RELEASE):
    """Runs release.py like the workflow does; GitHub's step output file is written only when `output` names one."""
    env = dict(os.environ, GITHUB_OUTPUT=output, GITHUB_STEP_SUMMARY="")
    return subprocess.run([sys.executable, str(script), *args], capture_output=True, text=True, env=env)


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

    def test_pending_creates_nothing_when_the_merge_does_not_contain_the_head(self):
        out = self.folder / "pending.json"
        result = run_cli("pending", "--head", "0" * 40, "--merge", "HEAD", "--branch", "staging", "--out", str(out), "--notes", str(self.folder / "notes"))
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("::error::", result.stdout)
        self.assertFalse(out.exists())

    def test_pending_writes_the_untagged_releases_and_their_notes_for_the_workflow(self):
        """Runs a copy of the release tools in a throwaway repository whose core version has no tag yet."""
        repo = self.folder / "repo"
        shutil.copytree(RELEASE.parent, repo / ".github/release", ignore=shutil.ignore_patterns("tests", "__pycache__"))
        write_core(repo, "26.9.0", "26.9.0", "26.9.0", "## 26.9.0\n* Core change.\n\n## 26.8.0\n* Old.\n")
        write_ui(repo, "26.8.0", "## 26.8.0\n* Old UI.\n")
        git = ["git", "-C", str(repo), "-c", "user.name=Release test", "-c", "user.email=release@example.com", "-c", "commit.gpgsign=false", "-c", "tag.gpgSign=false"]
        subprocess.run(["git", "init", "-q", str(repo)], check=True)
        subprocess.run(git + ["add", "-A"], check=True)
        subprocess.run(git + ["commit", "-q", "-m", "Release 26.9.0"], check=True)
        subprocess.run(git + ["tag", "ui-26.8.0"], check=True)
        out = self.folder / "pending.json"
        notes = self.folder / "notes"
        result = run_cli("pending", "--head", "HEAD", "--merge", "HEAD", "--branch", "staging", "--out", str(out), "--notes", str(notes), script=repo / ".github/release/release.py")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(json.loads(out.read_text(encoding="utf-8")), {"releases": [{"tag": "26.9.0", "prerelease": False, "latest": True}]})
        self.assertEqual(sorted(path.name for path in notes.iterdir()), ["26.9.0.md"])
        self.assertEqual((notes / "26.9.0.md").read_text(encoding="utf-8"), "* Core change.\n")

    def test_prerelease_flag_must_match(self):
        cases = [("26.8.1", "true", 1), ("26.8.1-rc1", "false", 1), ("26.8.1", "unknown", 1), ("26.8.1-rc1", "true", 0), ("26.8.1", "false", 0)]
        for tag, prerelease, code in cases:
            with self.subTest(tag=tag, prerelease=prerelease):
                result = run_cli("plan", "--tag", tag, "--target", "production", "--prerelease", prerelease, "--out", str(self.folder / "plan.json"))
                self.assertEqual(result.returncode, code, result.stdout)

    def test_check_environment_reads_the_api_answer_and_fails_an_unprotected_environment(self):
        """The workflow saves the environments API answer and passes its path; an unprotected environment fails the step."""
        protected = {"name": "maven-release", "protection_rules": [{"type": "required_reviewers", "reviewers": [{"type": "User"}]}],
                     "deployment_branch_policy": {"protected_branches": False, "custom_branch_policies": True}}
        unprotected = {"name": "maven-release", "protection_rules": [], "deployment_branch_policy": None}
        for environment, code in ((protected, 0), (unprotected, 1)):
            with self.subTest(code=code):
                path = self.folder / "environment.json"
                path.write_text(json.dumps(environment), encoding="utf-8")
                result = run_cli("check-environment", "--environment", str(path))
                self.assertEqual(result.returncode, code, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
