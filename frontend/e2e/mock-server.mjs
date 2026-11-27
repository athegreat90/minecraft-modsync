// Serves a built `dist/` under /ui/ the way Spring Boot does (same CSP header, SPA fallback) and stubs the admin API
// with fixtures, so the real-browser checks need neither the Kotlin server nor a database.
// Usage: E2E_DIST=dist E2E_PORT=4300 node e2e/mock-server.mjs
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize, resolve } from 'node:path';

const dist = resolve(process.env.E2E_DIST ?? 'dist');
const port = Number(process.env.E2E_PORT ?? 4300);
// Keep identical to the constant in modsync-server DashboardSecurity.kt and SECURITY.md.
const csp = "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; frame-ancestors 'none'; form-action 'self'";
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.ico': 'image/x-icon', '.txt': 'text/plain' };

const info = (id, name, extra = {}) => ({
  id, name, minecraftVersion: '1.21.1', loader: 'fabric', loaderVersion: '0.16.9', inventoryAt: '2026-11-14T10:00:00Z', publishedRevision: 3, inventoryStale: false, ...extra,
});
const classifications = ['SERVER_ONLY', 'CLIENT_REQUIRED', 'CLIENT_OPTIONAL', null];
const entries = Array.from({ length: 60 }, (_, i) => ({
  fileName: i === 0 ? 'a-very-long-mod-file-name-that-keeps-going-without-any-break-points-1.21.1-fabric-v12.3.4567.jar' : `mod-${String(i).padStart(2, '0')}-1.21.1.jar`,
  sha512: `sha-${i}`,
  shortHash: `h${i}f3a9`,
  classification: classifications[i % 4],
}));
const user = { id: 'u1', username: 'an-extraordinarily-long-username-for-overflow-testing@example.org', role: 'CLIENT', enabled: true, manageable: true };

const api = {
  '/ui/api/session': { username: 'admin', role: 'ADMIN' },
  '/ui/api/sso/providers': { providers: ['google', 'github'] },
  '/ui/api/overview': [
    { info: info('s1', 'Survival'), policy: 'TRUSTED_AUTO' },
    { info: info('s2', 'Creative-with-an-unreasonably-long-server-name-that-must-wrap', { publishedRevision: null, inventoryStale: true }), policy: 'MANUAL' },
    { info: info('s3', 'Modded Skyblock'), policy: 'MANUAL' },
    { info: info('s4', 'Minigames'), policy: 'TRUSTED_AUTO' },
    { info: info('s5', 'Testing'), policy: 'MANUAL' },
  ],
  '/ui/api/servers/s1': {
    info: info('s1', 'Survival'),
    policy: 'TRUSTED_AUTO',
    entries,
    unreviewed: entries.filter((e) => e.classification === null).length,
    manifestRevision: 3,
    manifest: entries.slice(0, 12).map((e) => ({ fileName: e.fileName, projectId: 'project-id', versionId: 'version-id-0001', classification: 'CLIENT_REQUIRED' })),
    coverage: { rows: [
      { instanceId: 'i1', instanceName: 'Alex gaming PC with a long instance name', status: 'IN_SYNC', appliedRevision: 3, missingCount: 0, verifiedAt: '2026-11-14T10:00:00Z' },
      { instanceId: 'i2', instanceName: 'Laptop', status: 'BEHIND', appliedRevision: 2, missingCount: 4, verifiedAt: '2026-11-13T10:00:00Z' },
    ] },
    history: [
      { id: 'h1', createdAt: '2026-11-14T10:00:00Z', outcome: 'PUBLISHED', revision: 3, detail: 'Published automatically after review of 12 files', actor: 'system' },
      { id: 'h2', createdAt: '2026-11-13T10:00:00Z', outcome: 'HELD', revision: null, detail: 'Two mods are unreviewed', actor: 'system' },
    ],
  },
  '/ui/api/users': { users: [user, { ...user, id: 'u2', username: 'ann', enabled: false }, { ...user, id: 'u3', username: 'root', role: 'ADMIN', manageable: false }], page: 0, totalPages: 2, total: 30 },
  '/ui/api/users/u1': { user, activeDevices: 2 },
  '/ui/api/me/identities': { linked: ['github'], available: ['github', 'google'] },
};

createServer(async (request, response) => {
  const url = new URL(request.url ?? '/', 'http://localhost');
  const headers = { 'Content-Security-Policy': csp, 'X-Content-Type-Options': 'nosniff' };
  if (url.pathname === '/') {
    response.writeHead(302, { ...headers, Location: '/ui/' }).end();
    return;
  }
  if (request.method === 'POST') {
    response.writeHead(204, headers).end();
    return;
  }
  if (url.pathname in api) {
    response.writeHead(200, { ...headers, 'Content-Type': 'application/json' }).end(JSON.stringify(api[url.pathname]));
    return;
  }
  if (!url.pathname.startsWith('/ui/') || url.pathname.startsWith('/ui/api/')) {
    response.writeHead(404, headers).end();
    return;
  }
  const relative = normalize(url.pathname.slice('/ui/'.length)).replace(/^(\.\.[/\\])+/, '');
  const file = extname(relative) ? join(dist, relative) : join(dist, 'index.html');
  try {
    response.writeHead(200, { ...headers, 'Content-Type': types[extname(file)] ?? 'application/octet-stream' }).end(await readFile(file));
  } catch {
    response.writeHead(404, headers).end();
  }
}).listen(port, () => console.log(`mock ModSync at http://localhost:${port}/ui/ serving ${dist}`));
