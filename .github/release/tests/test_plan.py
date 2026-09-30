import tempfile
import unittest
from pathlib import Path

from plan import PlanError, checkout_tags, plan_for_tag, plan_from_json
from tests.support import repository_config

JAVA = ["ly.count.sdk:java"]
UI = ["ly.count.sdk:java-ui"]


class PlanTest(unittest.TestCase):
    def setUp(self):
        self.config = repository_config()

    def test_tags(self):
        cases = [
            ("26.8.1", JAVA, "26.8.1", True),
            ("26.8.1-rc1", JAVA, "26.8.1-rc1", False),
            ("0.0.1", JAVA, "0.0.1", True),
            ("ui-26.8.1", UI, "26.8.1", True),
            ("ui-26.8.1-rc1", UI, "26.8.1-rc1", False),
            ("ui-0.0.1", UI, "0.0.1", True),
        ]
        for tag, artifacts, version, listed in cases:
            with self.subTest(tag=tag):
                plan = plan_for_tag(self.config, tag)
                self.assertEqual(plan.branch, "staging")
                self.assertEqual([artifact.coordinates for artifact in plan.artifacts], artifacts)
                self.assertEqual(plan.version, version)
                self.assertEqual(plan.listed, listed)
                self.assertEqual(plan.prerelease, not listed)

    def test_refused_tags(self):
        refused = [
            "v26.8.1", "26.8", "26.8.1-RC1", "26.8.1-rc.1", " 26.8.1", "26.8.1-nw", "native-26.8.1", "plugin-26.8.1",
            "26.8.1/../x", "java-26.8.1", "٢٦.8.1", "ui-26.8", "ui-v26.8.1", "UI-26.8.1", "ui26.8.1",
            "java-ui-26.8.1", "26.8.1-ui", "ui-ui-26.8.1",
        ]
        for tag in refused:
            with self.subTest(tag=tag):
                with self.assertRaises(PlanError):
                    plan_for_tag(self.config, tag)

    def test_modules_and_round_trip(self):
        core = plan_for_tag(self.config, "26.8.1")
        ui = plan_for_tag(self.config, "ui-26.8.1")
        self.assertEqual(core.modules, [":sdk-java"])
        self.assertEqual(ui.modules, [":sdk-java-ui"])
        self.assertEqual(ui.artifacts[0].module_dir, "sdk-java-ui")
        self.assertEqual(ui.artifacts[0].folder("26.8.1"), "ly/count/sdk/java-ui/26.8.1")
        for plan in (core, ui):
            self.assertEqual(plan_from_json(self.config, plan.to_json()), plan)

    def test_artifact_details(self):
        java = plan_for_tag(self.config, "26.8.1").artifacts[0]
        ui = plan_for_tag(self.config, "ui-26.8.1").artifacts[0]
        self.assertEqual((java.contract, java.consumer_java), (".github/release-contract/java.json", 8))
        self.assertEqual((ui.contract, ui.consumer_java), (".github/release-contract/java-ui.json", 17))

    def test_checkout_tags_follow_each_version_file(self):
        root = Path(tempfile.mkdtemp())
        (root / "gradle.properties").write_bytes(b"VERSION_NAME=26.9.0-rc1\r\nGROUP=ly.count.sdk\r\n")
        (root / "sdk-java-ui").mkdir()
        (root / "sdk-java-ui/gradle.properties").write_bytes(b"POM_ARTIFACT_ID=java-ui\r\nVERSION_NAME=26.8.0\r\n")
        self.assertEqual(checkout_tags(self.config, root, "staging"), ["26.9.0-rc1", "ui-26.8.0"])
        self.assertEqual(checkout_tags(self.config, root, "master"), [])

    def test_checkout_tags_need_a_readable_version(self):
        root = Path(tempfile.mkdtemp())
        with self.assertRaises(PlanError):
            checkout_tags(self.config, root, "staging")
        (root / "gradle.properties").write_bytes(b"VERSION_NAME=26.9.0\n")
        with self.assertRaises(PlanError):
            checkout_tags(self.config, root, "staging")
        (root / "sdk-java-ui").mkdir()
        (root / "sdk-java-ui/gradle.properties").write_bytes(b"POM_ARTIFACT_ID=java-ui\n")
        with self.assertRaises(PlanError):
            checkout_tags(self.config, root, "staging")


if __name__ == "__main__":
    unittest.main()
