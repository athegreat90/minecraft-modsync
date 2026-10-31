#!/usr/bin/env python3
"""Small file-based administration helper. Does not print credentials/responses."""
import argparse, datetime, json, os, pathlib, ssl, tempfile, urllib.request

def write(path, value):
    path=pathlib.Path(path); path.parent.mkdir(parents=True,exist_ok=True)
    descriptor,temp=tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(descriptor,'w') as stream: stream.write(value)
        os.replace(temp,path)
    finally:
        if os.path.exists(temp): os.unlink(temp)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--config',required=True); parser.add_argument('--method',default='GET',choices=['GET','POST','PUT'])
    parser.add_argument('--data'); parser.add_argument('--out',required=True); parser.add_argument('--secret-field'); parser.add_argument('--ca-file'); parser.add_argument('path')
    args=parser.parse_args(); config=json.loads(pathlib.Path(args.config).read_text())
    endpoint=config['endpoint'].rstrip('/')
    from urllib.parse import urlsplit
    url=urlsplit(endpoint)
    if url.scheme!='https' or not url.hostname or url.username or url.password or url.query or url.fragment: raise SystemExit('A trusted HTTPS endpoint is required')
    if not args.path or args.path.startswith('/') or '..' in args.path or '#' in args.path: raise SystemExit('Use a relative API v1 route')
    context=ssl.create_default_context(cafile=args.ca_file)
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,*_args,**_kwargs): return None
    opener=urllib.request.build_opener(urllib.request.HTTPSHandler(context=context),NoRedirect())
    def request(path,method='GET',body=None,token=None):
        data=None if body is None else json.dumps(body).encode()
        headers={'Content-Type':'application/json','X-ModSync-Protocol':'1','X-ModSync-Version':'0.1.0'}
        if token: headers['Authorization']='Bearer '+token
        with opener.open(urllib.request.Request(endpoint+'/api/v1/'+path,data=data,headers=headers,method=method),timeout=60) as response:
            raw=response.read(8*1024*1024+1)
            if len(raw)>8*1024*1024: raise ValueError('Response too large')
            return json.loads(raw)
    caps=request('info')
    if caps['controlPlaneId']!=config['controlPlaneId'] or caps['protocol']!=1: raise SystemExit('Control-plane identity/protocol mismatch')
    credentials=config.get('credentials')
    if not credentials: raise SystemExit('Login with the CLI first')
    expires=datetime.datetime.fromisoformat(credentials['expiresAt'].replace('Z','+00:00'))
    if expires<=datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(seconds=60):
        credentials=request('auth/refresh','POST',{'token':credentials.get('refreshToken')})
        config['credentials']=credentials; write(args.config,json.dumps(config))
    body=json.loads(pathlib.Path(args.data).read_text()) if args.data else None
    result=request(args.path,args.method,body,credentials['token'])
    value=str(result[args.secret_field]) if args.secret_field else json.dumps(result,indent=2)+'\n'
    write(args.out,value); print('Response saved to the selected output file')
if __name__=='__main__': main()
