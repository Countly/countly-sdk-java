import hashlib
import subprocess
import tempfile
import unittest
from pathlib import Path

from plan import plan_for_tag
from tests.support import FakeWeb, repository_config
from verify import verify_public, verify_signatures

BASE = "https://maven.test/"
POM = "ly/count/sdk/java/26.8.1/java-26.8.1.pom"


class VerifyTest(unittest.TestCase):
    def setUp(self):
        self.config = repository_config()
        self.plan = plan_for_tag(self.config, "26.8.1")
        self.work = Path(tempfile.mkdtemp())
        self.manifest = {"tag": "26.8.1", "commit": "c" * 40, "files": [
            {"path": POM, "size": 3, "sha256": hashlib.sha256(b"pom").hexdigest()},
            {"path": POM + ".sha1", "size": 4, "sha256": hashlib.sha256(b"sha1").hexdigest()},
        ]}
        self.web = FakeWeb({
            BASE + POM: b"pom",
            BASE + POM + ".sha1": b"sha1",
            BASE + POM + ".asc": b"signature",
            BASE + "ly/count/sdk/java/maven-metadata.xml": b"<version>26.8.1</version>",
        })

    def test_published_release_passes_and_keeps_the_signatures(self):
        self.assertEqual(verify_public(self.manifest, self.plan, BASE, "1", self.work, fetcher=self.web), [])
        self.assertEqual((self.work / (POM + ".asc")).read_bytes(), b"signature")
        self.assertTrue(all("?nocache=1" in url for url in self.web.requests))

    def test_problems_are_reported(self):
        self.web.pages[BASE + POM] = b"changed"
        del self.web.pages[BASE + POM + ".asc"]
        self.web.pages[BASE + "ly/count/sdk/java/maven-metadata.xml"] = b"<version>26.8.0</version>"
        self.assertEqual(verify_public(self.manifest, self.plan, BASE, "1", self.work, fetcher=self.web), [
            f"changed {POM}",
            f"missing {POM}.asc",
            "ly.count.sdk:java index does not list 26.8.1",
        ])

    def test_a_ui_release_checks_only_the_ui_index(self):
        plan = plan_for_tag(self.config, "ui-26.8.1")
        web = FakeWeb({BASE + "ly/count/sdk/java-ui/maven-metadata.xml": b"<version>26.8.1</version>"})
        self.assertEqual(verify_public({"files": []}, plan, BASE, "1", self.work, fetcher=web), [])
        self.assertEqual(web.requests, [BASE + "ly/count/sdk/java-ui/maven-metadata.xml?nocache=1"])

    def test_candidate_must_stay_unlisted(self):
        plan = plan_for_tag(self.config, "26.8.1-rc1")
        web = FakeWeb({BASE + "ly/count/sdk/java/maven-metadata.xml": b"<version>26.8.1-rc1</version>"})
        self.assertEqual(verify_public({"files": []}, plan, BASE, "1", self.work, fetcher=web), ["ly.count.sdk:java index lists the release candidate 26.8.1-rc1"])

    def test_bad_signatures_are_reported(self):
        (self.work / "a").mkdir()
        for name in ["x.pom", "x.pom.asc", "y.pom", "y.pom.asc"]:
            (self.work / "a" / name).write_bytes(b"x")

        def fake_gpg(args, **kwargs):
            bad = "--verify" in args and args[-1].endswith("y.pom")
            return subprocess.CompletedProcess(args, 1 if bad else 0, b"", b"")

        self.assertEqual(verify_signatures(self.work, Path("key.asc"), runner=fake_gpg), ["bad signature a/y.pom.asc"])


if __name__ == "__main__":
    unittest.main()
