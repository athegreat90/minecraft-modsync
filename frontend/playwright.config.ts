import { defineConfig } from '@playwright/test';

// Real-browser checks (layout, contrast, CSP, theme persistence) against a production build served with the same CSP
// as Spring Boot. Local only: `npm run build && npm run test:e2e`. Not part of `ng test` or CI.
const port = Number(process.env['E2E_PORT'] ?? 4300);

export default defineConfig({
  testDir: 'e2e',
  fullyParallel: true,
  reporter: 'list',
  use: { baseURL: `http://localhost:${port}`, browserName: 'chromium' },
  webServer: {
    command: 'node e2e/mock-server.mjs',
    url: `http://localhost:${port}/ui/login`,
    reuseExistingServer: true,
    env: { E2E_PORT: String(port) },
  },
});
