import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

// Runs against a real ModSync server (Spring Boot jar + database), not the mock. Skipped unless E2E_REAL_URL is set:
//   E2E_REAL_URL=http://localhost:8080 E2E_REAL_USER=admin E2E_REAL_PASSWORD=... npm run test:e2e -- real-server
const baseUrl = process.env['E2E_REAL_URL'];
const user = process.env['E2E_REAL_USER'] ?? 'admin';
const password = process.env['E2E_REAL_PASSWORD'] ?? '';

test.describe('real server', () => {
  test.skip(!baseUrl, 'E2E_REAL_URL is not set');
  test.describe.configure({ mode: 'serial' });

  async function watchCsp(page: Page): Promise<() => Promise<string[]>> {
    await page.addInitScript(() => {
      const w = window as unknown as { __csp: string[] };
      w.__csp = [];
      document.addEventListener('securitypolicyviolation', (e) => w.__csp.push(`${e.violatedDirective} ${e.blockedURI}`));
    });
    return () => page.evaluate(() => (window as unknown as { __csp: string[] }).__csp);
  }

  async function signIn(page: Page): Promise<void> {
    await page.goto(`${baseUrl}/ui/login`);
    await page.getByLabel('Username').fill(user);
    await page.getByLabel('Password').fill(password);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.locator('nav#nav')).toBeAttached();
  }

  test('the theme chosen before sign-in survives sign-in, pages and sign-out', async ({ page }) => {
    const csp = await watchCsp(page);
    await page.goto(`${baseUrl}/ui/login`);
    await page.locator('label', { hasText: 'AMOLED' }).click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'amoled');

    await signIn(page);
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'amoled');
    expect(await page.evaluate(() => getComputedStyle(document.body).backgroundColor)).toBe('rgb(0, 0, 0)');

    await page.goto(`${baseUrl}/ui/account`);
    await expect(page.getByRole('radio', { name: 'AMOLED Black' })).toBeChecked();
    await page.locator('label', { hasText: 'Valhalla Dark' }).click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');

    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page).toHaveURL(/\/ui\/login/);
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    expect(await csp()).toEqual([]);
  });

  for (const theme of ['dark', 'amoled']) {
    for (const width of [320, 768, 1440]) {
      test(`pages after sign-in in ${theme} at ${width}px: no overflow, no CSP violation, axe clean`, async ({ page }) => {
        await page.addInitScript((id) => localStorage.setItem('modsync.theme', id), theme);
        await page.setViewportSize({ width, height: 900 });
        const csp = await watchCsp(page);
        await signIn(page);

        for (const path of ['/ui/', '/ui/users', '/ui/users/new', '/ui/account']) {
          await page.goto(`${baseUrl}${path}`);
          await page.waitForLoadState('networkidle');
          await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
          expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth), `${path} overflow`).toBeLessThanOrEqual(0);
          const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze();
          expect(result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.html).join(' | ')}`), path).toEqual([]);
        }
        expect(await csp()).toEqual([]);
      });
    }
  }
});
