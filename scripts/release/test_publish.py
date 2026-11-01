import hashlib
import json
import os
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import publish
import state
from reconcile import version

SHA = 'a' * 40
VERSION = version(SHA, 0)
REPO = 'owner/repo'


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.previous = os.getcwd()
        os.chdir(self.temp.name)
        self.addCleanup(os.chdir, self.previous)
        self.env = patch.dict(os.environ, RELEASE_VERSION=VERSION, GITHUB_REPOSITORY=REPO, GITHUB_ACTOR='owner', GH_TOKEN='test')
        self.env.start()
        self.addCleanup(self.env.stop)
        self.release = dict(tag_name=VERSION, target_commitish=SHA, draft=True, prerelease=True, assets=[])
        for name in state.required_assets(VERSION) - {'release-manifest.json', 'SHA256SUMS'}:
            p = pathlib.Path('artifacts') / name
            p.parent.mkdir(exist_ok=True)
            p.write_bytes(b'build')

    def run_command(self, *args):
        if args[:3] == ('git', 'rev-parse', 'HEAD'):
            return SHA
        if args[:2] == ('git', 'show'):
            return '0'
        if args[:2] == ('git', 'ls-remote'):
            return SHA + '\trefs/tags/' + VERSION
        if '--slurp' in args:
            return json.dumps([[self.release]])
        return '{}'

    def test_draft_retry_uploads_and_finalizes_only_after_verification(self):
        def command(*args):
            if args[:3] == ('gh', 'api', f'repos/{REPO}/releases/tags/{VERSION}'):
                return json.dumps(self.release)
            return self.run_command(*args)
        def process(args, **kwargs):
            if args[:3] == ['docker', 'buildx', 'build']:
                pathlib.Path(args[args.index('--metadata-file') + 1]).write_text(json.dumps({'containerimage.digest': 'sha256:' + 'b'*64}))
            if args[:3] == ['gh', 'release', 'edit']:
                self.release['draft'] = False
        with patch.object(publish, 'run', side_effect=command), patch.object(publish, 'verify_source'), patch.object(publish, 'verify_complete') as verify, patch.object(publish, 'preflight'), patch.object(state, 'tag_source', return_value=SHA), patch.object(publish.subprocess, 'run', side_effect=process) as calls:
            publish.main()
        self.assertEqual(verify.call_count, 2)
        self.assertTrue(any(c.args[0][:3] == ['gh', 'release', 'upload'] for c in calls.call_args_list))
        self.assertFalse(self.release['draft'])

    def test_existing_release_source_mismatch_fails_without_mutation(self):
        self.release['draft'] = False
        with patch.object(publish, 'run', side_effect=self.run_command), patch.object(state, 'tag_source', return_value='b'*40), patch.object(publish.subprocess, 'run') as mutate:
            with self.assertRaises(ValueError):
                publish.main()
            mutate.assert_not_called()

    def test_missing_assets_fail_before_preflight(self):
        pathlib.Path('artifacts/modsync-server.jar').unlink()
        with patch.object(publish, 'run', side_effect=self.run_command), patch.object(state, 'tag_source', return_value=SHA), patch.object(publish, 'verify_source'), patch.object(publish, 'preflight') as preflight:
            with self.assertRaises(ValueError):
                publish.main()
            preflight.assert_not_called()

    def test_denied_registry_permission_prevents_publication(self):
        with patch.object(publish, 'run', return_value=SHA) as commands, patch.object(publish, 'registry_preflight', side_effect=PermissionError('GHCR denied')):
            with self.assertRaises(PermissionError):
                publish.preflight(REPO)
            self.assertEqual(commands.call_args.args[3], 'DELETE')
            self.assertIn('/git/refs/heads/modsync-release-preflight-', commands.call_args.args[4])

    def test_repository_permission_required(self):
        def command(*args):
            if args[0] == 'git':
                return SHA
            raise subprocess.CalledProcessError(1, args)
        with patch.object(publish, 'run', side_effect=command), patch.object(publish, 'registry_preflight') as registry:
            with self.assertRaises(subprocess.CalledProcessError):
                publish.preflight(REPO)
            registry.assert_not_called()

    def test_effective_write_permission_probe_is_cleaned_after_success(self):
        with patch.object(publish, 'run', return_value=SHA) as commands, patch.object(publish, 'registry_preflight') as registry:
            publish.preflight(REPO)
            registry.assert_called_once_with(REPO)
            create = commands.call_args_list[1].args
            delete = commands.call_args_list[2].args
            self.assertEqual(create[3], 'POST')
            self.assertEqual(delete[3], 'DELETE')
            self.assertEqual(create[8], 'sha='+SHA)
            self.assertEqual(create[6].removeprefix('ref=refs/'), delete[4].split('/git/refs/',1)[1])

    def test_image_failure_leaves_draft(self):
        with patch.object(publish, 'run', side_effect=self.run_command), patch.object(state, 'tag_source', return_value=SHA), patch.object(publish, 'verify_source'), patch.object(publish, 'preflight'), patch.object(publish.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, 'docker')) as calls:
            with self.assertRaises(subprocess.CalledProcessError):
                publish.main()
            self.assertEqual(calls.call_count, 1)
            self.assertTrue(self.release['draft'])

    def test_incomplete_remote_assets_rejected(self):
        with patch.object(state, 'verify_source'):
            with self.assertRaises(ValueError):
                state.verify_complete(REPO, self.release, SHA)

    def test_complete_release_checks_hashes_and_registry(self):
        def command(*args):
            if args[:3] == ('gh', 'release', 'download'):
                root = pathlib.Path(args[-1])
                checksums = {}
                for p in pathlib.Path('artifacts').iterdir():
                    (root / p.name).write_bytes(p.read_bytes())
                    checksums[p.name] = hashlib.sha256(p.read_bytes()).hexdigest()
                manifest = dict(version=VERSION, sourceCommit=SHA, artifactChecksums=checksums, images={c: {'image': f'ghcr.io/owner/minecraft-modsync-{c}:{VERSION}', 'digest': 'sha256:'+'b'*64} for c in ('server', 'agent')})
                (root / 'release-manifest.json').write_text(json.dumps(manifest))
                (root / 'SHA256SUMS').write_text(''.join(f'{h}  {n}\n' for n, h in checksums.items()))
            return '{}'
        self.release['assets'] = [dict(name=n, size=5, state='uploaded') for n in state.required_assets(VERSION)]
        with patch.object(state, 'verify_source'), patch.object(state, 'run', side_effect=command) as calls:
            state.verify_complete(REPO, self.release, SHA)
            self.assertEqual(sum(c.args[:3] == ('docker', 'buildx', 'imagetools') for c in calls.call_args_list), 2)

    def test_registry_preflight_requests_push_and_cleans_upload_sessions(self):
        import io
        from unittest.mock import MagicMock
        calls = []
        def response(request, **kwargs):
            calls.append(request)
            result = MagicMock()
            result.headers = {'Location': '/v2/owner/image/blobs/uploads/session'}
            if '/token?' in request.full_url:
                result = io.StringIO(json.dumps({'token': 'scoped-test-token'}))
            context = MagicMock()
            context.__enter__.return_value = result
            return context
        with patch('urllib.request.urlopen', side_effect=response):
            publish.registry_preflight(REPO)
        self.assertEqual([r.get_method() for r in calls], ['GET', 'POST', 'DELETE'] * 2)
        self.assertIn('pull%2Cpush', calls[0].full_url)

    def test_tag_target_mismatch_is_rejected(self):
        self.release['target_commitish'] = 'b'*40
        with patch.object(state, 'tag_source', return_value=SHA):
            with self.assertRaises(ValueError):
                state.verify_source(REPO, self.release, SHA)

    def test_upload_failure_does_not_finalize(self):
        def process(args, **kwargs):
            if args[:3] == ['docker', 'buildx', 'build']:
                pathlib.Path(args[args.index('--metadata-file') + 1]).write_text(json.dumps({'containerimage.digest': 'sha256:' + 'b'*64}))
            if args[:3] == ['gh', 'release', 'upload']:
                raise subprocess.CalledProcessError(1, args)
        with patch.object(publish, 'run', side_effect=self.run_command), patch.object(state, 'tag_source', return_value=SHA), patch.object(publish, 'verify_source'), patch.object(publish, 'preflight'), patch.object(publish.subprocess, 'run', side_effect=process) as calls:
            with self.assertRaises(subprocess.CalledProcessError):
                publish.main()
            self.assertFalse(any(c.args[0][:3] == ['gh', 'release', 'edit'] for c in calls.call_args_list))
