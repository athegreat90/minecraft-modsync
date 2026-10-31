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
