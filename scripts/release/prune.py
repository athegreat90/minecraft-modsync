#!/usr/bin/env python3
"""Keep only the newest development prerelease of each application version.

Dry run by default; pass --apply to delete. The newest release of a version is only trusted (and older ones only removed)
when it is published and complete, so the last good release of a version can never be deleted. Drafts are never touched.
Container image tags in GHCR are not removed here (the workflow token cannot delete package versions).
"""
import json, os, subprocess, sys
from reconcile import TAG_PATTERN, run, tag_version


def plan(releases, complete=lambda release: True):
    """Tags to delete: every published development release except the newest one of its version.

    The newest is chosen by the commit timestamp embedded in the tag, then by tag name. A version whose newest release is
    not complete (as reported by [complete]) is left untouched.
    """
    groups = {}
    for release in releases:
        tag = release["tag_name"]
        if release.get("draft") or not TAG_PATTERN.fullmatch(tag):
            continue
        groups.setdefault(tag_version(tag), []).append(release)
    doomed = []
    for group in groups.values():
        group.sort(key=lambda release: (release["tag_name"].split("-dev.", 1)[1]))
        *older, newest = group
        if older and complete(newest):
            doomed.extend(release["tag_name"] for release in older)
    return sorted(doomed)


def main():
    repo = os.environ["GITHUB_REPOSITORY"]
    apply = "--apply" in sys.argv
    from state import verify_complete
    pages = json.loads(run("gh", "api", "--paginate", "--slurp", f"repos/{repo}/releases?per_page=100"))
    releases = [release for page in pages for release in page]

    def complete(release):
        try:
            verify_complete(repo, release, release["tag_name"].rsplit(".", 1)[-1])
            return True
        except (ValueError, KeyError, OSError, subprocess.CalledProcessError):
            return False

    doomed = plan(releases, complete)
    print(json.dumps({"mode": "apply" if apply else "dry-run", "delete": doomed}, indent=2))
    if apply:
        for tag in doomed:
            subprocess.run(["gh", "release", "delete", tag, "--cleanup-tag", "--yes", "--repo", repo], check=True)


if __name__ == "__main__":
    main()
