"""Commands for the clean projects in .github/release/consumers that build against a staged release."""

from pathlib import Path

GRADLE_PROJECT = ".github/release/consumers/gradle"
MAVEN_POM = ".github/release/consumers/maven/pom.xml"
GRADLE_FLAGS = ["--no-daemon", "--no-configuration-cache", "--stacktrace"]
PROBE_CLASS = "ly.count.consumer.Probe"
REPOSITORY_ID = "countly"


def gradle_command(gradlew, repository, public_repository, plan, artifact):
    """Builds the Gradle consumer against one artifact of the plan and installs it with its runtime classpath. Only the
    plan's artifacts come from the staging folder; any other Countly package they need, such as the core SDK of a UI
    release, comes from the public repository or Maven Central, as it does for an integrator."""
    staged = ",".join(item.coordinates for item in plan.artifacts)
    return [
        gradlew, "-p", GRADLE_PROJECT, *GRADLE_FLAGS, "clean", "installDist",
        f"-PcountlyRepository={repository}", f"-PcountlyPublicRepository={public_repository}", f"-PcountlyStaged={staged}",
        f"-PcountlyDependency={artifact.coordinates}:{plan.version}",
        f"-PcountlyRelease={artifact.consumer_java}", f"-PcountlyProbe={artifact.artifact}",
    ]


def maven_command(mvn, repository, public_repository, artifact, version, local_repository):
    """Compiles the Maven consumer against one artifact version, with an empty local repository so nothing is reused.
    Maven looks in the staging folder first, then in the public repository, then on Maven Central."""
    return [
        mvn, "-B", "-f", MAVEN_POM, "clean", "compile",
        f"-Dcountly.repository={repository}", f"-Dcountly.publicRepository={public_repository}",
        f"-Dcountly.artifact={artifact.artifact}", f"-Dcountly.version={version}",
        f"-Dcountly.release={artifact.consumer_java}", f"-Dmaven.repo.local={local_repository}",
    ]


def smoke_command(java_home, repo_root):
    """Runs the probe the Gradle consumer installed, on the given Java runtime, with the installed runtime classpath."""
    java = Path(java_home) / "bin" / "java"
    classpath = Path(repo_root) / GRADLE_PROJECT / "build/install/countly-consumer/lib/*"
    return [str(java), "-cp", str(classpath), PROBE_CLASS]


def maven_source_problems(local_repository, plan, consumed):
    """Problems when Maven did not take the consumed artifact, and every other artifact of the plan it downloaded, from
    the staged release; Maven records the source repository of every downloaded file in _remote.repositories."""
    problems = []
    for artifact in plan.artifacts:
        record = Path(local_repository) / artifact.folder(plan.version) / "_remote.repositories"
        if not record.is_file():
            if artifact == consumed:
                problems.append(f"Maven did not download {artifact.coordinates}:{plan.version}")
            continue
        sources = [line.split(">", 1)[1] for line in record.read_text(encoding="utf-8").splitlines() if ">" in line and not line.startswith("#")]
        if not sources or any(source != f"{REPOSITORY_ID}=" for source in sources):
            problems.append(f"Maven resolved {artifact.coordinates}:{plan.version} from another repository than the staged release")
    return problems
