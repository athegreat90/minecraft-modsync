#!/usr/bin/env python3
"""Reconcile every reachable main commit, rather than just push event head."""
import datetime as dt, json, os, re, subprocess

def run(*args):
    return subprocess.check_output(args, text=True).strip()

def version(sha, timestamp):
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Expected full commit SHA")
    stamp = dt.datetime.fromtimestamp(int(timestamp),dt.timezone.utc).strftime("%Y%m%d%H%M%S")
    return f"v0.1.0-dev.{stamp}.{sha}"

def candidates(commits, released, running, attempted, now):
    eligible = [sha for sha in commits if sha not in released and sha not in running and now-attempted.get(sha,0)>=21600]
    head = commits[-1] if commits else None
    return sorted(eligible, key=lambda sha: (sha != head, attempted.get(sha,0)))

def main():
    repo = os.environ["GITHUB_REPOSITORY"]
    commits = run("git","rev-list","--reverse","origin/main").splitlines()
    pages = json.loads(run("gh","api","--paginate","--slurp",f"repos/{repo}/releases?per_page=100"))
    from state import verify_complete
    released, invalid = set(), {}
    for page in pages:
        for release in page:
            if release['draft'] or not re.fullmatch(r'v0\.1\.0-dev\.[0-9]{14}\.[0-9a-f]{40}', release['tag_name']):
                continue
            sha = release['tag_name'].rsplit('.', 1)[-1]
            try:
                verify_complete(repo, release, sha)
                released.add(sha)
            except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
                invalid[sha] = str(error)
    pages = json.loads(run("gh","api","--paginate","--slurp",f"repos/{repo}/actions/workflows/release.yaml/runs?event=workflow_dispatch&per_page=100"))
    running, attempted, failed = set(), {}, {}
    for page in pages:
        for item in page["workflow_runs"]:
            match = re.fullmatch(r"release:([0-9a-f]{40})",item["display_title"])
            if not match: continue
            sha=match.group(1)
            if item["status"] in ("queued","in_progress","waiting","pending","requested"): running.add(sha)
            if item.get('conclusion') in ('failure', 'timed_out', 'cancelled', 'action_required'):
                failed.setdefault(sha, item.get('html_url'))
            at=dt.datetime.fromisoformat(item.get("updated_at", item["created_at"]).replace("Z","+00:00")).timestamp()
            attempted[sha]=max(at,attempted.get(sha,0))
    now=dt.datetime.now(dt.timezone.utc).timestamp()
    selected=candidates(commits,released,running,attempted,now)[:20]
    for sha in selected:
        subprocess.run(["gh","workflow","run","release.yaml","--ref","main","-f",f"sha={sha}"],check=True)
    print(json.dumps({"reachable":len(commits),"released":len(set(commits)&released),"running":len(running),"dispatched":selected,"failed":failed,"invalidReleases":invalid,"remaining":len(set(commits)-released)},indent=2))
if __name__ == "__main__": main()
