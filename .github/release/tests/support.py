"""Shared test helpers: the repository configuration, fake checkouts and in-memory stand-ins for R2 and the web."""

import hashlib
from pathlib import Path

from plan import load_config

BUILD_GRADLE = 'allprojects {\n  ext.CLY_VERSION = "{version}"\n  ext.POWERMOCK_VERSION = "1.7.4"\n}\n'
CONFIG_JAVA = 'public class Config {\n    protected String sdkVersion = "{version}";\n}\n'


def repository_config():
    """The real config.json of this repository."""
    return load_config(Path(__file__).resolve().parents[1] / "config.json")


def write_file(root, relative, text):
    """Writes a file of a fake checkout."""
    path = Path(root) / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(text.encode("utf-8"))


def write_core(root, properties_version, gradle_version, java_version, changelog):
    """Writes the three version sources of the core SDK and its changelog into a fake checkout."""
    write_file(root, "gradle.properties", f"VERSION_NAME={properties_version}\nGROUP=ly.count.sdk\n")
    write_file(root, "build.gradle", BUILD_GRADLE.replace("{version}", gradle_version))
    write_file(root, "sdk-java/src/main/java/ly/count/sdk/java/Config.java", CONFIG_JAVA.replace("{version}", java_version))
    write_file(root, "CHANGELOG.md", changelog)


def write_ui(root, version, changelog):
    """Writes the version source of the UI package and its changelog into a fake checkout."""
    write_file(root, "sdk-java-ui/gradle.properties", f"POM_ARTIFACT_ID=java-ui\nVERSION_NAME={version}\n")
    write_file(root, "sdk-java-ui/CHANGELOG.md", changelog)


class FakeBucket:
    """In-memory stand-in for s3.R2Bucket with MD5 ETags; `fail_on` makes one upload fail like a lost connection."""

    def __init__(self, fail_on=None):
        self.objects = {}
        self.calls = []
        self.fail_on = fail_on

    def head(self, key):
        """Returns {'etag', 'size'} or None."""
        if key not in self.objects:
            return None
        data = self.objects[key][0]
        return {"etag": hashlib.md5(data).hexdigest(), "size": len(data)}

    def put_file(self, key, path, content_type, cache_control, create_only):
        """Stores a file; with create_only an existing key is left alone and 'exists' is returned."""
        if key == self.fail_on:
            raise RuntimeError(f"connection lost while uploading {key}")
        self.calls.append(key)
        if create_only and key in self.objects:
            return "exists"
        self.objects[key] = (Path(path).read_bytes(), content_type, cache_control)
        return "created"

    def list_keys(self, prefix):
        """Keys starting with the prefix, sorted."""
        return sorted(key for key in self.objects if key.startswith(prefix))


class FakeWeb:
    """Serves a dict of URL to bytes (ignoring ?nocache=...); anything else is a 404 (None)."""

    def __init__(self, pages=None):
        self.pages = dict(pages or {})
        self.requests = []

    def __call__(self, url):
        self.requests.append(url)
        return self.pages.get(url.split("?nocache=")[0])
