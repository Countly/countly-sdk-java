#!/usr/bin/env python3
"""Turn jacoco XML reports into a Markdown coverage report for a pull request comment.

Reads one report per Gradle module, plus the list of files the pull request touched, and
writes:

  * a per-module line and branch coverage table
  * coverage of the source files this pull request actually changed, which is the part a
    reviewer can act on
  * the least covered classes, as a standing to-do list
  * the test failure count, so nobody reads coverage numbers off a broken run

Exit code is 0 unless a configured minimum is missed and enforcement is switched on, so the
job can start out advisory and become a gate later without touching this script.

Usage:
  coverage_report.py --out coverage-report.md
                     --changed-files changed.txt
                     --module <label>:<jacoco xml>:<test-results dir>:<source root> ...
"""

import argparse
import os
import sys
import xml.etree.ElementTree as ET

# Reported but not enforced unless ENFORCE_COVERAGE is truthy, so the job can be introduced
# on a repository that is not at target yet.
MIN_OVERALL = float(os.environ.get("COVERAGE_MIN_OVERALL", "0"))
MIN_CHANGED = float(os.environ.get("COVERAGE_MIN_CHANGED", "0"))
ENFORCE = os.environ.get("ENFORCE_COVERAGE", "").lower() in ("1", "true", "yes")
WORST_CLASS_COUNT = int(os.environ.get("COVERAGE_WORST_CLASSES", "10"))


class Counter:
    """Covered and missed totals for one jacoco counter type."""

    def __init__(self, covered=0, missed=0):
        self.covered = covered
        self.missed = missed

    @property
    def total(self):
        return self.covered + self.missed

    @property
    def percent(self):
        if self.total == 0:
            return None
        return 100.0 * self.covered / self.total

    def add(self, other):
        self.covered += other.covered
        self.missed += other.missed

    def __str__(self):
        if self.total == 0:
            return "n/a"
        return "{:.1f}% ({}/{})".format(self.percent, self.covered, self.total)


def counters_of(element):
    """The counters declared directly on a jacoco element, keyed by type."""
    result = {}
    for counter in element.findall("counter"):
        result[counter.get("type")] = Counter(
            covered=int(counter.get("covered", 0)),
            missed=int(counter.get("missed", 0)),
        )
    return result


def parse_report(path):
    """A jacoco report as (report counters, {(package, sourcefile): counters}, [(class, counters)])."""
    # The report declares a DOCTYPE pointing at a remote DTD. ElementTree does not fetch
    # external entities, so parsing stays offline.
    root = ET.parse(path).getroot()

    overall = counters_of(root)
    source_files = {}
    classes = []

    for package in root.iter("package"):
        package_name = package.get("name", "")

        for source_file in package.findall("sourcefile"):
            key = (package_name, source_file.get("name", ""))
            source_files[key] = counters_of(source_file)

        for klass in package.findall("class"):
            name = klass.get("name", "")
            # Nested and anonymous classes roll up into their outer class for reporting.
            if "$" in name:
                continue
            classes.append((name.replace("/", "."), counters_of(klass)))

    return overall, source_files, classes


def count_test_failures(results_dir):
    """Failures plus errors across a Gradle module's JUnit XML results."""
    if not results_dir or not os.path.isdir(results_dir):
        return 0

    failures = 0
    for entry in os.listdir(results_dir):
        if not entry.endswith(".xml"):
            continue
        try:
            suite = ET.parse(os.path.join(results_dir, entry)).getroot()
            failures += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
        except ET.ParseError:
            # An unreadable result file is not worth failing the report over.
            continue
    return failures


def read_build_log_tail(path, limit=40):
    """The last few lines of the build output, for when there is no report to show."""
    if not path or not os.path.isfile(path):
        return []
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            return [line.rstrip() for line in handle.readlines()[-limit:]]
    except OSError:
        return []


def read_changed_files(path):
    if not path or not os.path.isfile(path):
        return []
    with open(path, encoding="utf-8") as handle:
        return [line.strip() for line in handle if line.strip().endswith(".java")]


def percent_cell(counter):
    return str(counter) if counter else "n/a"


def format_percent(value):
    return "n/a" if value is None else "{:.1f}%".format(value)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    parser.add_argument("--changed-files")
    parser.add_argument("--build-log", help="Build output, shown when no report was produced.")
    parser.add_argument(
        "--module",
        action="append",
        default=[],
        help="<label>:<jacoco xml>:<test-results dir>:<source root>",
    )
    args = parser.parse_args()

    changed_files = read_changed_files(args.changed_files)

    lines = ["## Code coverage", ""]
    module_rows = []
    changed_rows = []
    worst = []
    total_failures = 0
    missing_reports = []

    for spec in args.module:
        label, report_path, results_dir, source_root = (spec.split(":") + ["", "", ""])[:4]

        if not os.path.isfile(report_path):
            missing_reports.append(label)
            module_rows.append((label, "no report", "no report"))
            continue

        overall, source_files, classes = parse_report(report_path)
        total_failures += count_test_failures(results_dir)

        module_rows.append(
            (label, percent_cell(overall.get("LINE")), percent_cell(overall.get("BRANCH")))
        )

        for name, counters in classes:
            line_counter = counters.get("LINE")
            if line_counter and line_counter.missed > 0:
                worst.append((line_counter.missed, name, label, line_counter))

        # Match a changed path back to the report through its package and file name.
        for changed in changed_files:
            if source_root and not changed.startswith(source_root):
                continue
            file_name = os.path.basename(changed)
            package = os.path.dirname(changed)
            if source_root:
                package = package[len(source_root):]
            package = package.strip("/")

            counters = source_files.get((package, file_name))
            if counters:
                changed_rows.append((changed, percent_cell(counters.get("LINE")), counters.get("LINE")))

    lines.append("| module | lines | branches |")
    lines.append("|---|---|---|")
    for label, line_cell, branch_cell in module_rows:
        lines.append("| `{}` | {} | {} |".format(label, line_cell, branch_cell))
    lines.append("")

    if changed_rows:
        lines.append("### Files changed by this pull request")
        lines.append("")
        lines.append("| file | lines covered |")
        lines.append("|---|---|")
        for path, cell, _ in sorted(changed_rows):
            lines.append("| `{}` | {} |".format(path, cell))
        lines.append("")
    elif changed_files:
        lines.append("_No changed file appears in a coverage report. Either none of the changed "
                     "Java files are in a measured module, or they are test sources._")
        lines.append("")

    if worst:
        worst.sort(reverse=True)
        lines.append("<details><summary>Least covered classes ({} shown)</summary>".format(
            min(WORST_CLASS_COUNT, len(worst))))
        lines.append("")
        lines.append("| class | module | lines covered | uncovered |")
        lines.append("|---|---|---|---|")
        for missed, name, label, counter in worst[:WORST_CLASS_COUNT]:
            lines.append("| `{}` | `{}` | {} | {} |".format(name, label, counter, missed))
        lines.append("")
        lines.append("</details>")
        lines.append("")

    if total_failures > 0:
        lines.append("> **{} test(s) failed in this run.** Coverage is still measured over the whole "
                     "run, but read the test results before trusting these numbers."
                     .format(total_failures))
        lines.append("")

    if missing_reports:
        lines.append("> No coverage report was produced for: {}."
                     .format(", ".join("`{}`".format(m) for m in missing_reports)))
        lines.append("")

        # A missing report almost always means the build failed before jacoco ran. Putting the
        # tail of the build output in the comment saves a trip to the workflow logs.
        tail = read_build_log_tail(args.build_log)
        if tail:
            lines.append("<details><summary>Build output (last {} lines)</summary>".format(len(tail)))
            lines.append("")
            lines.append("```")
            lines.extend(tail)
            lines.append("```")
            lines.append("")
            lines.append("</details>")
            lines.append("")

    # Threshold checks, reported either way and enforced only when asked to be.
    problems = []

    for label, line_cell, _ in module_rows:
        if MIN_OVERALL > 0 and line_cell not in ("no report",):
            value = float(line_cell.split("%")[0])
            if value < MIN_OVERALL:
                problems.append("`{}` line coverage {} is below the {:.1f}% minimum"
                                .format(label, format_percent(value), MIN_OVERALL))

    if MIN_CHANGED > 0 and changed_rows:
        changed_total = Counter()
        for _, _, counter in changed_rows:
            if counter:
                changed_total.add(counter)
        if changed_total.percent is not None and changed_total.percent < MIN_CHANGED:
            problems.append("changed files line coverage {} is below the {:.1f}% minimum"
                            .format(format_percent(changed_total.percent), MIN_CHANGED))

    if problems:
        lines.append("**Coverage below the configured minimum:**")
        lines.append("")
        for problem in problems:
            lines.append("* {}".format(problem))
        lines.append("")
        if not ENFORCE:
            lines.append("_Advisory only. Set `ENFORCE_COVERAGE=true` on the workflow to make this "
                         "fail the build._")
            lines.append("")

    body = "\n".join(lines)

    with open(args.out, "w", encoding="utf-8") as handle:
        handle.write(body)

    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as handle:
            handle.write(body + "\n")

    print(body)

    if problems and ENFORCE:
        print("::error::Coverage is below the configured minimum")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
