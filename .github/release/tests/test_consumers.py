import tempfile
import unittest
from pathlib import Path

from consumers import gradle_command, maven_command, maven_source_problems, smoke_command
from plan import plan_for_tag
from tests.support import repository_config

PUBLIC = "https://maven.countly.com"


class ConsumersTest(unittest.TestCase):
    def setUp(self):
        config = repository_config()
        self.core_plan = plan_for_tag(config, "26.9.0")
        self.ui_plan = plan_for_tag(config, "ui-26.8.1")
        self.java = self.core_plan.artifacts[0]
        self.ui = self.ui_plan.artifacts[0]

    def test_gradle_takes_only_the_staged_artifacts_from_the_staging_folder(self):
        command = gradle_command("/repo/gradlew", "file:///staging", PUBLIC, self.ui_plan, self.ui)
        self.assertEqual(command[:3], ["/repo/gradlew", "-p", ".github/release/consumers/gradle"])
        for part in ["installDist", "-PcountlyRepository=file:///staging", f"-PcountlyPublicRepository={PUBLIC}", "-PcountlyStaged=ly.count.sdk:java-ui", "-PcountlyDependency=ly.count.sdk:java-ui:26.8.1", "-PcountlyRelease=17", "-PcountlyProbe=java-ui"]:
            self.assertIn(part, command)
        core = gradle_command("/repo/gradlew", "file:///staging", PUBLIC, self.core_plan, self.java)
        for part in ["-PcountlyStaged=ly.count.sdk:java", "-PcountlyDependency=ly.count.sdk:java:26.9.0", "-PcountlyRelease=8"]:
            self.assertIn(part, core)

    def test_maven_uses_an_empty_local_repository_and_the_public_repository(self):
        command = maven_command("mvn", "file:///staging", PUBLIC, self.java, "26.9.0", "/work/m2-java")
        self.assertEqual(command[:4], ["mvn", "-B", "-f", ".github/release/consumers/maven/pom.xml"])
        for part in [f"-Dcountly.publicRepository={PUBLIC}", "-Dcountly.artifact=java", "-Dcountly.version=26.9.0", "-Dcountly.release=8", "-Dmaven.repo.local=/work/m2-java"]:
            self.assertIn(part, command)

    def test_smoke_run_uses_the_given_runtime_and_the_installed_classpath(self):
        command = smoke_command("/jdk8", "/repo")
        self.assertEqual(Path(command[0]), Path("/jdk8/bin/java"))
        self.assertEqual(Path(command[2]), Path("/repo/.github/release/consumers/gradle/build/install/countly-consumer/lib/*"))
        self.assertEqual(command[3], "ly.count.consumer.Probe")

    def write_record(self, local, artifact, version, text):
        """Writes Maven's download record for one artifact version into the local repository."""
        folder = Path(local) / artifact.folder(version)
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "_remote.repositories").write_text(text, encoding="utf-8")

    def test_maven_must_resolve_the_release_from_the_staged_folder(self):
        local = tempfile.mkdtemp()
        self.assertEqual(maven_source_problems(local, self.core_plan, self.java), ["Maven did not download ly.count.sdk:java:26.9.0"])
        self.write_record(local, self.java, "26.9.0", "#NOTE: This is a Maven Resolver internal implementation file\njava-26.9.0.jar>countly=\njava-26.9.0.pom>countly=\n")
        self.assertEqual(maven_source_problems(local, self.core_plan, self.java), [])
        self.write_record(local, self.ui, "26.8.1", "java-ui-26.8.1.jar>central=\njava-ui-26.8.1.pom>countly=\n")
        self.assertEqual(maven_source_problems(local, self.ui_plan, self.ui), ["Maven resolved ly.count.sdk:java-ui:26.8.1 from another repository than the staged release"])

    def test_the_core_of_a_ui_release_may_come_from_anywhere(self):
        local = tempfile.mkdtemp()
        self.write_record(local, self.ui, "26.8.1", "java-ui-26.8.1.jar>countly=\njava-ui-26.8.1.pom>countly=\n")
        self.write_record(local, self.java, "26.8.0", "java-26.8.0.jar>central=\njava-26.8.0.pom>central=\n")
        self.assertEqual(maven_source_problems(local, self.ui_plan, self.ui), [])


if __name__ == "__main__":
    unittest.main()
