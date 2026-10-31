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
    return sorted((sha for sha in commits if sha not in released and sha not in running and now-attempted.get(sha,0)>=21600), key=lambda sha: attempted.get(sha,0))

def main():
    repo = os.environ["GITHUB_REPOSITORY"]
    commits = run("git","rev-list","--reverse","origin/main").splitlines()
    pages = json.loads(run("gh","api","--paginate","--slurp",f"repos/{repo}/releases?per_page=100"))
    released = {r["tag_name"].rsplit(".",1)[-1] for page in pages for r in page if not r["draft"]}
    pages = json.loads(run("gh","api","--paginate","--slurp",f"repos/{repo}/actions/workflows/release.yaml/runs?event=workflow_dispatch&per_page=100"))
    running, attempted = set(), {}
    for page in pages:
        for item in page["workflow_runs"]:
            match = re.fullmatch(r"release:([0-9a-f]{40})",item["display_title"])
            if not match: continue
            sha=match.group(1)
            if item["status"] in ("queued","in_progress","waiting","pending","requested"): running.add(sha)
            at=dt.datetime.fromisoformat(item["created_at"].replace("Z","+00:00")).timestamp()
            attempted[sha]=max(at,attempted.get(sha,0))
    now=dt.datetime.now(dt.timezone.utc).timestamp()
    selected=candidates(commits,released,running,attempted,now)[:20]
    for sha in selected:
        subprocess.run(["gh","workflow","run","release.yaml","--ref","main","-f",f"sha={sha}"],check=True)
    print(json.dumps({"reachable":len(commits),"released":len(set(commits)&released),"running":len(running),"dispatched":selected,"remaining":len(set(commits)-released)},indent=2))
if __name__ == "__main__": main()
