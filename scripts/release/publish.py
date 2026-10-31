#!/usr/bin/env python3
import hashlib, json, os, pathlib, subprocess, tempfile
from reconcile import run

def main():
    version=os.environ["RELEASE_VERSION"]
    sha=run("git","rev-parse","HEAD")
    repo=os.environ["GITHUB_REPOSITORY"]
    release=subprocess.run(["gh","release","view",version,"--json","isDraft,targetCommitish"],capture_output=True,text=True)
    if release.returncode == 0 and not json.loads(release.stdout)["isDraft"]:
        print("Published release already exists; refusing mutation")
        return
    remote=subprocess.run(["git","ls-remote","origin",f"refs/tags/{version}"],capture_output=True,text=True,check=True).stdout.strip()
    if remote and remote.split()[0] != sha: raise SystemExit("Existing immutable tag points to another revision")
    if not remote:
        subprocess.run(["git","tag",version,sha],check=True)
        subprocess.run(["git","push","origin",f"refs/tags/{version}"],check=True)
    if release.returncode != 0:
        subprocess.run(["gh","release","create",version,"--draft","--prerelease","--title",version,"--notes",f"Exact source: {sha}. Development build; see compatibility manifest."],check=True)
    # Tags for this unpublished draft can be rebuilt on retry. Published releases never enter this path.
    digests={}
    owner=repo.split('/')[0].lower()
    for component in ("server","agent"):
        image=f"ghcr.io/{owner}/minecraft-modsync-{component}:{version}"
        with tempfile.TemporaryDirectory() as tmp:
            metadata=pathlib.Path(tmp)/"build.json"
            subprocess.run(["docker","buildx","build","--platform","linux/amd64,linux/arm64","--file",f"docker/{component}/Dockerfile","--tag",image,"--build-arg",f"RELEASE_VERSION={version}","--label",f"org.opencontainers.image.revision={sha}","--provenance=true","--sbom=true","--metadata-file",str(metadata),"--push","."],check=True)
            digest=json.loads(metadata.read_text())["containerimage.digest"]
            # Scan the pushed immutable digest; no release is finalized on failure.
            subprocess.run(["docker","run","--rm","aquasec/trivy:0.67.2","image","--exit-code","1","--severity","HIGH,CRITICAL",image.split(':')[0]+"@"+digest],check=True)
            digests[component]={"image":image,"digest":digest}
    artifacts=pathlib.Path("artifacts")
    files=sorted(p for p in artifacts.rglob('*') if p.is_file())
    names=[p.name for p in files]
    if len(names)!=len(set(names)): raise SystemExit("Release asset filename collision")
    checksums={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    manifest={"version":version,"sourceCommit":sha,"apiProtocol":1,"agentProtocol":1,"minimumClient":"0.1.0","minimumAgent":"0.1.0","images":digests,"artifactChecksums":checksums,"migrationRequirements":"Apply Flyway migrations; retain database, agent/client state and configured CA trust."}
    pathlib.Path("release-manifest.json").write_text(json.dumps(manifest,indent=2)+'\n')
    pathlib.Path("SHA256SUMS").write_text(''.join(f"{h}  {name}\n" for name,h in checksums.items()))
    for p in [*files,pathlib.Path("release-manifest.json"),pathlib.Path("SHA256SUMS")]:
        subprocess.run(["gh","release","upload",version,str(p),"--clobber"],check=True)
    subprocess.run(["gh","release","edit",version,"--draft=false","--prerelease"],check=True)
if __name__ == "__main__": main()
