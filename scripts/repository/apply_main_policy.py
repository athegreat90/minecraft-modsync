#!/usr/bin/env python3
"""Apply the requested main policy with an authenticated, network-capable gh CLI."""
import argparse
import json
import subprocess

MIGRATION_PATHS = {
    '.github/workflows/ci.yaml', '.github/workflows/reconcile.yaml', '.github/workflows/release.yaml',
    'scripts/release/reconcile.py', 'scripts/repository/apply_main_policy.py',
    'scripts/repository/test_main_policy.py', 'RELEASES.md', 'REPOSITORY_STRATEGY.md',
    'IMPLEMENTATION_REPORT.md', 'MEMORY.md', 'docs/review/PR.md',
}
PROTECTION = {
    'required_status_checks': {'strict': True, 'contexts': ['validate']},
    'enforce_admins': True,
    'required_pull_request_reviews': {
        'dismiss_stale_reviews': True,
        'required_approving_review_count': 1,
        'require_code_owner_reviews': False,
    },
    'restrictions': None,
    'allow_force_pushes': False,
    'allow_deletions': False,
    'required_conversation_resolution': True,
}

def safe_to_retire(comparison):
    return (comparison.get('status') in ('identical', 'ahead')
            and comparison.get('behind_by') == 0
            and not comparison.get('too_large', False)
            and all(item['filename'] in MIGRATION_PATHS for item in comparison.get('files', []))
            and len(comparison.get('files', [])) < 300)

def api(method, path, body=None, missing_ok=False):
    args=['gh', 'api', '--method', method, path]
    if body is not None: args += ['--input', '-']
    result=subprocess.run(args,input=json.dumps(body) if body is not None else None,text=True,capture_output=True)
    if result.returncode:
        if missing_ok and 'HTTP 404' in result.stderr: return None
        raise RuntimeError(result.stderr.strip())
    return json.loads(result.stdout) if result.stdout.strip() else None

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo',default='athegreat90/minecraft-modsync')
    args=parser.parse_args()
    repo='repos/'+args.repo
    metadata=api('GET',repo)
    main_ref=api('GET',repo+'/git/ref/heads/main')
    if metadata['default_branch'] != 'main': api('PATCH',repo,{'default_branch':'main'})
    protection=api('GET',repo+'/branches/main/protection',missing_ok=True)
    if protection is None:
        api('PUT',repo+'/branches/main/protection',PROTECTION)
        actual=api('GET',repo+'/branches/main/protection')
        if not (actual['enforce_admins']['enabled'] and 'validate' in actual['required_status_checks']['contexts']
                and actual['required_status_checks']['strict']
                and actual['required_pull_request_reviews']['required_approving_review_count'] >= 1
                and not actual['allow_force_pushes']['enabled'] and not actual['allow_deletions']['enabled']
                and actual['required_conversation_resolution']['enabled']):
            raise RuntimeError('Protection verification failed; master retained')
        print('main protected: PR, one approval, current validate check, resolved conversations; admins included; force pushes/deletion blocked')
    else:
        print('main is already protected; existing protection settings retained without replacement')
    legacy=api('GET',repo+'/git/ref/heads/master',missing_ok=True)
    if legacy is None:
        print('master is already absent'); return
    comparison=api('GET',repo+'/compare/master...main')
    if not safe_to_retire(comparison):
        raise RuntimeError('master retained: unique commits, application-code changes or incomplete comparison require review')
    # Stop on movement during validation. The REST deletion endpoint has no atomic expected-SHA parameter.
    if api('GET',repo+'/git/ref/heads/master')['object']['sha'] != legacy['object']['sha']:
        raise RuntimeError('master moved during validation; no deletion performed')
    if api('GET',repo+'/git/ref/heads/main')['object']['sha'] != main_ref['object']['sha']:
        raise RuntimeError('main moved during validation; no deletion performed')
    api('DELETE',repo+'/git/refs/heads/master')
    if api('GET',repo+'/git/ref/heads/master',missing_ok=True) is not None:
        raise RuntimeError('master deletion could not be verified')
    assert api('GET',repo)['default_branch']=='main'
    print('Verified main default and protected; duplicate master deleted')

if __name__=='__main__': main()
