import re
import tempfile
import unittest
from pathlib import Path

from plan import plan_for_tag
from sources import changelog_section, check_version_sources
from tests.support import repository_config, write_core, write_file


class SourcesTest(unittest.TestCase):
    def setUp(self):
        self.config = repository_config()
        self.root = Path(tempfile.mkdtemp())

    def write(self, relative, text):
        """Writes a file of the fake checkout."""
        write_file(self.root, relative, text)

    def write_sdk(self, properties_version, gradle_version, java_version, changelog):
        """Writes the three version sources and the changelog."""
        write_core(self.root, properties_version, gradle_version, java_version, changelog)

    def test_matching_sources(self):
        self.write_sdk("26.8.1", "26.8.1", "26.8.1", "## 26.8.1\n* Fixed things.\n")
        self.assertEqual(check_version_sources(self.root, plan_for_tag(self.config, "26.8.1"), self.config), [])

    def test_mismatches_are_reported_once(self):
        self.write_sdk("26.8.1", "26.8.0", "26.8.2", "## XX.XX.XX\n")
        problems = check_version_sources(self.root, plan_for_tag(self.config, "26.8.1"), self.config)
        self.assertEqual(problems, [
            "build.gradle: version is 26.8.0, the tag says 26.8.1",
            "sdk-java/src/main/java/ly/count/sdk/java/Config.java: version is 26.8.2, the tag says 26.8.1",
            "CHANGELOG.md: no '## 26.8.1' heading",
            "CHANGELOG.md: still has a '## XX.XX.XX' heading; move its lines under '## 26.8.1'",
        ])

    def test_candidates_need_no_changelog_heading(self):
        self.write_sdk("26.8.1-rc1", "26.8.1-rc1", "26.8.1-rc1", "## XX.XX.XX\n")
        self.assertEqual(check_version_sources(self.root, plan_for_tag(self.config, "26.8.1-rc1"), self.config), [])

    def test_windows_line_endings_are_accepted(self):
        self.write_sdk("26.8.1\r", "26.8.1", "26.8.1", "## 26.8.1\r\n")
        self.assertEqual(check_version_sources(self.root, plan_for_tag(self.config, "26.8.1"), self.config), [])

    def test_ui_uses_its_own_version_and_changelog(self):
        self.write_sdk("26.9.0", "26.9.0", "26.9.0", "## 26.9.0\n")
        self.write("sdk-java-ui/gradle.properties", "POM_ARTIFACT_ID=java-ui\nVERSION_NAME=26.8.1\n")
        self.write("sdk-java-ui/CHANGELOG.md", "## 26.8.1\n* Fixed the survey card.\n")
        self.assertEqual(check_version_sources(self.root, plan_for_tag(self.config, "ui-26.8.1"), self.config), [])
        self.assertEqual(check_version_sources(self.root, plan_for_tag(self.config, "26.9.0"), self.config), [])

    def test_ui_mismatches_are_reported(self):
        self.write("sdk-java-ui/gradle.properties", "POM_ARTIFACT_ID=java-ui\nVERSION_NAME=26.8.0\n")
        self.write("sdk-java-ui/CHANGELOG.md", "## 26.8.0\n")
        self.assertEqual(check_version_sources(self.root, plan_for_tag(self.config, "ui-26.8.1"), self.config), [
            "sdk-java-ui/gradle.properties: version is 26.8.0, the tag says 26.8.1",
            "sdk-java-ui/CHANGELOG.md: no '## 26.8.1' heading",
        ])

    def test_a_changelog_section_ends_at_the_next_heading_and_keeps_its_lines(self):
        text = "## XX.XX.XX\n\n* New.\n  ```groovy\n  code\n  ```\n\n## 26.8.0\n* Old.\n"
        self.assertEqual(changelog_section(text, "XX.XX.XX"), "* New.\n  ```groovy\n  code\n  ```\n")
        self.assertEqual(changelog_section(text, "26.8.0"), "* Old.\n")
        self.assertIsNone(changelog_section(text, "26.9.0"))
        fenced = "## 26.9.0\r\n* Add the repository:\r\n```markdown\r\n## Not a release\r\n```\r\n## 26.8.0\r\n"
        self.assertEqual(changelog_section(fenced, "26.9.0"), "* Add the repository:\n```markdown\n## Not a release\n```\n")

    def test_a_missing_changelog_is_reported(self):
        self.write_sdk("26.9.0", "26.9.0", "26.9.0", "")
        (self.root / "CHANGELOG.md").unlink()
        self.assertEqual(check_version_sources(self.root, plan_for_tag(self.config, "26.9.0"), self.config), ["CHANGELOG.md does not exist"])

    def test_every_version_source_is_found_in_this_checkout(self):
        root = Path(__file__).resolve().parents[3]
        for spec in self.config["artifacts"].values():
            for source in spec["versionSources"]:
                with self.subTest(file=source["file"]):
                    text = (root / source["file"]).read_text(encoding="utf-8")
                    self.assertIsNotNone(re.search(source["regex"], text, re.MULTILINE))


if __name__ == "__main__":
    unittest.main()
