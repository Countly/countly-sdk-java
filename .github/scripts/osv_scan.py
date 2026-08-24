#!/usr/bin/env python3
"""Check every resolved Gradle dependency against the OSV vulnerability database.

Reads "COORD <projectPath> <group>:<artifact>:<version>" lines (produced by
dependency-report.init.gradle) on stdin and queries https://osv.dev.

Exits 1 if anything vulnerable is found, so CI fails the job.

Why this exists rather than a stock scanner: this project has no Gradle
lockfile, so lockfile-based scanners see nothing. It also declares org.json
separately in sdk-java, app-java and app-javafx -- a past upgrade bumped only
sdk-java and left the demo modules on a version with a known CVE. Scanning the
*resolved* graph of every module is what catches that.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

OSV_BATCH = "https://api.osv.dev/v1/querybatch"
OSV_VULN = "https://api.osv.dev/v1/vulns/"
# Local project artifacts have no upstream version to check.
SKIP_VERSIONS = {"unspecified", ""}


def read_coords(stream):
    """-> {(group:artifact:version): sorted list of module paths}"""
    coords = {}
    for line in stream:
        parts = line.split()
        if len(parts) != 3 or parts[0] != "COORD":
            continue
        _, project, ga_v = parts
        if ga_v.count(":") != 2:
            continue
        version = ga_v.rsplit(":", 1)[1]
        if version in SKIP_VERSIONS:
            continue
        coords.setdefault(ga_v, set()).add(project)
    return {k: sorted(v) for k, v in sorted(coords.items())}


def post_json(url, payload):
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def get_json(url):
    with urllib.request.urlopen(url, timeout=60) as resp:
        return json.load(resp)


def query_osv(coords):
    """-> {coord: [vuln id, ...]} for coords with at least one vulnerability."""
    keys = list(coords)
    queries = []
    for ga_v in keys:
        group, artifact, version = ga_v.rsplit(":", 2)
        queries.append(
            {
                "package": {"ecosystem": "Maven", "name": f"{group}:{artifact}"},
                "version": version,
            }
        )
    results = post_json(OSV_BATCH, {"queries": queries})["results"]
    hits = {}
    for ga_v, result in zip(keys, results):
        ids = [v["id"] for v in result.get("vulns", [])]
        if ids:
            hits[ga_v] = ids
    return hits


def describe(vuln_id):
    """-> (summary, severity, fixed_versions) - best effort."""
    try:
        data = get_json(OSV_VULN + vuln_id)
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError):
        return "(details unavailable)", "", []
    summary = data.get("summary") or data.get("details", "")[:160] or "(no summary)"
    severity = ""
    for sev in data.get("severity") or []:
        score = sev.get("score", "")
        match = re.search(r"CVSS:[\d.]+/(\S+)", score)
        severity = match.group(1) if match else score
        break
    fixed = []
    for affected in data.get("affected", []):
        for rng in affected.get("ranges", []):
            for event in rng.get("events", []):
                fix = event.get("fixed")
                # Git commit ranges are not actionable version numbers.
                if fix and not re.fullmatch(r"[0-9a-f]{40}", fix):
                    fixed.append(fix)
    aliases = [a for a in data.get("aliases", []) if a.startswith("CVE-")]
    if aliases:
        summary = f"[{', '.join(aliases)}] {summary}"
    return summary, severity, sorted(set(fixed))


def main():
    coords = read_coords(sys.stdin)
    if not coords:
        print("::error::no dependency coordinates received - the Gradle report step failed")
        return 1

    print(f"Scanned {len(coords)} resolved dependencies across all modules.\n")
    hits = query_osv(coords)

    summary_lines = ["# Dependency security scan", ""]
    if not hits:
        print("No known vulnerabilities.")
        for ga_v, modules in coords.items():
            print(f"  ok  {ga_v}  ({', '.join(modules)})")
        summary_lines += [
            f"No known vulnerabilities in {len(coords)} resolved dependencies.",
        ]
        write_summary(summary_lines)
        return 0

    summary_lines += [
        f"**{len(hits)} vulnerable dependency(ies)** out of {len(coords)} resolved.",
        "",
        "| Dependency | Modules | Advisory | Severity | Fixed in |",
        "| --- | --- | --- | --- | --- |",
    ]
    for ga_v, vuln_ids in hits.items():
        modules = ", ".join(coords[ga_v])
        print(f"VULNERABLE {ga_v}  (declared in: {modules})")
        for vuln_id in vuln_ids:
            summary, severity, fixed = describe(vuln_id)
            fixed_text = ", ".join(fixed) if fixed else "unknown"
            print(f"    {vuln_id}  {severity}")
            print(f"      {summary}")
            print(f"      fixed in: {fixed_text}")
            print(f"      https://osv.dev/vulnerability/{vuln_id}")
            # A GitHub annotation so the failure is visible on the PR itself.
            print(f"::error title={ga_v} {vuln_id}::{summary} (fixed in {fixed_text})")
            summary_lines.append(
                f"| `{ga_v}` | {modules} | [{vuln_id}](https://osv.dev/vulnerability/{vuln_id})"
                f" | {severity or 'n/a'} | {fixed_text} |"
            )
        print()

    summary_lines += [
        "",
        "Upgrade the dependency in **every** module that declares it - this project "
        "declares `org.json` in `sdk-java`, `app-java` and `app-javafx` separately.",
    ]
    write_summary(summary_lines)
    return 1


def write_summary(lines):
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    sys.exit(main())
