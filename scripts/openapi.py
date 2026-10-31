#!/usr/bin/env python3
"""Generate the checked API schema from the small shared Kotlin DTO vocabulary."""
import json, pathlib, re, sys
ROOT=pathlib.Path(__file__).resolve().parents[1]
SOURCES=[ROOT/'modsync-domain/src/main/kotlin/net/modsync/Domain.kt',ROOT/'modsync-protocol/src/main/kotlin/net/modsync/Protocol.kt']

def parts(text):
    result=[]; start=0; depth=0; quoted=False; escaped=False
    for index,char in enumerate(text):
        if quoted:
            if escaped: escaped=False
            elif char=='\\': escaped=True
            elif char=='"': quoted=False
        elif char=='"': quoted=True
        elif char in '(<[': depth+=1
        elif char in ')>]': depth-=1
        elif char==',' and depth==0: result.append(text[start:index].strip()); start=index+1
    result.append(text[start:].strip()); return result

def model_schema(kind):
    if kind.endswith('?'): return {'anyOf':[model_schema(kind[:-1]),{'type':'null'}]}
    primitives={'String':{'type':'string'},'Long':{'type':'integer','format':'int64'},'Int':{'type':'integer','format':'int32'},'Boolean':{'type':'boolean'}}
    if kind in primitives: return dict(primitives[kind])
    for collection in ('List','Set'):
        if kind.startswith(collection+'<'):
            return {'type':'array','items':model_schema(kind[len(collection)+1:-1]),**({'uniqueItems':True} if collection=='Set' else {})}
    if kind.startswith('Map<String, '): return {'type':'object','additionalProperties':model_schema(kind[len('Map<String, '):-1])}
    if not re.fullmatch(r'[A-Za-z]+',kind): raise ValueError('Unsupported DTO type '+kind)
    return {'$ref':'#/components/schemas/'+kind}

def schemas():
    models={}
    for source in SOURCES:
        text=source.read_text()
        for match in re.finditer(r'@Serializable enum class (\w+)\s*\{([^}]+)\}',text):
            models[match[1]]={'type':'string','enum':[v.strip() for v in match[2].split(',')]}
        for match in re.finditer(r'@Serializable data class (\w+)\(',text):
            depth=1; index=match.end(); start=index; quoted=False; escaped=False
            while depth:
                char=text[index]
                if quoted:
                    if escaped: escaped=False
                    elif char=='\\': escaped=True
                    elif char=='"': quoted=False
                elif char=='"': quoted=True
                elif char=='(': depth+=1
                elif char==')': depth-=1
                index+=1
            props={}; required=[]
            for field in parts(text[start:index-1]):
                field_match=re.fullmatch(r'val (\w+): (.+?)(?: = .+)?',field,re.S)
                if not field_match: raise ValueError('Unsupported DTO field '+field)
                name,kind=field_match.groups(); props[name]=model_schema(kind.strip())
                if ' = ' not in field: required.append(name)
                if name in ('password','token','refreshToken'): props[name]['format']='password'
            models[match[1]]={'type':'object','properties':props,**({'required':required} if required else {})}
    assert len(models)>=25
    encoded=json.dumps(models)
    for reference in re.findall(r'#/components/schemas/(\w+)',encoded): assert reference in models,reference
    return models

def build():
    # method, route, request type, response schema, authority
    routes=[
        ('GET','info',None,'Capabilities','public'),
        ('POST','auth/login','Login','Credentials','public'),
        ('POST','auth/refresh','RefreshRequest','Credentials','public'),
        ('POST','enroll','Enrollment','Credentials','public'),
        ('POST','admin/users','UserRegistration','String','ADMIN'),
        ('POST','admin/enrollments','EnrollmentRequest','EnrollmentToken','ADMIN'),
        ('POST','admin/enrollments/revoke',None,'Boolean','ADMIN'),
        ('GET','admin/agents',None,'raw-array','ADMIN'),
        ('POST','admin/servers','ServerRegistration','String','ADMIN'),
        ('PUT','admin/servers/{id}/assignment','Transfer','Assignment','ADMIN'),
        ('POST','admin/agents/{id}/revoke',None,'Boolean','ADMIN'),
        ('GET','agent/assignments',None,'List<Assignment>','AGENT'),
        ('POST','agent/heartbeat','Heartbeat','Boolean','AGENT'),
        ('POST','agent/credentials/rotate',None,'Credentials','AGENT'),
        ('POST','agent/snapshots','SnapshotBegin','SnapshotBegin','AGENT'),
        ('PUT','agent/snapshots/{id}/batches','SnapshotBatch','Boolean','AGENT'),
        ('POST','agent/snapshots/{id}/commit',None,'SnapshotAck','AGENT'),
        ('GET','servers',None,'List<ServerInfo>','CLIENT or ADMIN'),
        ('GET','servers/{id}/manifest',None,'Manifest','CLIENT or ADMIN'),
        ('GET','admin/servers/{id}/inventory',None,'SnapshotView','ADMIN'),
        ('GET','admin/servers/{id}/identified',None,'raw','ADMIN'),
        ('PUT','admin/servers/{id}/classification','OverrideRequest','Boolean','ADMIN'),
        ('POST','admin/servers/{id}/preview','PublishRequest','Manifest','ADMIN'),
        ('POST','admin/servers/{id}/publish','PublishRequest','Manifest','ADMIN'),
        ('GET','mods/{project}/updates',None,'raw-array','CLIENT or ADMIN'),
        ('GET','personal',None,'List<PersonalProfile>','CLIENT or ADMIN'),
        ('PUT','personal/{id}','PersonalProfile','PersonalProfile','owner'),
        ('GET','devices',None,'List<String>','owner'),
        ('POST','devices/{id}/revoke',None,'Boolean','owner'),
        ('POST','sync/reports','SyncReport','Boolean','CLIENT or ADMIN'),
        ('GET','openapi.json',None,'raw','CLIENT or ADMIN'),
    ]
    paths={}
    for method,path,request,response,role in routes:
        schema={'type':'object','additionalProperties':True} if response=='raw' else {'type':'array','items':{'type':'object','additionalProperties':True}} if response=='raw-array' else model_schema(response)
        operation={'operationId':method.lower()+'_'+re.sub(r'[^a-zA-Z0-9]+','_',path).strip('_'),'x-authority':role,'security':[] if role=='public' else [{'bearerAuth':[]}], 'responses':{'200':{'description':'Success','content':{'application/json':{'schema':schema}}}}}
        for code,description in [(400,'Invalid request'),(401,'Authentication required or invalid'),(403,'Insufficient authority'),(404,'Resource not found'),(409,'Conflict, stale ownership, snapshot or manifest'),(413,'Request exceeds limit'),(426,'Protocol or minimum version incompatible'),(429,'Rate limit exceeded'),(503,'Maintenance or dependency unavailable')]:
            operation['responses'][str(code)]={'description':description}
        parameters=[]
        if path!='info':
            parameters += [{'name':'X-ModSync-Protocol','in':'header','required':True,'schema':{'type':'integer','enum':[1]}},{'name':'X-ModSync-Version','in':'header','required':True,'schema':{'type':'string','example':'0.1.0'}}]
        for name in re.findall(r'\{(\w+)\}',path): parameters.append({'name':name,'in':'path','required':True,'schema':{'type':'string',**({'format':'uuid'} if name=='id' else {})}})
        if path=='servers/{id}/manifest': parameters.append({'name':'revision','in':'query','required':False,'schema':{'type':'integer','format':'int64'}})
        if path=='mods/{project}/updates': parameters += [{'name':name,'in':'query','required':True,'schema':{'type':'string'}} for name in ('minecraft','loader')]
        if parameters: operation['parameters']=parameters
        if request: operation['requestBody']={'required':True,'content':{'application/json':{'schema':model_schema(request)}}}
        paths.setdefault('/api/v1/'+path,{})[method.lower()]=operation
    return {'openapi':'3.1.0','info':{'title':'Minecraft ModSync API','version':'0.1.0','description':'Protocol 1; staged snapshots and immutable manifests. Administrator/user/agent scopes are described per operation.'},'paths':paths,'components':{'securitySchemes':{'bearerAuth':{'type':'http','scheme':'bearer'}},'schemas':schemas()}}

def main():
    path=ROOT/'docs/architecture/openapi.json'
    contract=build()
    controller=(ROOT/'modsync-server/src/main/kotlin/net/modsync/Controllers.kt').read_text()
    actual={(method.lower(),'/api/v1'+route) for method,route in re.findall(r'@(Get|Post|Put)Mapping\("([^"]+)"\)',controller)}
    declared={(method,route) for route,operations in contract['paths'].items() for method in operations}
    assert actual==declared,('OpenAPI route drift',actual-declared,declared-actual)
    data=json.dumps(contract,indent=2)+'\n'
    if '--check' in sys.argv:
        if path.read_text()!=data: raise SystemExit('OpenAPI contract out of date: run python3 scripts/openapi.py')
        print('OpenAPI DTO/route contract checked')
    else: path.write_text(data)
if __name__=='__main__': main()
