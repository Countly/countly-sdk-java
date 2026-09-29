"""Checks on the tag commit: its release branch and the required status checks."""

import subprocess


def branch_problems(commit, branch, repo_root, runner=subprocess.run):
    """Problems unless the commit is an ancestor of origin/<branch>."""
    result = runner(["git", "-C", str(repo_root), "merge-base", "--is-ancestor", commit, f"origin/{branch}"], capture_output=True)
    return [] if result.returncode == 0 else [f"{commit} is not on origin/{branch}; the tag must point to that branch"]


def missing_checks(check_runs, required):
    """Required check names that did not succeed on the commit, from the GitHub check-runs API response: a name without
    any run, or with any run that did not succeed or has not finished. A commit can carry several runs of one check,
    for example one from the push to the release branch and one from a pull request whose head it is, which tests a
    merge with another branch; each of them must have passed."""
    problems = []
    for name in required:
        conclusions = [run.get("conclusion") for run in check_runs.get("check_runs", []) if run["name"] == name]
        if not conclusions or any(conclusion != "success" for conclusion in conclusions):
            problems.append(name)
    return problems
