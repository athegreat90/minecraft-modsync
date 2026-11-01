#!/usr/bin/env python3
import hashlib, http.server, json, pathlib, ssl
root=pathlib.Path('/fixtures')
artifacts=json.loads((root/'artifacts.json').read_text())
class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self,*args): pass
    def respond(self, value, status=200):
        body=json.dumps(value).encode(); self.send_response(status); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
    def version(self,a):
        return {'id':a['versionId'],'project_id':a['projectId'],'files':[{'filename':a['fileName'],'size':a['size'],'url':a['url'],'hashes':{'sha512':a['sha512']}}],'game_versions':a['minecraftVersions'],'loaders':a['loaders'],'dependencies':[]}
    def do_POST(self):
        if self.path!='/v2/version_files': return self.respond({},404)
        data=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        self.respond({a['sha512']:self.version(a) for a in artifacts if a['sha512'] in data['hashes']})
    def do_GET(self):
        if self.path.startswith('/data/'):
            name=self.path.removeprefix('/data/')
            if name not in {a['fileName'] for a in artifacts}: return self.respond({},404)
            body=(root/'artifacts'/name).read_bytes(); self.send_response(200); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body); return
        for a in artifacts:
            if self.path==f"/v2/project/{a['projectId']}": return self.respond({'id':a['projectId'],'server_side':'unsupported' if a['projectId']=='personal' else 'required','client_side':'required'})
            if self.path==f"/v2/version/{a['versionId']}": return self.respond(self.version(a))
            if self.path==f"/v2/project/{a['projectId']}/version": return self.respond([self.version(a)])
        self.respond({},404)
server=http.server.ThreadingHTTPServer(('0.0.0.0',443),Handler)
ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain('/pki/cert.pem','/pki/key.pem'); server.socket=ctx.wrap_socket(server.socket,server_side=True); server.serve_forever()
