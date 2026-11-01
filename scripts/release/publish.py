#!/usr/bin/env python3
import hashlib, json, os, pathlib, subprocess, tempfile, uuid
from reconcile import run, version as release_version
from state import required_assets, verify_complete, verify_source


def registry_preflight(repo):
    """Ask GHCR for actual push authorization before creating any draft/tag."""
    import base64
    import urllib.parse
    import urllib.request
    owner = repo.split('/')[0].lower()
    credentials = base64.b64encode((os.environ['GITHUB_ACTOR'] + ':' + os.environ['GH_TOKEN']).encode()).decode()
    for component in ('server', 'agent'):
        name = f'{owner}/minecraft-modsync-{component}'
        query = urllib.parse.urlencode({'service': 'ghcr.io', 'scope': f'repository:{name}:pull,push'})
        request = urllib.request.Request('https://ghcr.io/token?' + query, headers={'Authorization': 'Basic ' + credentials})
        with urllib.request.urlopen(request, timeout=30) as response:
            token = json.load(response)['token']
        headers = {'Authorization': 'Bearer ' + token}
        request = urllib.request.Request(f'https://ghcr.io/v2/{name}/blobs/uploads/', headers=headers, method='POST')
        with urllib.request.urlopen(request, timeout=30) as response:
            location = urllib.parse.urljoin('https://ghcr.io', response.headers['Location'])
        if urllib.parse.urlparse(location).netloc != 'ghcr.io':
            raise ValueError('Unexpected registry upload location')
        # GHCR rejects DELETE on upload sessions (405). Creating the scoped
        # session verifies push permission; its empty session expires server-side.


def preflight(repo):
    # Repository permissions.push describes user access, not the effective
    # contents permission of an Actions installation token. Verify the write API.
    sha = run('git', 'rev-parse', 'HEAD')
    ref = 'heads/modsync-release-preflight-' + uuid.uuid4().hex
    run('gh', 'api', '--method', 'POST', f'repos/{repo}/git/refs', '-f', f'ref=refs/{ref}', '-f', f'sha={sha}')
    try:
        registry_preflight(repo)
    finally:
        run('gh', 'api', '--method', 'DELETE', f'repos/{repo}/git/refs/{ref}')


def main():
    version=os.environ["RELEASE_VERSION"]
    sha=run("git","rev-parse","HEAD")
    repo=os.environ["GITHUB_REPOSITORY"]
    expected = release_version(sha, run('git', 'show', '-s', '--format=%ct', 'HEAD'))
    if version != expected:
        raise ValueError('Release version does not identify source commit')
    pages = json.loads(run('gh', 'api', '--paginate', '--slurp', f'repos/{repo}/releases?per_page=100'))
    matches = [r for page in pages for r in page if r['tag_name'] == version]
    if len(matches) > 1:
        raise ValueError('Duplicate release tags')
    release = matches[0] if matches else None
    remote = run('git', 'ls-remote', 'origin', f'refs/tags/{version}', f'refs/tags/{version}^{{}}')
    if remote:
        from state import tag_source
        if tag_source(repo, version) != sha:
            raise ValueError('Existing immutable tag points to another revision')
    if release:
        verify_source(repo, release, sha)
        if not release['draft']:
            verify_complete(repo, release, sha)
            print('Existing published release verified')
            return
    artifacts=pathlib.Path('artifacts')
    files=sorted(p for p in artifacts.rglob('*') if p.is_file())
    names=[p.name for p in files]
    if len(names)!=len(set(names)):
        raise ValueError('Release asset filename collision')
    if not required_assets(version) - {'release-manifest.json', 'SHA256SUMS'} <= set(names) or any(p.stat().st_size == 0 for p in files):
        raise ValueError('Missing or empty build artifacts')
    preflight(repo)
    if not remote:
        run('gh', 'api', '--method', 'POST', f'repos/{repo}/git/refs', '-f', f'ref=refs/tags/{version}', '-f', f'sha={sha}')
    if not release:
        run('gh', 'release', 'create', version, '--target', sha, '--draft', '--prerelease', '--title', version, '--notes', f'Exact source: {sha}. Development build; see compatibility manifest.')
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
            subprocess.run(["docker","run","--rm","-e","TRIVY_USERNAME","-e","TRIVY_PASSWORD","aquasec/trivy:0.67.2","image","--exit-code","1","--severity","HIGH,CRITICAL",image.split(':')[0]+"@"+digest],check=True,env={**os.environ,"TRIVY_USERNAME":os.environ["GITHUB_ACTOR"],"TRIVY_PASSWORD":os.environ["GH_TOKEN"]})
            digests[component]={"image":image,"digest":digest}
    checksums={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    manifest={"version":version,"sourceCommit":sha,"apiProtocol":1,"agentProtocol":1,"minimumClient":"0.1.0","minimumAgent":"0.1.0","images":digests,"artifactChecksums":checksums,"migrationRequirements":"Apply Flyway migrations; retain database, agent/client state and configured CA trust."}
    pathlib.Path("release-manifest.json").write_text(json.dumps(manifest,indent=2)+'\n')
    pathlib.Path("SHA256SUMS").write_text(''.join(f"{h}  {name}\n" for name,h in checksums.items()))
    for p in [*files,pathlib.Path("release-manifest.json"),pathlib.Path("SHA256SUMS")]:
        subprocess.run(["gh","release","upload",version,str(p),"--clobber"],check=True)
    release = json.loads(run('gh', 'api', f'repos/{repo}/releases/tags/{version}'))
    verify_complete(repo, release, sha)
    subprocess.run(["gh","release","edit",version,"--draft=false","--prerelease"],check=True)
    visible = json.loads(run('gh', 'api', f'repos/{repo}/releases/tags/{version}'))
    if visible['draft'] or not visible['prerelease']:
        raise ValueError('Release did not become a visible prerelease')
    verify_complete(repo, visible, sha)
if __name__ == "__main__": main()
