import pathlib
import tempfile
import unittest

import check_links


class CheckLinksTests(unittest.TestCase):
    def tree(self, files):
        root = pathlib.Path(tempfile.mkdtemp())
        for name, text in files.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return root

    def test_valid_links_and_anchors_pass(self):
        root = self.tree({"a.md": "# A\n[b](docs/b.md#second-part) [self](#a)\n", "docs/b.md": "# B\n## Second part\n"})
        self.assertEqual([], check_links.broken_links(root))

    def test_missing_file_is_reported(self):
        root = self.tree({"a.md": "[gone](nope.md)\n"})
        self.assertEqual(1, len(check_links.broken_links(root)))

    def test_missing_anchor_is_reported(self):
        root = self.tree({"a.md": "[x](b.md#absent)\n", "b.md": "# B\n"})
        self.assertIn("missing anchor", check_links.broken_links(root)[0])

    def test_links_inside_code_fences_and_external_links_are_ignored(self):
        root = self.tree({"a.md": "```\n[x](nope.md)\n```\n[web](https://example.test/x.md)\n"})
        self.assertEqual([], check_links.broken_links(root))

    def test_slug_matches_github_rules(self):
        self.assertEqual("3-google-sso", check_links.slug(" 3. Google SSO"))
        self.assertEqual("user-status-v5", check_links.slug("User status (V5)"))


if __name__ == "__main__":
    unittest.main()
