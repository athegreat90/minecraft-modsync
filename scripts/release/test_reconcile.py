import unittest
from reconcile import candidates, version
class ReleaseTests(unittest.TestCase):
    def test_version_stable_and_unique(self):
        self.assertEqual(version("a"*40,0),"v0.1.0-dev.19700101000000."+"a"*40)
        self.assertNotEqual(version("a"*40,0),version("b"*40,0))
    def test_multicommit_and_canceled_pending_not_lost(self):
        self.assertEqual(candidates(["a","b","c"],{"a"},set(),{"b":1},30000),["c","b"])
    def test_active_and_published_skip(self):
        self.assertEqual(candidates(["a","b","c"],{"a"},{"b"},{"c":29999},30000),[])
    def test_failures_cannot_starve_new_commits(self):
        self.assertEqual(candidates(["old","new"],set(),set(),{"old":1},30000),["new","old"])

    def test_latest_precedes_unattempted_history(self):
        self.assertEqual(candidates(['root', 'branch', 'merge'], set(), set(), {'merge': 1}, 30000), ['merge', 'root', 'branch'])

    def test_head_cooldown_preserves_backfill(self):
        self.assertEqual(candidates(['old', 'head'], set(), set(), {'head': 29999}, 30000), ['old'])

    def test_canceled_attempt_recovers_at_cooldown_boundary(self):
        self.assertEqual(candidates(['head'], set(), set(), {'head': 100}, 21700), ['head'])

    def test_running_head_does_not_duplicate_dispatch(self):
        self.assertEqual(candidates(['old', 'head'], set(), {'head'}, {}, 30000), ['old'])

    def test_reconcile_reports_canceled_and_skips_active_merge(self):
        import contextlib
        import io
        import json
        import os
        from unittest.mock import patch
        import reconcile
        commits = ['a'*40, 'b'*40, 'c'*40]
        runs = [{'display_title': 'release:'+commits[1], 'status': 'in_progress', 'created_at': '1970-01-01T00:00:01Z'},
                {'display_title': 'release:'+commits[0], 'status': 'completed', 'conclusion': 'cancelled', 'created_at': '1970-01-01T00:00:01Z', 'html_url': 'canceled-run'}]
        def command(*args):
            if args[0] == 'git':
                return '\n'.join(commits)
            if '/releases?' in args[-1]:
                return '[[]]'
            return json.dumps([{'workflow_runs': runs}])
        output = io.StringIO()
        with patch.dict(os.environ, GITHUB_REPOSITORY='owner/repo'), patch.object(reconcile, 'run', side_effect=command), patch.object(reconcile.subprocess, 'run') as dispatch, contextlib.redirect_stdout(output):
            reconcile.main()
        self.assertEqual([c.args[0][-1] for c in dispatch.call_args_list], ['sha='+commits[2], 'sha='+commits[0]])
        self.assertEqual(json.loads(output.getvalue())['failed'], {commits[0]: 'canceled-run'})
