import copy
import json
import tempfile
import unittest
from pathlib import Path

from plan import plan_for_tag
from sbom import SbomError, normalize, place_sbom
from tests.support import repository_config

PLUGIN_BOM = {
    "bomFormat": "CycloneDX",
    "specVersion": "1.6",
    "serialNumber": "urn:uuid:random",
    "metadata": {
        "timestamp": "2026-10-01T00:00:00Z",
        "component": {"type": "application", "bom-ref": "pkg:maven/ly.count.sdk/sdk-java-ui@26.8.1?type=jar", "group": "ly.count.sdk", "name": "sdk-java-ui", "version": "26.8.1"},
        "tools": {"components": [{"type": "application", "name": "cyclonedx-gradle-plugin"}]},
    },
    "components": [{"type": "library", "bom-ref": "pkg:maven/org.json/json@20250517?type=jar", "name": "json"}],
    "dependencies": [{"ref": "pkg:maven/ly.count.sdk/sdk-java-ui@26.8.1?type=jar", "dependsOn": ["pkg:maven/org.json/json@20250517?type=jar"]}],
}


class SbomTest(unittest.TestCase):
    def setUp(self):
        self.config = repository_config()
        self.java = plan_for_tag(self.config, "26.8.1").artifacts[0]
        self.ui = plan_for_tag(self.config, "ui-26.8.1").artifacts[0]

    def test_normalize_sets_the_published_coordinates(self):
        bom = normalize(copy.deepcopy(PLUGIN_BOM), self.ui, "26.8.1", "2026-10-27T10:15:00+03:00", "https://maven.countly.com/")
        ref = "pkg:maven/ly.count.sdk/java-ui@26.8.1?repository_url=https%3A%2F%2Fmaven.countly.com&type=jar"
        self.assertEqual(bom["metadata"]["component"], {"type": "library", "bom-ref": ref, "group": "ly.count.sdk", "name": "java-ui", "version": "26.8.1", "purl": ref, "licenses": [{"license": {"id": "MIT"}}]})
        self.assertEqual(bom["metadata"]["timestamp"], "2026-10-27T10:15:00+03:00")
        self.assertNotIn("serialNumber", bom)
        self.assertEqual(bom["dependencies"][0]["ref"], ref)

    def test_other_modules_of_the_build_get_their_published_coordinates(self):
        core = "pkg:maven/countly-sdk-java/sdk-java@unspecified?project_path=%3Asdk-java"
        bom = copy.deepcopy(PLUGIN_BOM)
        bom["components"].append({"type": "library", "bom-ref": core, "group": "countly-sdk-java", "name": "sdk-java", "version": "unspecified", "purl": core})
        bom["dependencies"][0]["dependsOn"].append(core)
        bom["dependencies"].append({"ref": core, "dependsOn": ["pkg:maven/org.json/json@20250517?type=jar"]})
        normalize(bom, self.ui, "26.8.1", "t", "https://maven.countly.com/", {":sdk-java": self.java, ":sdk-java-ui": self.ui})
        java_ref = "pkg:maven/ly.count.sdk/java@26.8.1?repository_url=https%3A%2F%2Fmaven.countly.com&type=jar"
        self.assertEqual(bom["components"][-1], {"type": "library", "bom-ref": java_ref, "group": "ly.count.sdk", "name": "java", "version": "26.8.1", "purl": java_ref})
        self.assertIn(java_ref, bom["dependencies"][0]["dependsOn"])
        self.assertEqual(bom["dependencies"][-1]["ref"], java_ref)
        self.assertNotIn("project_path", json.dumps(bom))

    def test_platform_specific_components_carry_no_hashes(self):
        bom = copy.deepcopy(PLUGIN_BOM)
        hashes = [{"alg": "SHA-256", "content": "c689de189abaa839eaf628d4b34aa176f6661e0d62ca918cb4af7544172e6a60"}]
        bom["components"][0]["hashes"] = copy.deepcopy(hashes)
        bom["components"].append({"type": "library", "bom-ref": "pkg:maven/org.openjfx/javafx-controls@21.0.5?type=jar", "group": "org.openjfx", "name": "javafx-controls", "version": "21.0.5", "hashes": copy.deepcopy(hashes), "purl": "pkg:maven/org.openjfx/javafx-controls@21.0.5?type=jar"})
        normalize(bom, self.ui, "26.8.1", "t", "https://maven.countly.com/", platform_groups=["org.openjfx"])
        self.assertEqual(bom["components"][0]["hashes"], hashes)
        self.assertNotIn("hashes", bom["components"][1])
        self.assertEqual(bom["components"][1]["purl"], "pkg:maven/org.openjfx/javafx-controls@21.0.5?type=jar")

    def test_an_unpublished_module_stops_the_release(self):
        bom = copy.deepcopy(PLUGIN_BOM)
        bom["components"].append({"bom-ref": "x", "purl": "pkg:maven/x/app-java@unspecified?project_path=%3Aapp-java"})
        with self.assertRaises(SbomError):
            normalize(bom, self.ui, "26.8.1", "t", "https://maven.countly.com/", {":sdk-java": self.java})

    def test_place_sbom_writes_the_file_and_its_checksums(self):
        staging = Path(tempfile.mkdtemp())
        source = staging / "bom.json"
        source.write_bytes(json.dumps(PLUGIN_BOM).encode("utf-8"))
        target = place_sbom(source, staging, self.java, "26.8.1", "t", "https://maven.countly.com/")
        self.assertEqual(target, staging / "ly/count/sdk/java/26.8.1/java-26.8.1-cyclonedx.json")
        self.assertEqual(json.loads(target.read_bytes())["metadata"]["component"]["name"], "java")
        for suffix in [".md5", ".sha1", ".sha256", ".sha512"]:
            self.assertTrue(Path(f"{target}{suffix}").is_file())


if __name__ == "__main__":
    unittest.main()
