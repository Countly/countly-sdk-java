import re
import tempfile
import unittest
from pathlib import Path

from plan import plan_for_tag
from sources import check_version_sources
from tests.support import repository_config

BUILD_GRADLE = 'allprojects {\n  ext.CLY_VERSION = "{version}"\n  ext.POWERMOCK_VERSION = "1.7.4"\n}\n'
CONFIG_JAVA = 'public class Config {\n    protected String sdkVersion = "{version}";\n}\n'


class SourcesTest(unittest.TestCase):
    def setUp(self):
        self.config = repository_config()
        self.root = Path(tempfile.mkdtemp())

    def write(self, relative, text):
        """Writes a file of the fake checkout."""
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(text.encode("utf-8"))

    def write_sdk(self, properties_version, gradle_version, java_version, changelog):
        """Writes the three version sources and the changelog."""
        self.write("gradle.properties", f"VERSION_NAME={properties_version}\nGROUP=ly.count.sdk\n")
        self.write("build.gradle", BUILD_GRADLE.replace("{version}", gradle_version))
        self.write("sdk-java/src/main/java/ly/count/sdk/java/Config.java", CONFIG_JAVA.replace("{version}", java_version))
        self.write("CHANGELOG.md", changelog)

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

    def test_every_version_source_is_found_in_this_checkout(self):
        root = Path(__file__).resolve().parents[3]
        for spec in self.config["artifacts"].values():
            for source in spec["versionSources"]:
                with self.subTest(file=source["file"]):
                    text = (root / source["file"]).read_text(encoding="utf-8")
                    self.assertIsNotNone(re.search(source["regex"], text, re.MULTILINE))


if __name__ == "__main__":
    unittest.main()
