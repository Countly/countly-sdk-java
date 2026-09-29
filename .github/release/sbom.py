"""Turns the CycloneDX plugin's per-project SBOM into the published component list of one artifact."""

import json
from pathlib import Path
from urllib.parse import parse_qs, quote, urlsplit

from layout import write_checksums


def purl(artifact, version, public_base_url):
    """Package URL of a published Countly artifact, naming the Countly repository."""
    repository = quote(public_base_url.rstrip("/"), safe="")
    return f"pkg:maven/{artifact.group}/{artifact.artifact}@{version}?repository_url={repository}&type=jar"


class SbomError(Exception):
    """The plugin's SBOM names a module of this build that is not published."""


def _project_path(component):
    """Gradle project path of a component the plugin wrote for a module of this build, or None."""
    return parse_qs(urlsplit(component.get("purl", "")).query).get("project_path", [None])[0]


def normalize(bom, artifact, version, timestamp, public_base_url, modules=None, platform_groups=()):
    """Sets the published coordinates, the licence and a fixed timestamp, and drops the random serial number. Components
    the plugin wrote for other modules of this build (java-ui depends on java) get the coordinates those modules are
    published under in the same release; `modules` maps a Gradle project path to its published artifact. Components of
    `platform_groups` lose their hashes: those groups publish one file per operating system (JavaFX), so the hashes of
    the file the build machine resolved match no file the named package stands for."""
    metadata = bom.setdefault("metadata", {})
    old_ref = metadata.get("component", {}).get("bom-ref")
    ref = purl(artifact, version, public_base_url)
    metadata["component"] = {"type": "library", "bom-ref": ref, "group": artifact.group, "name": artifact.artifact, "version": version, "purl": ref, "licenses": [{"license": {"id": "MIT"}}]}
    metadata["timestamp"] = timestamp
    bom.pop("serialNumber", None)
    renamed = {} if old_ref is None else {old_ref: ref}
    for component in bom.get("components", []):
        if component.get("group") in platform_groups:
            component.pop("hashes", None)
        path = _project_path(component)
        if path is None:
            continue
        published = (modules or {}).get(path)
        if published is None:
            raise SbomError(f"the SBOM of {artifact.coordinates} names the module {path}, which is not published")
        new_ref = purl(published, version, public_base_url)
        renamed[component.get("bom-ref")] = new_ref
        component.update({"bom-ref": new_ref, "group": published.group, "name": published.artifact, "version": version, "purl": new_ref})
    for dependency in bom.get("dependencies", []):
        dependency["ref"] = renamed.get(dependency.get("ref"), dependency.get("ref"))
        if "dependsOn" in dependency:
            dependency["dependsOn"] = [renamed.get(entry, entry) for entry in dependency["dependsOn"]]
    return bom


def place_sbom(bom_path, staging_dir, artifact, version, timestamp, public_base_url, modules=None, platform_groups=()):
    """Writes the published SBOM and its checksums into the staging folder and returns its path."""
    bom = normalize(json.loads(Path(bom_path).read_text(encoding="utf-8")), artifact, version, timestamp, public_base_url, modules, platform_groups)
    target = Path(staging_dir) / artifact.folder(version) / f"{artifact.base_name(version)}-cyclonedx.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes((json.dumps(bom, indent=2) + "\n").encode("utf-8"))
    write_checksums(target)
    return target
