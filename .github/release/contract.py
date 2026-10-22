"""Compares what a staged artifact promises integrators with its committed contract file."""

import io
import json
import zipfile
import xml.etree.ElementTree as ElementTree
from pathlib import Path

POM_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
JVM_VERSION = "org.gradle.jvm.version"


def _child(element, tag, default=""):
    return (element.findtext(f"m:{tag}", default, POM_NAMESPACE) or default).strip()


def pom_dependencies(pom):
    """Sorted group:artifact:version:scope strings of the POM's direct dependencies."""
    root = ElementTree.fromstring(pom)
    return sorted(
        f"{_child(d, 'groupId')}:{_child(d, 'artifactId')}:{_child(d, 'version')}:{_child(d, 'scope', 'compile')}"
        for d in root.findall("m:dependencies/m:dependency", POM_NAMESPACE)
    )


def module_variants(module):
    """Library variants (not sources or javadoc) of a Gradle module file."""
    return [variant for variant in json.loads(module).get("variants", []) if variant.get("attributes", {}).get("org.gradle.category") == "library"]


def module_dependencies(module):
    """Sorted group:module:version strings declared by the library variants of a Gradle module file."""
    found = set()
    for variant in module_variants(module):
        for dependency in variant.get("dependencies", []):
            version = dependency.get("version", {})
            number = version.get("requires") or version.get("strictly") or version.get("prefers") or ""
            found.add(f"{dependency['group']}:{dependency['module']}:{number}")
    return sorted(found)


def max_class_major(jar):
    """Highest class-file major version in a jar (52 is Java 8), ignoring multi-release folders."""
    highest = 0
    with zipfile.ZipFile(io.BytesIO(jar)) as archive:
        for name in archive.namelist():
            if name.endswith(".class") and not name.startswith("META-INF/versions/"):
                head = archive.read(name)[:8]
                if len(head) == 8 and head[:4] == b"\xca\xfe\xba\xbe":
                    highest = max(highest, (head[6] << 8) | head[7])
    return highest


def _common(variants, key):
    """The attribute value all variants share (None when absent), or a note that they differ."""
    values = {json.dumps(variant.get("attributes", {}).get(key)) for variant in variants}
    if len(values) == 1:
        return json.loads(values.pop())
    return None if not values else "differs between variants"


def actual_contract(staging_dir, artifact, version):
    """The contract the staged files of one artifact version fulfil, in the shape of the contract files (schema 1)."""
    folder = Path(staging_dir) / artifact.folder(version)
    base = artifact.base_name(version)
    return {
        "schema": 1,
        "coordinates": artifact.coordinates,
        "pomDependencies": pom_dependencies((folder / f"{base}.pom").read_bytes()),
        "moduleVariantAttributes": {JVM_VERSION: _common(module_variants((folder / f"{base}.module").read_bytes()), JVM_VERSION)},
        "maxClassFileMajor": max_class_major((folder / f"{base}.jar").read_bytes()),
    }


def compare(expected, actual):
    """Differences between the committed contract and the staged artifact, as readable problems."""
    problems = []
    if expected["coordinates"] != actual["coordinates"]:
        problems.append(f"coordinates are {actual['coordinates']}, the contract says {expected['coordinates']}")
    if expected["pomDependencies"] != actual["pomDependencies"]:
        problems.append(f"POM dependencies are {actual['pomDependencies']}, the contract says {expected['pomDependencies']}")
    for key, value in expected.get("moduleVariantAttributes", {}).items():
        found = actual["moduleVariantAttributes"].get(key)
        if found != value:
            problems.append(f"{key} is {found}, the contract says {value}")
    if actual["maxClassFileMajor"] > expected["maxClassFileMajor"]:
        problems.append(f"class files reach major version {actual['maxClassFileMajor']}, the contract allows {expected['maxClassFileMajor']}")
    return problems


def module_dependency_problems(staging_dir, artifact, version):
    """Problems when the .module declares other dependencies than the POM; Gradle builds read the .module, Maven builds the POM."""
    folder = Path(staging_dir) / artifact.folder(version)
    base = artifact.base_name(version)
    in_module = module_dependencies((folder / f"{base}.module").read_bytes())
    in_pom = sorted(entry.rsplit(":", 1)[0] for entry in pom_dependencies((folder / f"{base}.pom").read_bytes()))
    return [] if in_module == in_pom else [f"the .module declares {in_module} but the POM declares {in_pom}"]


def _shown(value):
    return "absent" if value is None else str(value)


def summary_rows(expected, actual):
    """(property, contract, built) rows for the job summary table."""
    def listing(values):
        return ", ".join(values) if values else "none"

    return [
        ("POM dependencies", listing(expected["pomDependencies"]), listing(actual["pomDependencies"])),
        (JVM_VERSION, _shown(expected.get("moduleVariantAttributes", {}).get(JVM_VERSION)), _shown(actual["moduleVariantAttributes"].get(JVM_VERSION))),
        ("highest class file version", f"at most {expected['maxClassFileMajor']}", str(actual["maxClassFileMajor"])),
    ]
