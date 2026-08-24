#!/usr/bin/env python3
"""
Check resolved Gradle dependencies against the OSV vulnerability DB.

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

OSV_HOST_PREFIX = "https://api.osv.dev/"
OSV_BATCH = OSV_HOST_PREFIX + "v1/querybatch"
OSV_VULN = OSV_HOST_PREFIX + "v1/vulns/"
# Advisory ids come back inside an OSV response, i.e. from outside this repo.
# They are interpolated into a URL, so accept only the documented shape.
VULN_ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,100}$")
# Local project artifacts have no upstream version to check.
SKIP_VERSIONS = {"unspecified", ""}


def read_coords(stream):
    """Map each coordinate to the sorted module paths that declare it."""
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


def _check_url(url):
    """
    Reject any URL that is not a plain https OSV API endpoint.

    urlopen would happily accept file:/ or a custom scheme, so the host and
    scheme are pinned here rather than trusted from the caller.
    """
    if not url.startswith(OSV_HOST_PREFIX):
        raise ValueError(f"refusing to fetch a non-OSV URL: {url}")
    return url


def post_json(url, payload):
    """POST a JSON payload to OSV and return the decoded response."""
    req = urllib.request.Request(
        _check_url(url),
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
    )
    # nosec B310 - scheme and host are pinned by _check_url above.
    with urllib.request.urlopen(req, timeout=60) as resp:  # nosec B310
        return json.load(resp)


def get_json(url):
    """GET an OSV endpoint and return the decoded response."""
    # nosec B310 -- scheme and host are pinned by _check_url above.
    checked = _check_url(url)
    with urllib.request.urlopen(checked, timeout=60) as resp:  # nosec B310
        return json.load(resp)


def query_osv(coords):
    """Return {coord: [vuln id, ...]} for coordinates with a vulnerability."""
    keys = list(coords)
    queries = []
    for ga_v in keys:
        group, artifact, version = ga_v.rsplit(":", 2)
        queries.append(
            {
                "package": {
                    "ecosystem": "Maven",
                    "name": f"{group}:{artifact}",
                },
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
    """Return (summary, severity, fixed_versions) for an advisory id."""
    if not VULN_ID_RE.match(vuln_id):
        return "(advisory id in unexpected format)", "", []
    try:
        data = get_json(OSV_VULN + vuln_id)
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError):
        return "(details unavailable)", "", []
    summary = (data.get("summary")
               or data.get("details", "")[:160]
               or "(no summary)")
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
    """Scan stdin's coordinates and return a process exit code."""
    coords = read_coords(sys.stdin)
    if not coords:
        print("::error::no dependency coordinates received - the "
              "Gradle report step failed")
        return 1

    print(f"Scanned {len(coords)} resolved dependencies, all modules.\n")
    hits = query_osv(coords)

    module_count = len({m for ms in coords.values() for m in ms})
    summary_lines = ["# Dependency security scan", ""]
    if not hits:
        print("No known vulnerabilities.")
        for ga_v, modules in coords.items():
            print(f"  ok  {ga_v}  ({', '.join(modules)})")
        summary_lines += [
            f"No known vulnerabilities in **{len(coords)}** resolved "
            f"dependencies across {module_count} modules.",
            "",
            "<details><summary>Dependencies checked</summary>",
            "",
            "| Dependency | Modules |",
            "| --- | --- |",
        ] + [
            f"| `{ga_v}` | {', '.join(modules)} |"
            for ga_v, modules in coords.items()
        ] + ["", "</details>"]
        write_summary(summary_lines)
        return 0

    summary_lines += [
        f"**{len(hits)} vulnerable dependency(ies)** out of "
        f"{len(coords)} resolved.",
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
            print(f"::error title={ga_v} {vuln_id}::{summary} "
                  f"(fixed in {fixed_text})")
            summary_lines.append(
                f"| `{ga_v}` | {modules} | "
                f"[{vuln_id}](https://osv.dev/vulnerability/{vuln_id})"
                f" | {severity or 'n/a'} | {fixed_text} |"
            )
        print()

    summary_lines += [
        "",
        "Upgrade the dependency in **every** module that declares it - "
        "this project declares `org.json` in `sdk-java`, `app-java` "
        "and `app-javafx` separately.",
    ]
    write_summary(summary_lines)
    return 1


def write_summary(lines):
    """
    Write the report to the job summary and to a file for the PR comment.

    GITHUB_STEP_SUMMARY only renders on the workflow run page - it does NOT
    reach the pull request. The report file is what the PR comment step posts.
    """
    body = "\n".join(lines) + "\n"
    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as handle:
            handle.write(body)
    report_path = os.environ.get("OSV_REPORT_FILE", "osv-report.md")
    with open(report_path, "w", encoding="utf-8") as handle:
        handle.write(body)


if __name__ == "__main__":
    sys.exit(main())
