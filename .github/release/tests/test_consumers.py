import tempfile
import unittest
from pathlib import Path

from consumers import gradle_command, maven_command, maven_source_problems, smoke_command
from plan import plan_for_tag
from tests.support import repository_config


class ConsumersTest(unittest.TestCase):
    def setUp(self):
        self.plan = plan_for_tag(repository_config(), "26.8.1")
        self.java, self.ui = self.plan.artifacts

    def test_gradle_builds_each_artifact_at_its_java_level(self):
        command = gradle_command("/repo/gradlew", "file:///staging", self.ui, "26.8.1")
        self.assertEqual(command[:3], ["/repo/gradlew", "-p", ".github/release/consumers/gradle"])
        for part in ["installDist", "-PcountlyRepository=file:///staging", "-PcountlyDependency=ly.count.sdk:java-ui:26.8.1", "-PcountlyRelease=17", "-PcountlyProbe=java-ui"]:
            self.assertIn(part, command)
        self.assertIn("-PcountlyRelease=8", gradle_command("/repo/gradlew", "file:///staging", self.java, "26.8.1"))

    def test_maven_uses_an_empty_local_repository(self):
        command = maven_command("mvn", "file:///staging", self.java, "26.8.1", "/work/m2-java")
        self.assertEqual(command[:4], ["mvn", "-B", "-f", ".github/release/consumers/maven/pom.xml"])
        for part in ["-Dcountly.artifact=java", "-Dcountly.version=26.8.1", "-Dcountly.release=8", "-Dmaven.repo.local=/work/m2-java"]:
            self.assertIn(part, command)

    def test_smoke_run_uses_the_given_runtime_and_the_installed_classpath(self):
        command = smoke_command("/jdk8", "/repo")
        self.assertEqual(Path(command[0]), Path("/jdk8/bin/java"))
        self.assertEqual(Path(command[2]), Path("/repo/.github/release/consumers/gradle/build/install/countly-consumer/lib/*"))
        self.assertEqual(command[3], "ly.count.consumer.Probe")

    def write_record(self, local, artifact, text):
        """Writes Maven's download record for one artifact version into the local repository."""
        folder = Path(local) / artifact.folder("26.8.1")
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "_remote.repositories").write_text(text, encoding="utf-8")

    def test_maven_must_resolve_from_the_staged_release(self):
        local = tempfile.mkdtemp()
        self.assertEqual(maven_source_problems(local, self.plan, self.java), ["Maven did not download ly.count.sdk:java:26.8.1"])
        self.write_record(local, self.java, "#NOTE: This is a Maven Resolver internal implementation file\njava-26.8.1.jar>countly=\njava-26.8.1.pom>countly=\n")
        self.assertEqual(maven_source_problems(local, self.plan, self.java), [])
        self.write_record(local, self.ui, "java-ui-26.8.1.jar>central=\njava-ui-26.8.1.pom>countly=\n")
        self.assertEqual(maven_source_problems(local, self.plan, self.ui), ["Maven resolved ly.count.sdk:java-ui:26.8.1 from another repository than the staged release"])


if __name__ == "__main__":
    unittest.main()
