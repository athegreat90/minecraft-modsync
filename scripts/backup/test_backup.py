import os, pathlib, subprocess, tempfile, unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
class BackupTransportTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root=pathlib.Path(self.temp.name); self.bin=self.root/'bin'; self.bin.mkdir()
        self.secret=self.root/'secret'; self.secret.write_text('test"with\\slashes#and space\n'.replace('\\n','\n'))
        self.output=self.root/'backup with spaces.sql'
        self.env={**os.environ,'PATH':str(self.bin)+os.pathsep+os.environ['PATH'],'TEST_SECRET':str(self.secret),'TEST_ROOT':str(self.root),'MODSYNC_RESTORE_CONFIRMED':'fresh-database'}
        self.executable('docker',"""#!/usr/bin/env python3
import os,subprocess,sys
script=sys.argv[sys.argv.index('-c')+1].replace('/run/secrets/db_root_password',os.environ['TEST_SECRET'])
raise SystemExit(subprocess.call(['sh','-eu','-c',script]))
""")
        self.executable('mariadb-dump',"""#!/usr/bin/env python3
import os,pathlib,stat,sys
cfg=pathlib.Path(sys.argv[1].split('=',1)[1])
assert stat.S_IMODE(cfg.stat().st_mode)==0o600
password=pathlib.Path(os.environ['TEST_SECRET']).read_text().rstrip('\\n')
escaped=password.replace('\\\\','\\\\\\\\').replace('"','\\\\"')
assert 'password="'+escaped+'"' in cfg.read_text()
print('CREATE TABLE fixture(id INT);')
""")
        self.executable('mariadb',"""#!/usr/bin/env python3
import pathlib,sys,os
pathlib.Path(os.environ['TEST_ROOT'],'imported.sql').write_text(sys.stdin.read())
""")
    def executable(self,name,body):
        path=self.bin/name; path.write_text(body); path.chmod(0o755)
    def run_script(self,name,ok=True):
        result=subprocess.run(['sh',str(ROOT/'scripts/backup'/name),'test-compose.yaml',str(self.output)],env=self.env,capture_output=True,text=True)
        if ok: self.assertEqual(result.returncode,0,result.stderr)
        else: self.assertNotEqual(result.returncode,0)
    def test_quoted_secret_export_and_moved_backup_import(self):
        self.run_script('export.sh')
        moved=self.root/'new-name.sql'; self.output.rename(moved); self.output.with_suffix('.sql.sha256').rename(self.root/'new-name.sql.sha256'); self.output=moved
        self.run_script('import.sh'); self.assertEqual((self.root/'imported.sql').read_text(),'CREATE TABLE fixture(id INT);\n')
    def test_modified_backup_rejected(self):
        self.run_script('export.sh'); self.output.write_text('modified'); self.run_script('import.sh',False); self.assertFalse((self.root/'imported.sql').exists())
    def test_existing_backup_preserved(self):
        self.output.write_text('keep'); self.run_script('export.sh',False); self.assertEqual(self.output.read_text(),'keep')
