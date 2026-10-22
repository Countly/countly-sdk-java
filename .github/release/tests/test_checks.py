import subprocess
import unittest

from checks import branch_problems, environment_problems, missing_checks

COMMIT = "c" * 40
REVIEWERS = {"type": "required_reviewers", "reviewers": [{"type": "User", "reviewer": {"login": "releaser"}}]}
TAG_POLICY = {"protected_branches": False, "custom_branch_policies": True}


class ChecksTest(unittest.TestCase):
    def test_reports_missing_and_failed_checks(self):
        runs = {"check_runs": [{"name": "Build and Test", "conclusion": "success"}, {"name": "Analyze (java)", "conclusion": "failure"}]}
        required = ["Build and Test", "Analyze (java)", "OSV scan (all modules)"]
        self.assertEqual(missing_checks(runs, required), ["Analyze (java)", "OSV scan (all modules)"])

    def test_every_run_of_a_required_check_must_succeed(self):
        runs = {"check_runs": [
            {"name": "Build and Test", "conclusion": "failure"},
            {"name": "Build and Test", "conclusion": "success"},
            {"name": "Analyze (java)", "conclusion": "success"},
            {"name": "Analyze (java)", "conclusion": None},
            {"name": "OSV scan (all modules)", "conclusion": "success"},
            {"name": "OSV scan (all modules)", "conclusion": "success"},
        ]}
        required = ["Build and Test", "Analyze (java)", "OSV scan (all modules)"]
        self.assertEqual(missing_checks(runs, required), ["Build and Test", "Analyze (java)"])

    def test_tag_commit_must_be_on_the_release_branch(self):
        calls = []

        def on_branch(args, **kwargs):
            calls.append(args)
            return subprocess.CompletedProcess(args, 0)

        def elsewhere(args, **kwargs):
            return subprocess.CompletedProcess(args, 1)

        self.assertEqual(branch_problems(COMMIT, "staging", "/repo", runner=on_branch), [])
        self.assertEqual(calls[0], ["git", "-C", "/repo", "merge-base", "--is-ancestor", COMMIT, "origin/staging"])
        self.assertEqual(branch_problems(COMMIT, "master", "/repo", runner=elsewhere), [f"{COMMIT} is not on origin/master; the tag must point to that branch"])

    def test_publish_environment_needs_a_reviewer_and_a_deployment_policy(self):
        """An environment GitHub created on first use, without rules, fails on both counts."""
        protected = {"name": "maven-release", "protection_rules": [REVIEWERS, {"type": "branch_policy"}], "deployment_branch_policy": TAG_POLICY}
        self.assertEqual(environment_problems(protected), [])
        created_on_first_use = {"name": "maven-release", "protection_rules": [], "deployment_branch_policy": None}
        self.assertEqual(environment_problems(created_on_first_use), [
            "environment maven-release does not require a reviewer to approve the publish job",
            "environment maven-release does not limit deployments to selected branches and tags",
        ])

    def test_protected_branches_only_does_not_count_as_a_tag_rule(self):
        """A release runs on a tag, which a protected-branches-only policy refuses after the build."""
        environment = {"name": "maven-release", "protection_rules": [REVIEWERS], "deployment_branch_policy": {"protected_branches": True, "custom_branch_policies": False}}
        self.assertEqual(environment_problems(environment), ["environment maven-release does not limit deployments to selected branches and tags"])

    def test_a_reviewer_rule_without_reviewers_does_not_count(self):
        """A reviewer rule whose list is empty, or a wait timer alone, approves nothing."""
        environment = {"name": "maven-release", "protection_rules": [{"type": "required_reviewers", "reviewers": []}, {"type": "wait_timer", "wait_timer": 5}], "deployment_branch_policy": TAG_POLICY}
        self.assertEqual(environment_problems(environment), ["environment maven-release does not require a reviewer to approve the publish job"])


if __name__ == "__main__":
    unittest.main()
