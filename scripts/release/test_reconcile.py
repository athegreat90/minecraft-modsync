import unittest
from reconcile import TAG_PATTERN, candidates, version
class ReleaseTests(unittest.TestCase):
    def test_current_and_legacy_tags_are_recognised(self):
        self.assertTrue(TAG_PATTERN.fullmatch(version("a"*40,0)))
        self.assertTrue(TAG_PATTERN.fullmatch("v0.1.0-dev.19700101000000."+"a"*40))
    def test_version_stable_and_unique(self):
        self.assertEqual(version("a"*40,0),"v0.6.0-dev.19700101000000."+"a"*40)
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

    def test_repeated_failures_back_off_exponentially_up_to_a_cap(self):
        hour = 3600
        # One failure keeps the 6 hour cooldown, each further failure doubles it.
        self.assertEqual(candidates(['x'], set(), set(), {'x': 0}, 6 * hour, {'x': 1}), ['x'])
        self.assertEqual(candidates(['x'], set(), set(), {'x': 0}, 6 * hour, {'x': 2}), [])
        self.assertEqual(candidates(['x'], set(), set(), {'x': 0}, 12 * hour, {'x': 2}), ['x'])
        # 21 failed attempts must not push the retry beyond seven days.
        self.assertEqual(candidates(['x'], set(), set(), {'x': 0}, 7 * 24 * hour, {'x': 21}), ['x'])
        self.assertEqual(candidates(['x'], set(), set(), {'x': 0}, 7 * 24 * hour - 1, {'x': 21}), [])

    def test_unknown_failure_count_keeps_the_default_cooldown(self):
        self.assertEqual(candidates(['x'], set(), set(), {'x': 0}, 21600, {}), ['x'])

    def test_floor_excludes_the_unbuildable_commit(self):
        import reconcile
        self.assertTrue(reconcile.RELEASE_FLOOR.startswith('7c29453'))

    def test_commits_with_different_workflows_cannot_be_published(self):
        trees = {'old': 'tree-1', 'same': 'tree-2', 'head': 'tree-2', 'done': 'tree-1'}
        from reconcile import unpublishable
        self.assertEqual(unpublishable(['old', 'same', 'head'], set(), trees.get), ['old'])
        self.assertEqual(unpublishable(['done', 'same', 'head'], {'done'}, trees.get), [])
        self.assertEqual(unpublishable([], set(), trees.get), [])
        self.assertEqual(unpublishable(['gone', 'head'], set(), trees.get), ['gone'])

    def test_reconcile_never_dispatches_or_counts_unpublishable_commits(self):
        import contextlib
        import io
        import json
        import os
        from unittest.mock import patch
        import reconcile
        commits = ['a'*40, 'b'*40, 'c'*40]
        trees = {commits[0]: 'old-workflows', commits[1]: 'current', commits[2]: 'current'}
        def command(*args):
            if args[:2] == ('git', 'rev-parse'):
                return trees[args[2].split(':')[0]]
            if args[0] == 'git':
                return chr(10).join(commits)
            if '/releases?' in args[-1]:
                return '[[]]'
            return json.dumps([{'workflow_runs': []}])
        output = io.StringIO()
        with patch.dict(os.environ, GITHUB_REPOSITORY='owner/repo'), patch.object(reconcile, 'run', side_effect=command), patch.object(reconcile.subprocess, 'run') as dispatch, contextlib.redirect_stdout(output):
            reconcile.main()
        self.assertEqual([c.args[0][-1] for c in dispatch.call_args_list], ['sha='+commits[2], 'sha='+commits[1]])
        report = json.loads(output.getvalue())
        self.assertEqual(report['unpublishable'], [commits[0]])
        self.assertEqual(report['remaining'], 2)

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

    def test_tag_version_is_the_application_version(self):
        from reconcile import tag_version
        self.assertEqual(tag_version("v0.5.0-dev.19700101000000." + "a" * 40), "0.5.0")

    def test_source_version_is_read_from_the_commits_own_tooling(self):
        from unittest.mock import patch
        import reconcile
        with patch.object(reconcile, "run", return_value='    return f"v0.3.2-dev.{stamp}.{sha}"'):
            self.assertEqual(reconcile.source_version("a" * 40), "0.3.2")
        with patch.object(reconcile, "run", return_value="nothing useful"):
            self.assertIsNone(reconcile.source_version("a" * 40))

    def test_only_commits_older_than_a_release_of_the_same_version_are_superseded(self):
        from reconcile import superseded
        commits = ["a", "b", "c", "d", "e"]
        versions = {"a": "0.1.0", "b": "0.1.0", "c": "0.1.0", "d": "0.2.0", "e": "0.2.0"}
        # c is the newest released 0.1.0 build, so a and b are replaced; nothing of 0.2.0 is released, so d and e still build.
        self.assertEqual(superseded(commits, {"c"}, versions), {"a", "b"})
        # a newer commit of the same version after the release is not superseded.
        self.assertEqual(superseded(["a", "b", "c"], {"b"}, {"a": "1", "b": "1", "c": "1"}), {"a"})

    def test_commits_with_unknown_versions_are_never_superseded(self):
        from reconcile import superseded
        self.assertEqual(superseded(["a", "b", "c"], {"c"}, {"a": None, "c": "1"}), set())

    def test_reconcile_does_not_rebuild_commits_replaced_by_a_later_release_of_the_same_version(self):
        import contextlib
        import io
        import json
        import os
        from unittest.mock import patch
        import reconcile
        commits = ["a" * 40, "b" * 40, "c" * 40]
        release = {"draft": False, "tag_name": "v0.5.0-dev.19700101000001." + commits[1]}

        def command(*args):
            if args[:2] == ("git", "rev-parse"):
                return "same-tree"
            if args[:2] == ("git", "show"):
                return 'return f"v0.5.0-dev.{stamp}.{sha}"'
            if args[0] == "git":
                return chr(10).join(commits)
            if "/releases?" in args[-1]:
                return json.dumps([[release]])
            return json.dumps([{"workflow_runs": []}])

        import state
        output = io.StringIO()
        with patch.dict(os.environ, GITHUB_REPOSITORY="owner/repo"), patch.object(reconcile, "run", side_effect=command), patch.object(state, "verify_complete"), patch.object(reconcile.subprocess, "run") as dispatch, contextlib.redirect_stdout(output):
            reconcile.main()
        report = json.loads(output.getvalue())
        self.assertEqual(report["superseded"], [commits[0]])
        self.assertEqual([c.args[0][-1] for c in dispatch.call_args_list], ["sha=" + commits[2]])

