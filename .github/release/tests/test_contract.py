import tempfile
import unittest
from pathlib import Path

from contract import actual_contract, compare, max_class_major, module_dependency_problems, summary_rows
from plan import plan_for_tag
from tests.builders import jar_bytes, module_bytes, pom_bytes, write_artifact
from tests.support import repository_config

JSON = "org.json:json:20250517"
JSR305 = "com.google.code.findbugs:jsr305:3.0.2"
JAVA_CONTRACT = {
    "schema": 1,
    "coordinates": "ly.count.sdk:java",
    "pomDependencies": [JSR305 + ":runtime", JSON + ":runtime"],
    "moduleVariantAttributes": {"org.gradle.jvm.version": 8},
    "maxClassFileMajor": 52,
}


class ContractTest(unittest.TestCase):
    def setUp(self):
        self.config = repository_config()
        self.staging = Path(tempfile.mkdtemp())
        self.java = plan_for_tag(self.config, "26.8.1").artifacts[0]

    def stage_java(self, majors=(52,), jvm_version=8, pom_dependencies=(JSON + ":runtime", JSR305 + ":runtime"), module_dependencies=(JSON, JSR305)):
        """Stages java 26.8.1 with the given contents and returns the contract it actually fulfils."""
        write_artifact(self.staging, self.java, "26.8.1", main=jar_bytes(list(majors)), pom=pom_bytes(list(pom_dependencies)), module=module_bytes(jvm_version, module_dependencies))
        return actual_contract(self.staging, self.java, "26.8.1")

    def test_actual_contract_has_the_contract_file_shape(self):
        self.assertEqual(self.stage_java(), JAVA_CONTRACT)

    def test_matching_contract(self):
        self.assertEqual(compare(JAVA_CONTRACT, self.stage_java()), [])
        self.assertEqual(module_dependency_problems(self.staging, self.java, "26.8.1"), [])

    def test_regressions_are_reported(self):
        actual = self.stage_java(majors=(52, 61), jvm_version=17, pom_dependencies=[JSON + ":compile"])
        problems = compare(JAVA_CONTRACT, actual)
        joined = "\n".join(problems)
        self.assertEqual(len(problems), 3, joined)
        for fragment in ["POM dependencies", "org.gradle.jvm.version is 17", "major version 61"]:
            self.assertIn(fragment, joined)

    def test_dependency_only_in_the_module_file_is_reported(self):
        self.stage_java(module_dependencies=(JSON, JSR305, "org.slf4j:slf4j-api:2.0.17"))
        self.assertEqual(module_dependency_problems(self.staging, self.java, "26.8.1"), [
            f"the .module declares ['{JSR305}', '{JSON}', 'org.slf4j:slf4j-api:2.0.17'] but the POM declares ['{JSR305}', '{JSON}']",
        ])

    def test_the_core_version_of_the_ui_is_compared_exactly(self):
        ui = plan_for_tag(self.config, "ui-26.8.1").artifacts[0]
        contract = {"schema": 1, "coordinates": "ly.count.sdk:java-ui", "pomDependencies": ["ly.count.sdk:java:26.8.0:compile", JSON + ":runtime"], "moduleVariantAttributes": {"org.gradle.jvm.version": 17}, "maxClassFileMajor": 61}
        write_artifact(self.staging, ui, "26.8.1", main=jar_bytes([61]), pom=pom_bytes(["ly.count.sdk:java:26.8.1:compile", JSON + ":runtime"]), module=module_bytes(17, ["ly.count.sdk:java:26.8.1", JSON]))
        actual = actual_contract(self.staging, ui, "26.8.1")
        self.assertEqual(actual["pomDependencies"], ["ly.count.sdk:java:26.8.1:compile", JSON + ":runtime"])
        self.assertEqual(compare(contract, actual), [f"POM dependencies are ['ly.count.sdk:java:26.8.1:compile', '{JSON}:runtime'], the contract says ['ly.count.sdk:java:26.8.0:compile', '{JSON}:runtime']"])

    def test_highest_class_version_wins(self):
        self.assertEqual(max_class_major(jar_bytes([52, 50])), 52)

    def test_summary_rows(self):
        rows = summary_rows(JAVA_CONTRACT, self.stage_java())
        self.assertIn(("org.gradle.jvm.version", "8", "8"), rows)
        self.assertIn(("highest class file version", "at most 52", "52"), rows)


if __name__ == "__main__":
    unittest.main()
