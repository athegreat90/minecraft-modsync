import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';

const THEMES = ['dark', 'amoled'] as const;

const PAGES = [
  { name: 'login', path: '/ui/login' },
  { name: 'overview', path: '/ui/' },
  { name: 'server', path: '/ui/servers/s1' },
  { name: 'users', path: '/ui/users' },
  { name: 'user-new', path: '/ui/users/new' },
  { name: 'user-detail', path: '/ui/users/u1' },
  { name: 'account', path: '/ui/account' },
] as const;

// The spec's viewport list, the pixels just below and above each Bootstrap breakpoint, and landscape phones/tablet.
const WIDTHS = [320, 375, 390, 575, 576, 767, 768, 991, 992, 1199, 1200, 1399, 1400, 1440, 1920, 2560, 3440];
const LANDSCAPE = [
  { width: 568, height: 320 },
  { width: 844, height: 390 },
  { width: 1024, height: 768 },
];
// Contrast needs a real browser, but a full axe run per page and viewport is slow: run it at one width per layout.
const AXE_WIDTHS = new Set([320, 768, 1440]);
const SHOTS = process.env['E2E_SCREENSHOTS'];

async function open(page: Page, path: string, theme: string): Promise<string[]> {
  const violations: string[] = [];
  await page.addInitScript((id) => localStorage.setItem('modsync.theme', id), theme);
  await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (event) => {
      (window as unknown as { __csp: string[] }).__csp = [...((window as unknown as { __csp?: string[] }).__csp ?? []), `${event.violatedDirective} ${event.blockedURI}`];
    });
  });
  page.on('console', (message) => message.type() === 'error' && violations.push(message.text()));
  await page.goto(path);
  await page.waitForLoadState('networkidle');
  return violations;
}

async function cspViolations(page: Page): Promise<string[]> {
  return page.evaluate(() => (window as unknown as { __csp?: string[] }).__csp ?? []);
}

for (const theme of THEMES) {
  for (const route of PAGES) {
    test.describe(`${route.name} in ${theme}`, () => {
      const sizes = [...WIDTHS.map((width) => ({ width, height: 900 })), ...LANDSCAPE];
      for (const size of sizes) {
        test(`${size.width}x${size.height}: no overflow, no CSP violation${AXE_WIDTHS.has(size.width) && size.height === 900 ? ', axe clean' : ''}`, async ({ page }) => {
          await page.setViewportSize(size);
          const errors = await open(page, route.path, theme);

          await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
          const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
          expect(overflow, 'horizontal overflow in px').toBeLessThanOrEqual(0);
          expect(await cspViolations(page)).toEqual([]);
          expect(errors.filter((e) => !e.includes('favicon'))).toEqual([]);

          if (AXE_WIDTHS.has(size.width) && size.height === 900) {
            const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze();
            expect(result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.html).join(' | ')}`)).toEqual([]);
          }
          if (SHOTS && AXE_WIDTHS.has(size.width) && size.height === 900) {
            mkdirSync(join(SHOTS, theme), { recursive: true });
            await page.screenshot({ path: join(SHOTS, theme, `${route.name}-${size.width}.png`), fullPage: true });
          }
        });
      }
    });
  }
}

test.describe('theme behaviour', () => {
  test('AMOLED paints true black and the choice survives a reload', async ({ page }) => {
    await page.goto('/ui/account');
    await page.locator('label', { hasText: 'AMOLED Black' }).click();

    await expect(page.locator('html')).toHaveAttribute('data-theme', 'amoled');
    expect(await page.evaluate(() => getComputedStyle(document.body).backgroundColor)).toBe('rgb(0, 0, 0)');
    expect(await page.evaluate(() => getComputedStyle(document.body).backgroundImage)).toBe('none');
    await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#000000');

    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'amoled');
    await expect(page.getByRole('radio', { name: 'AMOLED Black' })).toBeChecked();
  });

  test('the saved theme is applied before Angular starts (no flash)', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('modsync.theme', 'amoled'));
    await page.route(/\/ui\/(main|chunk)-.*\.js$/, (route) => route.abort());
    await page.goto('/ui/login');

    await expect(page.locator('html')).toHaveAttribute('data-theme', 'amoled');
    expect(await page.evaluate(() => getComputedStyle(document.body).backgroundColor)).toBe('rgb(0, 0, 0)');
  });

  test('the theme switch needs no reload and no API call', async ({ page }) => {
    await page.goto('/ui/login');
    await page.waitForLoadState('networkidle');
    const requests: string[] = [];
    page.on('request', (request) => requests.push(request.url()));
    await page.locator('label', { hasText: 'AMOLED' }).click();

    await expect(page.locator('html')).toHaveAttribute('data-theme', 'amoled');
    expect(requests).toEqual([]);
  });

  test('another tab changing the theme is followed', async ({ context }) => {
    const first = await context.newPage();
    const second = await context.newPage();
    await first.goto('/ui/login');
    await second.goto('/ui/login');

    await second.locator('label', { hasText: 'AMOLED' }).click();

    await expect(first.locator('html')).toHaveAttribute('data-theme', 'amoled');
  });

  test('the navigation drawer opens below lg and is a sidebar from lg', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 800 });
    await page.goto('/ui/');
    const menu = page.getByRole('button', { name: 'Menu' });
    await expect(page.getByRole('navigation', { name: 'Main' })).toBeHidden();
    await menu.click();
    await expect(menu).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible();

    await page.setViewportSize({ width: 1200, height: 800 });
    await expect(menu).toBeHidden();
    await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible();
  });

  test('the sign-in page does not shift when the provider buttons arrive late', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.addInitScript(() => {
      (window as unknown as { __cls: number }).__cls = 0;
      new PerformanceObserver((list) => {
        for (const entry of list.getEntries() as unknown as { hadRecentInput: boolean; value: number }[]) {
          if (!entry.hadRecentInput) (window as unknown as { __cls: number }).__cls += entry.value;
        }
      }).observe({ type: 'layout-shift', buffered: true });
    });
    await page.route('**/ui/api/sso/providers', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 400));
      await route.continue();
    });
    await page.goto('/ui/login');
    await expect(page.getByRole('link', { name: 'Continue with Google' })).toBeVisible();

    expect(await page.evaluate(() => (window as unknown as { __cls: number }).__cls)).toBe(0);
  });

  test('controls meet the 44px touch target', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 800 });
    await page.goto('/ui/users');
    for (const control of await page.locator('main .btn, main .form-control, main .form-select').all()) {
      const box = await control.boundingBox();
      expect(box?.height ?? 0, await control.innerHTML()).toBeGreaterThanOrEqual(43.5);
    }
  });
});
