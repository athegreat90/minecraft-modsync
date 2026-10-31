#!/usr/bin/env python3
from pathlib import Path
root=Path(__file__).resolve().parents[1]
for name in ('domain','protocol','agent','client','scanner','resolver'):
    for path in (root/f'modsync-{name}').rglob('*.kt*'):
        source=path.read_text()
        assert 'org.springframework' not in source, path
        assert 'org.mariadb' not in source, path
        assert 'modsync-server' not in source, path
for name in ('agent','client'):
    source=(root/f'docker/{name}/compose.yaml').read_text() if name=='agent' else ''
    assert 'docker.sock' not in source
    if name=='agent': assert ':ro' in source and 'ports:' not in source
server=(root/'docker/server/compose.yaml').read_text()
assert '5432' not in server and 'mariadb:11.4.10' in server
print('Module and deployment boundary checks passed')
