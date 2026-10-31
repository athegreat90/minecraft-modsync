#!/usr/bin/env python3
"""Disposable TLS three-host, independent migration and MariaDB restore drill.
Requires Docker daemon/build access, JDK keytool and openssl. Never targets a live stack.
"""
import hashlib, json, os, pathlib, secrets, shutil, ssl, subprocess, sys, time, urllib.request, uuid, zipfile
ROOT=pathlib.Path(__file__).resolve().parents[2]
WORK=ROOT/'.local/integration'

def command(args, **kwargs):
    try:
        return subprocess.run(args,check=True,text=True,capture_output=True,**kwargs).stdout.strip()
    except subprocess.CalledProcessError as exc:
        # Arguments contain only fixture configuration/file paths, never real account credentials.
        for label, output in (("stdout",exc.stdout),("stderr",exc.stderr)):
            if output: print(f"Fixture command {label}:\n{output[-16000:]}",file=sys.stderr,flush=True)
        raise

def main():
    command(['docker','info'])
    if WORK.exists(): raise SystemExit('Integration workspace already exists; inspect it before removing or rerunning')
    WORK.mkdir(parents=True)
    fixtures=WORK/'fixtures'; pki=WORK/'pki'; fixtures.mkdir(); pki.mkdir(); (fixtures/'artifacts').mkdir(); (fixtures/'secrets').mkdir()
    for name in ['agent','agent2','client','client2','instance','instance2']:
        (WORK/name).mkdir(); (WORK/name).chmod(0o777)
    mods=fixtures/'minecraft/server/mods'; mods.mkdir(parents=True)
    artifacts=[]
    for project,kind in [('required','BOTH_REQUIRED'),('server','SERVER_ONLY'),('personal','CLIENT_ONLY')]:
        path=fixtures/'artifacts'/f'{project}.jar'
        with zipfile.ZipFile(path,'w') as jar:
            entry=zipfile.ZipInfo('META-INF/neoforge.mods.toml',(2020,1,1,0,0,0))
            jar.writestr(entry,f'modLoader="javafml"\nloaderVersion="[1,)"\nlicense="MIT"\n[[mods]]\nmodId="{project}"\nversion="1"')
        artifacts.append({'projectId':project,'versionId':project+'-v1','fileName':path.name,'sha512':hashlib.sha512(path.read_bytes()).hexdigest(),'size':path.stat().st_size,'url':'https://cdn.modrinth.com/data/'+path.name,'minecraftVersions':['1.21.1'],'loaders':['neoforge'],'classification':kind,'dependencies':[],'evidence':[]})
        if project!='personal': shutil.copy(path,mods/path.name)
    (fixtures/'artifacts.json').write_text(json.dumps(artifacts))
    for name in ['db_password','db_root_password','bootstrap_password','user_password']:
        (fixtures/'secrets'/name).write_text(secrets.token_hex(24))
    command(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(pki/'key.pem'),'-out',str(pki/'cert.pem'),'-days','2','-subj','/CN=modsync.test','-addext','subjectAltName=DNS:modsync.test,DNS:api.modrinth.com,DNS:cdn.modrinth.com,DNS:localhost,IP:127.0.0.1'])
    command(['openssl','pkcs12','-export','-in',str(pki/'cert.pem'),'-inkey',str(pki/'key.pem'),'-out',str(pki/'server.p12'),'-passout','pass:fixture-only'])
    command(['keytool','-importcert','-noprompt','-alias','fixture','-file',str(pki/'cert.pem'),'-keystore',str(pki/'trust.p12'),'-storetype','PKCS12','-storepass','fixture-only'])
    # Disposable fixture keys must be readable by the non-root test containers.
    for f in pki.iterdir(): f.chmod(0o644)
    trust='-Djavax.net.ssl.trustStore=/pki/trust.p12 -Djavax.net.ssl.trustStorePassword=fixture-only'
    mounts=[f'{fixtures}:/fixtures:ro',f'{pki}:/pki:ro']
    services={}
    for db in ['mariadb','mariadb2']:
        services[db]={'image':'mariadb:11.4.10','environment':{'MARIADB_DATABASE':'modsync','MARIADB_USER':'modsync','MARIADB_PASSWORD_FILE':'/run/secrets/db_password','MARIADB_ROOT_PASSWORD_FILE':'/run/secrets/db_root_password'},'volumes':[f'{fixtures}/secrets:/run/secrets:ro',f'{db}:/var/lib/mysql'],'networks':['database'],'healthcheck':{'test':['CMD','healthcheck.sh','--connect','--innodb_initialized'],'interval':'2s','timeout':'3s','retries':60}}
    for name,db in [('backend','mariadb'),('backend2','mariadb2')]:
        services[name]={'image':'modsync-integration-server:local','build':{'context':str(ROOT),'dockerfile':'docker/server/Dockerfile'},'environment':{'MODSYNC_DB_URL':f'jdbc:mariadb://{db}:3306/modsync','MODSYNC_DB_PASSWORD_FILE':'/fixtures/secrets/db_password','MODSYNC_BOOTSTRAP_PASSWORD_FILE':'/fixtures/secrets/bootstrap_password','SERVER_PORT':'8443','SERVER_SSL_KEY_STORE':'file:/pki/server.p12','SERVER_SSL_KEY_STORE_PASSWORD':'fixture-only','MODSYNC_HEALTH_URL':'https://localhost:8443/actuator/health','JAVA_TOOL_OPTIONS':trust},'volumes':mounts,'ports':['127.0.0.1::8443'],'networks':{'a':{'aliases':['modsync.test']},'c':{'aliases':['modsync.test']},'metadata':{} ,'database':{},'ingress':{}},'depends_on':{db:{'condition':'service_healthy'}}}
    services['modrinth']={'image':'python:3.13.7-alpine3.22','command':['python','/test/fake_modrinth.py'],'volumes':mounts+[f'{ROOT}/scripts/integration:/test:ro'],'networks':{'metadata':{'aliases':['api.modrinth.com']},'c':{'aliases':['cdn.modrinth.com']}}}
    for service,component,network,instance in [('agent','agent','a',None),('agent2','agent','a',None),('client','client','c','instance'),('client2','client','c','instance2')]:
        volumes=mounts+[f'{WORK/service}:/app/state']
        if component=='agent': volumes+=[f'{fixtures}/minecraft:/minecraft:ro']
        else: volumes+=[f'{WORK/instance}:/minecraft-instance']
        services[service]={'image':f'modsync-integration-{component}:local','build':{'context':str(ROOT),'dockerfile':'docker/agent/Dockerfile','args':{'COMPONENT':component}},'environment':{'JAVA_TOOL_OPTIONS':trust},'volumes':volumes,'networks':[network],'profiles':['tools']}
    compose=WORK/'compose.json'; compose.write_text(json.dumps({'name':'modsync-integration','services':services,'networks':{**{n:{'internal':True} for n in ['a','c','metadata','database']},'ingress':{}},'volumes':{'mariadb':{},'mariadb2':{}}},indent=2))
    base=['docker','compose','-f',str(compose)]
    def dc(*args): return command(base+list(args))
    def cli(service,*args): return dc('run','--rm','--no-deps',service,*args)
    results={}
    endpoint=''
    context=ssl.create_default_context(cafile=str(pki/'cert.pem'))
    admin_token=None
    def api(path,method='GET',body=None,token=None):
        data=None if body is None else json.dumps(body).encode()
        req=urllib.request.Request(endpoint+'/api/v1/'+path,data=data,method=method,headers={'Content-Type':'application/json','X-ModSync-Protocol':'1','X-ModSync-Version':'0.1.0',**({'Authorization':'Bearer '+token} if token else {})})
        with urllib.request.urlopen(req,context=context,timeout=30) as response:
            payload = response.read()
            try:
                return json.loads(payload)
            except json.JSONDecodeError as error:
                raise RuntimeError(f'Fixture API {method} {path}: status={response.status}, bytes={len(payload)}, correlation={response.headers.get("X-Correlation-ID")}') from error
    def admin(path,method='GET',body=None): return api(path,method,body,admin_token)
    def wait(service):
        nonlocal endpoint
        for attempt in range(15):
            try:
                port=dc('port',service,'8443').rsplit(':',1)[1]
                break
            except subprocess.CalledProcessError:
                if attempt == 14:
                    print(dc('logs','--no-color','--tail','120',service),file=sys.stderr,flush=True)
                    raise
                time.sleep(1)
        endpoint='https://127.0.0.1:'+port
        for _ in range(90):
            try: return api('info')
            except Exception: time.sleep(2)
        print(dc('logs','--no-color','--tail','120',service),file=sys.stderr,flush=True)
        raise RuntimeError('TLS backend readiness timeout')
    def enroll(service,plane):
        enrollment=admin('admin/enrollments','POST',{'ttlSeconds':600})
        (fixtures/'enrollment-token').write_text(enrollment['token'])
        cli(service,'setup','--server-url','https://modsync.test:8443','--trust-server-id',plane)
        result=cli(service,'register','--enrollment-token-file','/fixtures/enrollment-token')
        agent=result.split('Agent enrolled: ')[-1].strip()
        uuid.UUID(agent); cli(service,'map','primary','/minecraft/server/mods'); return agent
    def client_setup(service,plane):
        cli(service,'setup','--server-url','https://modsync.test:8443','--trust-server-id',plane)
        cli(service,'login','--username','player','--password-file','/fixtures/secrets/user_password')
        cli(service,'instances','add','game','/minecraft-instance','--minecraft-version','1.21.1','--loader','neoforge')
    try:
        dc('up','-d','--build','backend','modrinth'); plane=wait('backend')['controlPlaneId']
        dc('build','agent','client')
        admin_token=api('auth/login','POST',{'username':'admin','password':(fixtures/'secrets/bootstrap_password').read_text(),'deviceId':str(uuid.uuid4())})['token']
        server=admin('admin/servers','POST',{'name':'Fixture server','minecraftVersion':'1.21.1','loader':'neoforge'})
        agent=enroll('agent',plane); admin(f'admin/servers/{server}/assignment','PUT',{'agentId':agent,'mapping':'primary'}); cli('agent','scan','--server',server)
        inventory=admin(f'admin/servers/{server}/inventory')
        for artifact in artifacts[:2]: admin(f'admin/servers/{server}/classification','PUT',{'sha512':artifact['sha512'],'classification':artifact['classification'],'reason':'Deterministic fixture approval'})
        manifest=admin(f'admin/servers/{server}/publish','POST',{'scanId':inventory['scan']['scanId'],'artifacts':artifacts[:2]})
        admin('admin/users','POST',{'username':'player','password':(fixtures/'secrets/user_password').read_text()})
        player=api('auth/login','POST',{'username':'player','password':(fixtures/'secrets/user_password').read_text(),'deviceId':str(uuid.uuid4())})['token']
        profile=str(uuid.uuid4()); api('personal/'+profile,'PUT',{'id':profile,'name':'Portable preferences','artifacts':[artifacts[2]]},player)
        client_setup('client',plane)
        def sync(service):
            cli(service,'sync','plan','--instance','game','--server',server,'--profile',profile,'--out','/app/state/plan.json')
            cli(service,'sync','apply','--instance','game','--plan','/app/state/plan.json')
            cli(service,'sync','verify','--instance','game')
        sync('client'); sync('client'); results['three_host_and_idempotence']='passed'
        assert (WORK/'instance/mods/required.jar').read_bytes()==(fixtures/'artifacts/required.jar').read_bytes()
        assert not (WORK/'instance/mods/server.jar').exists()
        results['required_server_only_personal_hashes']='passed'
        client_setup('client2',plane); sync('client2'); results['client_migration']='passed'
        replacement=enroll('agent2',plane); admin(f'admin/servers/{server}/assignment','PUT',{'agentId':replacement,'mapping':'primary'})
        try: cli('agent','scan','--server',server); raise AssertionError('Old agent still owns server')
        except subprocess.CalledProcessError: pass
        cli('agent2','scan','--server',server)
        assert admin(f'servers/{server}/manifest')==manifest; assert len(admin('servers'))==1
        results['agent_migration_and_duplicate_ownership']='passed'
        # A failed delivery during a real backend outage must leave accepted state intact.
        dc('stop','backend')
        try: cli('agent2','scan'); raise AssertionError('Disconnected agent reported success')
        except subprocess.CalledProcessError: pass
        dc('start','backend'); wait('backend'); cli('agent2','scan'); assert admin(f'servers/{server}/manifest')==manifest
        results['network_interruption_and_reconnection']='passed'
        # Consistent backup with application writers stopped; restore to a separate MariaDB host.
        dc('stop','backend')
        backup=WORK/'backup.sql'
        command(['sh',str(ROOT/'scripts/backup/export.sh'),str(compose),str(backup)])
        dc('up','-d','--wait','mariadb2')
        command(['sh',str(ROOT/'scripts/backup/import.sh'),str(compose),str(backup)],env={**os.environ,'MODSYNC_DATABASE_SERVICE':'mariadb2','MODSYNC_RESTORE_CONFIRMED':'fresh-database'})
        dc('up','-d','backend2'); assert wait('backend2')['controlPlaneId']==plane
        assert admin(f'servers/{server}/manifest')==manifest
        cli('agent2','scan'); cli('client','connection','test'); cli('client2','connection','test'); sync('client2')
        results['backend_migration_dns_change_and_mariadb_restore']='passed'
        cli('client2','sync','rollback','--instance','game'); results['rollback']='passed'
    except Exception:
        print(dc('logs','--no-color','--tail','120','backend','backend2'),file=sys.stderr,flush=True)
        raise
    finally:
        (WORK/'results.json').write_text(json.dumps(results,indent=2)+'\n')
        if os.environ.get('MODSYNC_KEEP_FIXTURES')!='true': dc('down','--volumes','--remove-orphans')
    print(json.dumps(results,indent=2))
if __name__=='__main__': main()
