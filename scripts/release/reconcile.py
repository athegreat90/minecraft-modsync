#!/usr/bin/env python3
"""Reconcile every reachable main commit, rather than just push event head."""
import datetime as dt, json, os, re, subprocess

# First main commit whose build supports the release pipeline. d9f75f5 was the previous floor, but its native build can never
# pass: Gradle dependency verification lacks the graalvm-reachability-metadata checksum, which was added by this commit.
RELEASE_FLOOR = "7c2945334787ca9f28d15bde513de55134fdf4ed"
COOLDOWN = 6 * 3600
MAX_COOLDOWN = 7 * 24 * 3600
TAG_PATTERN = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+-dev\.[0-9]{14}\.[0-9a-f]{40}")

def run(*args):
    return subprocess.check_output(args, text=True).strip()

def version(sha, timestamp):
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Expected full commit SHA")
    stamp = dt.datetime.fromtimestamp(int(timestamp),dt.timezone.utc).strftime("%Y%m%d%H%M%S")
    return f"v0.6.5-dev.{stamp}.{sha}"

def tag_version(tag):
    """Application version part of a development tag, for example `0.5.0` for `v0.5.0-dev.<time>.<sha>`."""
    return tag[1:].split("-dev.", 1)[0]

def source_version(sha):
    """Application version a commit builds as, read from that commit's own release tooling; None when it cannot be determined."""
    try:
        text = run("git", "show", f"{sha}:scripts/release/reconcile.py")
    except subprocess.CalledProcessError:
        return None
    match = re.search(r'return f"v([0-9]+\.[0-9]+\.[0-9]+)-dev\.', text)
    return match.group(1) if match else None

def superseded(commits, released, versions):
    """Unreleased commits replaced by a later release of the same version.

    Only the newest build of each version is kept, so a commit that is older than a released commit of its own version
    must not be built again (nor rebuilt after its old release was pruned). Commits whose version is unknown are never
    treated as superseded. `commits` is ordered from oldest to newest.
    """
    newest = {}
    for index, sha in enumerate(commits):
        if sha in released and versions.get(sha):
            newest[versions[sha]] = index
    return {sha for index, sha in enumerate(commits) if sha not in released and versions.get(sha) and newest.get(versions[sha], -1) > index}

def cooldown(failures):
    """Seconds to wait after the last attempt: 6 hours, doubled for every further failure, capped at 7 days."""
    return min(COOLDOWN * 2 ** max(failures - 1, 0), MAX_COOLDOWN)

def workflows_tree(sha):
    """Git tree id of `.github/workflows` at [sha], or None when the directory does not exist."""
    try:
        return run("git","rev-parse",f"{sha}:.github/workflows")
    except subprocess.CalledProcessError:
        return None

def unpublishable(commits, released, tree=workflows_tree):
    """Commits the release workflow can never publish.

    The workflow's GITHUB_TOKEN cannot create a branch or tag at a commit whose `.github/workflows` differ from the
    tip of main (HTTP 403 "Resource not accessible by integration", observed for two historical commits while the
    same call at the tip succeeded), so `publish` fails for them and every retry only burns runners.
    Already released commits are not checked.
    """
    tip = tree(commits[-1]) if commits else None
    return [sha for sha in commits if sha not in released and tree(sha) != tip]

def candidates(commits, released, running, attempted, now, failures=None):
    failures = failures or {}
    eligible = [sha for sha in commits if sha not in released and sha not in running and now-attempted.get(sha,0)>=cooldown(failures.get(sha,0))]
    head = commits[-1] if commits else None
    return sorted(eligible, key=lambda sha: (sha != head, attempted.get(sha,0)))

def main():
    repo = os.environ["GITHUB_REPOSITORY"]
    commits = run("git","rev-list","--reverse",f"{RELEASE_FLOOR}^..origin/main").splitlines()
    pages = json.loads(run("gh","api","--paginate","--slurp",f"repos/{repo}/releases?per_page=100"))
    from state import verify_complete
    released, invalid, versions = set(), {}, {}
    for page in pages:
        for release in page:
            if release['draft'] or not TAG_PATTERN.fullmatch(release['tag_name']):
                continue
            sha = release['tag_name'].rsplit('.', 1)[-1]
            try:
                verify_complete(repo, release, sha)
                released.add(sha)
                versions[sha] = tag_version(release['tag_name'])
            except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
                invalid[sha] = str(error)
    pages = json.loads(run("gh","api","--paginate","--slurp",f"repos/{repo}/actions/workflows/release.yaml/runs?event=workflow_dispatch&per_page=100"))
    running, attempted, failed, failures = set(), {}, {}, {}
    for page in pages:
        for item in page["workflow_runs"]:
            match = re.fullmatch(r"release:([0-9a-f]{40})",item["display_title"])
            if not match: continue
            sha=match.group(1)
            if item["status"] in ("queued","in_progress","waiting","pending","requested"): running.add(sha)
            if item.get('conclusion') in ('failure', 'timed_out', 'cancelled', 'action_required'):
                failed.setdefault(sha, item.get('html_url'))
            if item.get('conclusion') in ('failure', 'timed_out'):
                failures[sha] = failures.get(sha, 0) + 1
            at=dt.datetime.fromisoformat(item.get("updated_at", item["created_at"]).replace("Z","+00:00")).timestamp()
            attempted[sha]=max(at,attempted.get(sha,0))
    now=dt.datetime.now(dt.timezone.utc).timestamp()
    skipped = unpublishable(commits, released)
    for sha in commits:
        if sha not in released and sha not in versions:
            versions[sha] = source_version(sha)
    replaced = superseded(commits, released, versions)
    selected=candidates([sha for sha in commits if sha not in skipped and sha not in replaced],released,running,attempted,now,failures)[:20]
    for sha in selected:
        subprocess.run(["gh","workflow","run","release.yaml","--ref","main","-f",f"sha={sha}"],check=True)
    print(json.dumps({"reachable":len(commits),"released":len(set(commits)&released),"running":len(running),"dispatched":selected,"failed":failed,"invalidReleases":invalid,"unpublishable":skipped,"superseded":sorted(replaced),"remaining":len(set(commits)-released-set(skipped)-replaced)},indent=2))
if __name__ == "__main__": main()
