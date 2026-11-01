"""Immutable release evidence shared by reconciliation and publication."""
import hashlib
import json
import pathlib
import re
import subprocess
import tempfile
from reconcile import run

PLATFORMS = ('linux-x64', 'linux-arm64', 'windows-x64')


def required_assets(version):
    names = {'modsync-server.jar', 'release-manifest.json', 'SHA256SUMS'}
    names.update(f'modsync-{component}-{version}.zip' for component in ('agent', 'client', 'client-tui'))
    for platform in PLATFORMS:
        suffix = '.exe' if platform.startswith('windows') else ''
        names.update(f'{component}-{platform}{suffix}' for component in ('modsync', 'modsync-agent'))
        names.add(f'benchmark-{platform}.json')
    return names


def tag_source(repo, tag):
    ref = json.loads(run('gh', 'api', f'repos/{repo}/git/ref/tags/{tag}'))['object']
    for _ in range(10):
        if ref['type'] == 'commit':
            return ref['sha']
        if ref['type'] != 'tag':
            break
        ref = json.loads(run('gh', 'api', f'repos/{repo}/git/tags/{ref["sha"]}'))['object']
    raise ValueError('Tag does not resolve to a commit')


def verify_source(repo, release, sha):
    if release['target_commitish'] != sha or tag_source(repo, release['tag_name']) != sha:
        raise ValueError('Release target or immutable tag differs from expected source')


def verify_complete(repo, release, sha):
    verify_source(repo, release, sha)
    tag = release['tag_name']
    assets = release['assets']
    names = [a['name'] for a in assets]
    if len(names) != len(set(names)) or not required_assets(tag) <= set(names):
        raise ValueError('Missing or duplicate release assets')
    if any(a['size'] <= 0 or a['state'] != 'uploaded' for a in assets):
        raise ValueError('Incomplete uploaded assets')
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        for asset in assets:
            name = asset['name']
            if pathlib.Path(name).name != name:
                raise ValueError('Invalid release asset filename')
            with (root / name).open('wb') as output:
                subprocess.run([
                    'gh', 'api', f'repos/{repo}/releases/assets/{asset["id"]}',
                    '-H', 'Accept: application/octet-stream',
                ], check=True, stdout=output)
        manifest = json.loads((root / 'release-manifest.json').read_text())
        if manifest['sourceCommit'] != sha or manifest['version'] != tag:
            raise ValueError('Manifest source/version mismatch')
        checksums = manifest['artifactChecksums']
        expected = set(names) - {'release-manifest.json', 'SHA256SUMS'}
        if set(checksums) != expected:
            raise ValueError('Manifest does not cover every artifact')
        if (root / 'SHA256SUMS').read_text() != ''.join(f'{h}  {name}\n' for name, h in checksums.items()):
            raise ValueError('Checksum index mismatch')
        for name, checksum in checksums.items():
            if hashlib.sha256((root / name).read_bytes()).hexdigest() != checksum:
                raise ValueError(f'Checksum mismatch: {name}')
        if set(manifest['images']) != {'agent', 'server'}:
            raise ValueError('Missing image digests')
        for component, image in manifest['images'].items():
            expected_image = f'ghcr.io/{repo.split("/")[0].lower()}/minecraft-modsync-{component}:{tag}'
            if image['image'] != expected_image or not re.fullmatch(r'sha256:[0-9a-f]{64}', image['digest']):
                raise ValueError('Invalid image identity')
            run('docker', 'buildx', 'imagetools', 'inspect', expected_image.split(':')[0] + '@' + image['digest'])
    return manifest
