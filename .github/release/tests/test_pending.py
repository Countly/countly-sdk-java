import subprocess
import tempfile
import unittest
from pathlib import Path

from pending import merge_problems, pending_releases
from plan import PlanError
from tests.support import repository_config, write_core, write_ui

HEAD = "a" * 40
MERGE = "b" * 40
RELEASED = {"26.8.0", "ui-26.8.0"}


def git_runner(contains_head, trees):
    """A stand-in for subprocess.run answering the two git questions of merge_problems: whether the merge contains the
    head, and the tree of each commit."""
    def run(args, **kwargs):
        if "merge-base" in args:
            return subprocess.CompletedProcess(args, 0 if contains_head else 1)
        return subprocess.CompletedProcess(args, 0, stdout="".join(trees[arg.split("^")[0]] + "\n" for arg in args[-2:]))
    return run


class PendingTest(unittest.TestCase):
    def setUp(self):
        self.config = repository_config()
        self.root = Path(tempfile.mkdtemp())

    def checkout(self, core, ui, changelog, ui_changelog, gradle=None):
        """Writes the version sources and the changelogs of the core and the UI."""
        write_core(self.root, core, gradle or core, core, changelog)
        write_ui(self.root, ui, ui_changelog)

    def test_new_versions_are_released_core_first_with_their_changelog_sections_and_only_the_core_is_latest(self):
        self.checkout("26.9.0", "26.9.0", "## 26.9.0\n\n* Core change.\n  with a second line\n\n## 26.8.0\n* Old.\n", "## 26.9.0\n* UI change.\n")
        releases, problems = pending_releases(self.config, self.root, "staging", RELEASED)
        self.assertEqual(problems, [])
        self.assertEqual([(release.tag, release.latest, release.prerelease) for release in releases], [("26.9.0", True, False), ("ui-26.9.0", False, False)])
        self.assertEqual([release.notes for release in releases], ["* Core change.\n  with a second line\n", "* UI change.\n"])
        self.assertEqual(releases[1].to_json(), {"tag": "ui-26.9.0", "prerelease": False, "latest": False})

    def test_tagged_versions_are_skipped_so_an_unchanged_ui_or_a_repeated_merge_releases_nothing_more(self):
        self.checkout("26.9.0", "26.8.0", "## 26.9.0\n* Core change.\n", "## 26.8.0\n* Old UI.\n")
        releases, problems = pending_releases(self.config, self.root, "staging", RELEASED)
        self.assertEqual(([release.tag for release in releases], problems), (["26.9.0"], []))
        self.assertEqual(pending_releases(self.config, self.root, "staging", RELEASED | {"26.9.0"}), ([], []))

    def test_a_changelog_or_a_version_source_not_ready_is_reported_for_every_release(self):
        self.checkout("26.9.0", "26.9.0", "## XX.XX.XX\n* Still waiting.\n\n## 26.9.0\n* Core change.\n", "## 26.8.0\n", gradle="26.8.0")
        _, problems = pending_releases(self.config, self.root, "staging", RELEASED)
        self.assertEqual(problems, [
            "build.gradle: version is 26.8.0, the tag says 26.9.0",
            "CHANGELOG.md: still has a '## XX.XX.XX' heading; move its lines under '## 26.9.0'",
            "sdk-java-ui/CHANGELOG.md: no '## 26.9.0' heading",
        ])

    def test_a_release_candidate_is_a_prerelease_never_latest_with_the_placeholder_section_as_notes(self):
        self.checkout("26.9.0-rc1", "26.8.0", "## XX.XX.XX\n* Coming soon.\n\n## 26.8.0\n* Old.\n", "## 26.8.0\n")
        releases, problems = pending_releases(self.config, self.root, "staging", RELEASED)
        self.assertEqual(problems, [])
        self.assertEqual([(release.tag, release.latest, release.prerelease, release.notes) for release in releases], [("26.9.0-rc1", False, True, "* Coming soon.\n")])

    def test_a_release_candidate_falls_back_to_its_version_section_and_needs_one_of_them(self):
        self.checkout("26.9.0-rc1", "26.8.0", "## 26.9.0-rc1\n* Ready early.\n", "## 26.8.0\n")
        releases, problems = pending_releases(self.config, self.root, "staging", RELEASED)
        self.assertEqual(([release.notes for release in releases], problems), (["* Ready early.\n"], []))
        self.checkout("26.9.0-rc1", "26.8.0", "## 26.8.0\n* Old.\n", "## 26.8.0\n")
        _, problems = pending_releases(self.config, self.root, "staging", RELEASED)
        self.assertEqual(problems, ["CHANGELOG.md: no '## 26.9.0-rc1' heading"])

    def test_a_version_outside_the_tag_grammar_stops_the_plan(self):
        self.checkout("XX.XX.XX", "26.8.0", "## XX.XX.XX\n", "## 26.8.0\n")
        with self.assertRaises(PlanError):
            pending_releases(self.config, self.root, "staging", RELEASED)

    def test_the_merge_must_contain_the_head_and_hold_exactly_its_tree(self):
        self.assertEqual(merge_problems(HEAD, MERGE, "/repo", runner=git_runner(True, {HEAD: "tree", MERGE: "tree"})), [])
        self.assertEqual(merge_problems(HEAD, MERGE, "/repo", runner=git_runner(False, {})),
                         [f"{MERGE} does not contain {HEAD}; merge the release PR with a merge commit, not a squash or a rebase"])
        self.assertEqual(merge_problems(HEAD, MERGE, "/repo", runner=git_runner(True, {HEAD: "tree", MERGE: "other"})),
                         ["master holds changes staging lacks; merge master into staging, then update the release PR"])


if __name__ == "__main__":
    unittest.main()
