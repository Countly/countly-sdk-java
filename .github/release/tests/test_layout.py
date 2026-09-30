import tempfile
import unittest
from pathlib import Path

from layout import check_staging, expected_files, primary_files, remove_index_files, write_checksums
from plan import plan_for_tag
from tests.builders import write_artifact
from tests.support import repository_config


class LayoutTest(unittest.TestCase):
    def setUp(self):
        self.config = repository_config()
        self.staging = Path(tempfile.mkdtemp())
        self.plan = plan_for_tag(self.config, "26.8.1")
        self.ui_plan = plan_for_tag(self.config, "ui-26.8.1")

    def test_expected_files_of_a_jar(self):
        ui = self.ui_plan.artifacts[0]
        self.assertEqual(primary_files(ui, "26.8.1"), ["java-ui-26.8.1.jar", "java-ui-26.8.1.pom", "java-ui-26.8.1.module", "java-ui-26.8.1-sources.jar", "java-ui-26.8.1-javadoc.jar", "java-ui-26.8.1-cyclonedx.json"])
        self.assertEqual(len(expected_files(ui, "26.8.1")), 30)

    def test_checksums_are_plain_hex(self):
        path = self.staging / "a.txt"
        path.write_bytes(b"abc")
        write_checksums(path)
        self.assertEqual((self.staging / "a.txt.md5").read_bytes(), b"900150983cd24fb0d6963f7d28e17f72")
        self.assertEqual((self.staging / "a.txt.sha1").read_bytes(), b"a9993e364706816aba3e25717850c26c9cd0d89d")

    def test_complete_staging_passes(self):
        for plan in (self.plan, self.ui_plan):
            with self.subTest(tag=plan.tag):
                staging = Path(tempfile.mkdtemp())
                for artifact in plan.artifacts:
                    write_artifact(staging, artifact, plan.version)
                self.assertEqual(check_staging(staging, plan), [])

    def test_a_tag_stages_only_its_own_artifact(self):
        write_artifact(self.staging, self.ui_plan.artifacts[0], self.ui_plan.version)
        write_artifact(self.staging, self.plan.artifacts[0], self.plan.version)
        problems = check_staging(self.staging, self.ui_plan)
        self.assertEqual(len(problems), 30)
        self.assertIn("unexpected ly/count/sdk/java/26.8.1/java-26.8.1.jar", problems)

    def test_missing_unexpected_and_wrong_files_are_reported(self):
        folder = write_artifact(self.staging, self.plan.artifacts[0], self.plan.version)
        (folder / "java-26.8.1-javadoc.jar").unlink()
        (folder / "notes.txt").write_bytes(b"x")
        (folder / "java-26.8.1.pom.sha1").write_bytes(b"0" * 40)
        (self.staging / "ly/count/sdk/other").mkdir(parents=True)
        (self.staging / "ly/count/sdk/other/x.jar").write_bytes(b"x")
        self.assertEqual(check_staging(self.staging, self.plan), [
            "missing ly/count/sdk/java/26.8.1/java-26.8.1-javadoc.jar",
            "unexpected ly/count/sdk/java/26.8.1/notes.txt",
            "unexpected ly/count/sdk/other/x.jar",
            "wrong checksum ly/count/sdk/java/26.8.1/java-26.8.1.pom.sha1",
        ])

    def test_index_files_are_removed(self):
        path = self.staging / "ly/count/sdk/java/maven-metadata.xml.sha1"
        path.parent.mkdir(parents=True)
        path.write_bytes(b"x")
        remove_index_files(self.staging)
        self.assertFalse(path.exists())


if __name__ == "__main__":
    unittest.main()
