import unittest
from apply_main_policy import safe_to_retire, PROTECTION

class MainPolicyTests(unittest.TestCase):
    def test_identical_is_safe(self):
        self.assertTrue(safe_to_retire({'status':'identical','behind_by':0,'files':[]}))
    def test_only_branch_migration_changes_are_safe(self):
        self.assertTrue(safe_to_retire({'status':'ahead','behind_by':0,'files':[{'filename':'.github/workflows/reconcile.yaml'}]}))
    def test_code_changes_or_unique_commits_block_deletion(self):
        self.assertFalse(safe_to_retire({'status':'ahead','behind_by':0,'files':[{'filename':'modsync-domain/build.gradle.kts'}]}))
        self.assertFalse(safe_to_retire({'status':'diverged','behind_by':1,'files':[]}))
        self.assertFalse(safe_to_retire({'status':'ahead','behind_by':0,'too_large':True,'files':[]}))
    def test_baseline_cannot_force_push_or_delete_main(self):
        self.assertFalse(PROTECTION['allow_force_pushes']); self.assertFalse(PROTECTION['allow_deletions'])
        self.assertTrue(PROTECTION['enforce_admins']); self.assertEqual(PROTECTION['required_status_checks']['contexts'],['validate'])
