import unittest
from prune import plan


def release(version, stamp, sha="a", draft=False):
    return {"tag_name": f"v{version}-dev.{stamp}." + sha * 40, "draft": draft}


class PruneTests(unittest.TestCase):
    def test_keeps_only_the_newest_release_of_each_version(self):
        releases = [release("0.5.0", "20261114184241", "a"), release("0.5.0", "20261114192712", "b"), release("0.4.7", "20261114143733", "c")]
        self.assertEqual(plan(releases), ["v0.5.0-dev.20261114184241." + "a" * 40])

    def test_a_single_release_per_version_is_never_deleted(self):
        self.assertEqual(plan([release("0.4.6", "20261114134921"), release("0.5.0", "20261114192712")]), [])

    def test_drafts_and_foreign_tags_are_ignored(self):
        releases = [release("0.5.0", "20261114184241", "a", draft=True), release("0.5.0", "20261114192712", "b"), {"tag_name": "v1.0.0", "draft": False}]
        self.assertEqual(plan(releases), [])

    def test_nothing_is_deleted_when_the_newest_release_is_not_complete(self):
        releases = [release("0.5.0", "20261114184241", "a"), release("0.5.0", "20261114192712", "b")]
        self.assertEqual(plan(releases, lambda r: False), [])

    def test_many_releases_of_one_version_keep_the_latest(self):
        releases = [release("0.1.0", f"2026111400000{i}", "d") for i in range(5)]
        self.assertEqual(len(plan(releases)), 4)
        self.assertNotIn(releases[-1]["tag_name"], plan(releases))


if __name__ == "__main__":
    unittest.main()
