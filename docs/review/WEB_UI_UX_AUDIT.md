# Web UI/UX Audit — Minecraft ModSync

Audit date: 2026-11-15. Evaluation and diagnosis only: no application code, version, `CHANGELOG.md` or `MEMORY.md` was changed, and nothing was committed. The audit ran in two passes: first as a non-admin account (`test`, role CLIENT), then as an administrator, with the password entered by the maintainer through the clipboard so it never appeared in logs or files.

## 1. Executive summary

The audit ran in a real Chrome session against the live deployment. Every `/ui/**` page was reachable in the second pass: server list, server detail, users, user detail and account. The only state-changing action executed was signing in and out. No classification, policy, user or device change was made.

Two themes dominate:

1. **Redirects use `http://` behind the TLS proxy (F-01, F-02).** Chrome blocks them through the page's own CSP (`form-action 'self'`). After **Sign in** the page does not move, after **Sign out** the page keeps showing the signed-in shell even though the server has ended the session, and failed logins show no message. The cause is the deployment setting `MODSYNC_FORWARD_HEADERS`, which the 0.5.2 changelog already describes.
2. **The admin API is slow (F-14).** Every `/ui/api/*` call takes 0.3 to 1.2 s for payloads of 35 to 400 bytes, and the server page's API call takes **7.5 to 8.1 s** every time. Trivial database-backed endpoints cost about 185 to 340 ms each, against 30 to 55 ms for static pages, which points at the backend or database rather than the network. The exact cause is not profiled yet.

The front end itself is in good shape. Lighthouse scores **Accessibility 100 and Best Practices 100** on both the login page and the authenticated server page. Keyboard flow, focus rings, touch targets and the mobile layout are solid, in-app navigation makes one API call per route with no duplicates, and the 0.5.2 "Classify scrolls into view and focuses the reason field" fix works in production.

## 2. Tested URL, build, browser, environment

| Item | Value |
|---|---|
| URL | `https://modsync.alexandermora.de/` (maintainer-authorized; named in `MEMORY.md`) |
| Build / version | **Not exposed** by any public endpoint. UI static files carry `Last-Modified: 2026-11-14 23:02:04 GMT`, before the 0.5.2 changelog date, so the deployment may be 0.5.1 (inference). The remedy for F-01 is configuration either way. |
| Browser | Chrome 157 (Chrome DevTools MCP), Windows 11, no throttling, cold cache for traces |
| Edge | `openresty` reverse proxy, HTTP/1.1 to the browser |
| Accounts | `test` (CLIENT, no dashboard access), `admin` (ADMIN). No credentials were written to any file. |
| Data seen | 1 server ("Survival", neoforge, Minecraft 26.3), 94 scanned mods, 3 accounts |

## 3. Pages and workflows tested

**Pages and routes (9):** `/`, `/ui/login` (plus `?error`, `?expired`, `?logout`), `/favicon.ico`, `/ui/` (as non-admin, then as admin), `/ui/servers` (anonymous deep link), `/ui/servers/{id}`, `/ui/users`, `/ui/users/{id}`, `/ui/account`.

**Workflows (19):**

1. Anonymous load of the login page.
2. Failed login with a nonexistent user (browser ×2, HTTP ×1).
3. Valid login as `test` (browser and HTTP).
4. Non-admin navigation and API probes.
5. Anonymous deep link to a protected route.
6. SSO start-of-flow inspection (HTTP only).
7. Keyboard-only walk of the login form.
8. Logout as `test` (browser and HTTP).
9. Login with a stale CSRF token (two tabs).
10. Admin sign-in.
11. Server list.
12. Server detail load and timing (3 repeats).
13. Open and cancel the **Classify** form (nothing submitted).
14. Users search and filter.
15. User detail view (no action clicked).
16. Account / linked identities view.
17. In-app navigation between sections (duplicate-call check).
18. Mobile menu and mobile layout of the server page.
19. Admin sign-out through the UI button.

Plus five viewport checks on the login page, three Lighthouse runs, two performance traces and a Resource Timing measurement.

## 4. Authentication and authorization results

| Check | Result |
|---|---|
| Anonymous `/ui/` | 302 to login. Pass. |
| Anonymous `/ui/api/me` | 401. Pass. |
| Anonymous deep link `/ui/servers` | Lands on `/ui/login`, clean console. Pass. |
| Failed login (nonexistent user) | **Fail (F-01)**: `302 → http://…/ui/login?error`, blocked by CSP, no visible feedback. |
| Valid login (`test`, `admin`) | Server accepts and sets `JSESSIONID` (`Secure; HttpOnly; SameSite=Lax`). The redirect is `http://…/ui`, blocked in the browser (**F-01**). Opening `/ui/` by hand works. |
| Non-admin session | `/ui/` and every `/ui/api/*` return **403**. Expected per `DashboardSecurity.kt:264-265`. The raw Whitelabel page is the defect (**F-03**). |
| Logout (UI button) | Server ends the session (`/ui/api/session` goes 200 → 401), but the redirect is blocked, so the page keeps the signed-in shell (**F-01**). |
| Stale CSRF token | `POST /login` returns a bare 403 (**F-16**). |
| SSO (GitHub, Google) | Buttons render and start the flow, but `redirect_uri` is `http://…` (**F-02**). End-to-end SSO **not** tested (no authorization to use personal accounts). |
| Session expiry | Not testable without waiting out the session. The `?expired` notice renders correctly. |

## 5. Functional test results

| Area | Result |
|---|---|
| Server list | Works. One card (name, loader, Minecraft version, policy, manifest revision). Stray bullet marker outside the card (F-21). |
| Server detail | Works after 7.5 to 8.1 s (F-14). Sections: Automatic publication (policy select, Save, Re-evaluate, history of 3 records), Client coverage (1 instance), Server mods (94 rows, "10 of 94 still need review"), Latest manifest. No search, filter, sort or pagination (F-15). |
| Classification form | Opens in place, scrolls into view, focuses **Reason**, has a descriptive label (0.5.2 fix confirmed). Cancel works but focus is lost (F-17). Not submitted. |
| Users | Search, role and status filters and pagination controls work (3 accounts). Filter state is not in the URL and the count reads "1 accounts" (F-18). |
| User detail | Shows role, status, device count, **Disable account**, **End sessions and tokens**, password reset, **Revoke all devices** (guarded by a checkbox). No confirmation on Disable or End sessions (F-19). Nothing clicked. |
| Account | Lists GitHub and Google as "not linked" with **Link** buttons. Not clicked. |
| Login notices | "Sign-in failed. Check your username and password.", "Your session expired. Please sign in again.", "You have been signed out." render correctly via `role="status"`. Real users rarely see them because of F-01. |

Observed data state, not UI defects: the policy is `TRUSTED_AUTO` but the last three automatic publications all ended `REVIEW_REQUIRED` for the same six files, and the one client instance shows `SYNC_FAILED` (4 applied, 15 missing).

## 6. Browser console errors and warnings

| # | Page | Message | Trigger | Class |
|---|---|---|---|---|
| C1 | `/ui/login` | `Sending form data to '…/login' violates … "form-action 'self'". The request has been blocked.` (3 occurrences) | Click **Sign in** | **App defect (F-01)** |
| C2 | any authenticated page | `Sending form data to '…/logout' violates … "form-action 'self'"` | Click **Sign out** | **App defect (F-01)** |
| C3 | `/` | `Failed to load resource: 401` | Open the bare domain | App defect (F-04) |
| C4 | `/favicon.ico` | `Failed to load resource: 401` | Same | App defect (F-04) |
| C5 | `/login` (POST) | `Failed to load resource: 403` | Submit a login form with a stale CSRF token | App defect (F-16) |
| C6 | `/ui/users` | Issue: `A form field element should have an id or name attribute` (3 fields) | Open the users page | App defect (F-18) |
| C7 | `/` | Issue: `Page layout may be unexpected due to Quirks Mode` | Same as C3 | App defect (F-04) |
| C8 | `/ui/login` | Issue: `Content Security Policy of your site blocks some resources` | Same as C1 | Consequence of C1 |

**Not counted:** one `No label associated with a form field` issue belonged to the Kafbat UI on `localhost:8080` that the browser had open before this audit, not to ModSync.

Server list, server detail, account and login pages loaded cleanly produced **zero** console messages: no Angular errors, no unhandled rejections, no source-map errors.

## 7. Network and API findings

- **Redirects (F-01, F-02):** `POST /login` and `POST /logout` answer `302` with `Location: http://modsync.alexandermora.de/…`. A request header `X-Forwarded-Proto: https` sent by hand was ignored, which matches `forward-headers-strategy: none` (`application.yaml:4`).
- **API latency (F-14), 1× repeated, no throttling:**

| Endpoint | Time | Size |
|---|---|---|
| `GET /ui/api/servers/{id}` | 8,137 / 7,525 / 7,530 ms | 37,879 B |
| `GET /ui/api/session` | 1,228 ms | 35 B |
| `GET /ui/api/users?page=0` | 923 to 1,075 ms | 390 B |
| `GET /ui/api/me/identities` | 919 to 925 ms | 45 B |
| `GET /ui/api/overview` | 313 to 465 ms | 262 B |
| Static `/ui/login` (TTFB) | 30 to 55 ms | n/a |
| `/api/v1/info` and `/actuator/health` (DB-backed, TTFB) | 185 to 340 ms | n/a |

- **Status codes:** no unexpected 400, 404, 409 or 500. 401 and 403 occurred only where described in sections 4 and 6.
- **Duplicate requests:** none on in-app navigation (one API call per route, session not refetched). The anonymous login page was seen calling `/ui/api/session` twice in one load (two 401s) once; not reproduced.
- **Static assets (F-05, F-06, F-07):** JS and CSS uncompressed with `Cache-Control: no-cache`, all HTTP/1.1.
- **Cookies (F-11):** `JSESSIONID`: `Secure; HttpOnly; SameSite=Lax`. `XSRF-TOKEN`: `Path=/` only.
- No tokens, cookies or credentials appear in this report or the evidence (screenshots of the login page show empty forms; a text scan of the evidence folder found no secret values).

## 8. UX/UI issues

- **F-01:** no visible reaction to Sign in, no reaction to Sign out, failed-login message never shown.
- **F-03:** a signed-in non-admin lands on a raw Spring "Whitelabel Error Page" with no explanation or sign-out.
- **F-04:** the bare domain shows a Whitelabel 401 page.
- **F-09:** login card not vertically centred, spurious scrollbar.
- **F-15:** the server page renders 94 + 94 rows with no way to search, filter to "unreviewed", sort or page; 11,675 px tall on desktop and **40,394 px** (about 50 screens) at 375 px wide.
- **F-16:** stale-token login returns a bare 403.
- **F-18:** filter state is not in the URL, "1 accounts", results not announced.
- **F-19:** **Disable account** and **End sessions and tokens** fire on one click.
- **F-20:** machine values shown raw: ISO timestamps with microseconds, enum names (`SERVER_ONLY`), mixed `unreviewed` lowercase, opaque Modrinth ids and `manual-…` placeholders in the manifest, `—` for Revision.
- **F-21:** stray list bullet outside the server card.
- **F-22:** a 7.5 s wait shows only the text "Loading server…".
- Positives: consistent dark theme, clear labels, 16px inputs, targets ≥ 44px, a working "Skip to content" link, responsive stacked cards, helpful notice copy and a correct 0.5.2 Classify focus fix.

## 9. Accessibility findings (WCAG 2.2 AA)

- Lighthouse accessibility: **100** on the login page (desktop and mobile) and on the authenticated server page (desktop, snapshot mode).
- Manual keyboard walk of the login form: logical order, visible 3px focus ring on every control.
- Mobile menu: `aria-expanded` toggles correctly, links are 44px tall.
- Reduced motion is honored (`_tokens.scss:32`).
- **F-12:** the sign-in error notice is `role="status"` (polite) rather than `alert`.
- **F-17:** closing the Classify form with **Cancel** leaves focus on `BODY` instead of returning to the **Classify** button.
- **F-18:** filtered user results are not announced (no live region); search and select fields lack `id`/`name`.
- Tables have no `<caption>`; `<th>` has no `scope` (not flagged by Lighthouse).
- **F-23 (withdrawn):** below 48rem `styles.scss:83` sets `display: block` on `table`, `tbody`, `tr` and `td`, which I suspected would remove the table meaning for screen readers. I tested a fixture with the same CSS in Chrome 157: at 375 px the accessibility tree still has `table`, `row`, `columnheader` and `cell`, and each cell includes its `data-label` text (for example "File alpha.jar"). Not a defect in this browser. Other browsers and screen readers were not tested.
- Not covered: real screen-reader testing, forced-colors mode.

## 10. Responsive design findings

| Page | Viewport | Overflow | Notes |
|---|---|---|---|
| Login | 375 × 812 | None | Targets ≥ 44px, inputs 16px |
| Login | 430 × 932 | None | Card width 384px |
| Login | 768 × 1024 | None | Card width 384px |
| Login | 1366 × 768 | None | Vertical scroll appears (F-09) |
| Login | 1920 × 1080 | None | Card not centred, document 1188px tall vs 1080px viewport (F-09) |
| Server detail | 375 × 812 | None | Tables become labelled cards, "Sign out" wraps to two lines, page 40,394px tall (F-15) |
| Server detail | 1366 × 768 | None | Lighthouse and trace ran here |
| Server list | 1920 × 1080 | None | Screenshot reviewed |

Authenticated pages were **not** measured at 430 and 768 widths. Screenshots: `docs/review/evidence/`.

## 11. Performance measurements

Conditions: live site, Chrome 157, 1× CPU, no network throttling, cache bypassed, single run each. Treat as indicative.

| Metric | Login page | Server page (admin) |
|---|---|---|
| LCP | 144 ms | 1,408 ms (the "Loading server…" placeholder; real content waits for the 7.5 s API call) |
| CLS | 0.07 desktop to 0.10 mobile | 0.00 |
| INP | Not measured | Not measured |
| JS + CSS | 276,933 B raw, uncompressed | n/a (shared bundle plus 2 lazy chunks) |
| Requests | 8 | 10 |
| API time | 12 ms (`sso/providers`) | 7.5 to 8.1 s (`servers/{id}`) |
| DOM size | n/a | 1,430 nodes, 196 rows, 98 buttons |
| JS heap after in-app navigation | n/a | 4 MB |
| Lighthouse | A11y 100, BP 100, SEO 90 | A11y 100, BP 100, SEO 75 |

No memory growth or repeated subscriptions were seen in a short three-route navigation loop. A long-running leak check was not performed.

## 12. Screenshots and sanitized evidence

- Login: `docs/review/evidence/01-login-desktop.png`, `02-…375x812`, `03-…430x932`, `04-…768x1024`, `05-…1366x768`, `06-…1920x1080`.
- Authenticated: `10-dashboard-1920x1080.png`, `11-server-375x812-loaded.png`.
- Lighthouse: `lighthouse-desktop/`, `lighthouse-mobile/`, `lighthouse-server-page/` (`report.html`).
- Redirect, cookie, header and timing evidence is quoted inline with token values redacted.

## 13. Findings, reproduction and root cause

### Summary table

| ID | Sev | Component | Description | Status |
|---|---|---|---|---|
| F-01 | **High** | Deployment / `application.yaml` | All redirects are `http://`; Chrome blocks the login, failure and logout redirects via CSP `form-action 'self'` | Confirmed |
| F-02 | **High** | Deployment / OAuth2 client | SSO `redirect_uri` is `http://…` | Header confirmed; provider rejection suspected, untested |
| F-14 | **Medium** | Backend, database access | Every UI API call takes 0.3 to 1.2 s; the server page call takes 7.5 to 8.1 s | Symptom confirmed; cause found (slow database host); code part fixed, host part open |
| F-15 | Medium | Server page | 94 + 94 rows, no search, filter, sort or paging; 40,394 px tall on mobile | Confirmed |
| F-03 | Medium | `DashboardSecurity.kt` | Non-admin sees raw Whitelabel 403 after login; no explanation or sign-out | Confirmed |
| F-04 | Medium | Root route | `/` and `/favicon.ico` return Whitelabel 401 in quirks mode | Confirmed |
| F-05 | Medium | Proxy / Spring static handler | JS/CSS not compressed (277 KB raw) | Confirmed |
| F-06 | Low | Static resource caching | Fingerprinted assets use `Cache-Control: no-cache` | Confirmed |
| F-07 | Low | Reverse proxy | HTTP/1.1 only | Confirmed |
| F-08 | Low | `login` component | CLS 0.07 to 0.10 when SSO buttons appear after `/ui/api/sso/providers` | Probable |
| F-09 | Low | `login.scss:3` | Margin collapse: card not centred, spurious scrollbar | Confirmed |
| F-10 | Low | Security headers | No Referrer-Policy or Permissions-Policy. HSTS looked missing only because the server did not see HTTPS (same root cause as F-01); Spring sends it for requests it sees as HTTPS | Confirmed (HSTS part corrected) |
| F-11 | Low | CSRF cookie | `XSRF-TOKEN` has no `SameSite`; `Secure` is missing only while the server does not see HTTPS (same root cause as F-01) | Confirmed (Secure part corrected) |
| F-12 | Low | Login notice | Error notice is `role="status"`, not `alert` | Confirmed |
| F-13 | Low | `index.html` | No meta description or `noindex` for an admin console | Confirmed |
| F-16 | Low | Login CSRF | Stale `_csrf` token (second tab) gives a bare 403 with no message | Confirmed |
| F-17 | Low | Classify form | Focus lost to `BODY` after **Cancel** | Confirmed |
| F-18 | Low | Users page | Filters not in URL, "1 accounts", no live region, fields lack `id`/`name` | Confirmed |
| F-19 | Low | `user-detail.ts:36-47` | **Disable account** and **End sessions** have no confirmation | Confirmed (source and UI) |
| F-20 | Low | Server page | Raw ISO timestamps, enum names, opaque ids shown to admins | Confirmed |
| F-21 | Low | Server list | Stray list bullet outside the card | Confirmed |
| F-22 | Low | Server page | Only text "Loading server…" during a 7.5 s wait; LCP understates real wait | Confirmed |
| F-23 | n/a | `styles.scss:83` | Mobile card CSS (`display: block` on table parts) was suspected to drop table semantics for screen readers | **Withdrawn**: not reproduced (see section 9) |

### F-01 — Redirects use `http://`; sign-in and sign-out appear to do nothing (High)

- **Reproduce:** open `/ui/login`, enter any credentials, click **Sign in**. Console shows the CSP `form-action` violation, the network list shows `POST /login ERR_ABORTED`, and the page does not change. The same happens on **Sign out** in the signed-in app. Over HTTP: `POST /login` → `302 Location: http://modsync.alexandermora.de/…` (`/ui` on success, `/ui/login?error` on failure, `/ui/login?logout` on logout).
- **Evidence:** consoles C1 and C2; the console error was observed 4× in the browser (3 login, 1 logout), and the `http://` `Location` 5× over HTTP. After **Sign out**, `/ui/api/session` was 401 while the page still showed "admin" and "Sign out".
- **Root cause:** `server.forward-headers-strategy` defaults to `none` (`application.yaml:4`, `MODSYNC_FORWARD_HEADERS`). Spring builds absolute redirects from its own plain-HTTP connection. Chrome applies `form-action` to the redirect target, and `http://` is not `'self'` for an `https` page. The server side succeeds, which is why opening `/ui` by hand works.
- **Fix (ops, matches the 0.5.2 docs):** set `MODSYNC_FORWARD_HEADERS=framework` and make sure `openresty` sends `X-Forwarded-Proto` and `X-Forwarded-Host`.
- **Fix (code, defence in depth):** log a `WARN` when the proxy sends `X-Forwarded-*` while the strategy is `none`. Optionally evaluate `server.tomcat.use-relative-redirects=true` to remove the scheme dependency (verify against the SSO flow first).
- **Regression tests:** a deployment smoke check asserting that the `Location` of `POST /login` starts with `https://`. The existing `UiApiIntegrationTest` already covers `framework` mode and the ignored-by-default case.

### F-02 — SSO `redirect_uri` is `http://` (High)

- **Evidence:** `Location: https://github.com/login/oauth/authorize?…&redirect_uri=http://modsync.alexandermora.de/login/oauth2/code/github…`, same for Google.
- **Root cause:** same as F-01; the `redirect_uri` is built from the request URL.
- **Consequence (suspected):** providers compare against the registered HTTPS callback and refuse the request. Not exercised, per the authorization boundary.
- **Fix:** same switch. The check is documented in `docs/security/SSO_SETUP.md:404`.

### F-14 — Slow admin API (Medium)

- **Reproduce:** open `/ui/servers/{id}` and watch the Network panel. `GET /ui/api/servers/{id}` stays pending for 7.5 to 8.1 s (three repeats), returning 37,879 B. Even `/ui/api/session` costs 1.2 s for 35 B.
- **Evidence:** DB-backed endpoints (`/api/v1/info`, `/actuator/health`) cost 185 to 340 ms against 30 to 55 ms for static pages and the non-DB readiness probe. Reading `DashboardService.server` gives about 10 to 12 queries (`listServers`, `overridesFor`, snapshot header, entries, manifest, coverage ×2, policy, history). This assumes repeated `findById` calls are served by the per-request session cache, because no `open-in-view` setting was found (Spring's default is on); not verified. At that count, 7.5 s is about 650 ms per query, well above the 185 to 340 ms measured for single-query endpoints, so round-trip time and query count alone do not explain the delay.
- **Root cause (found by profiling the deployment, read-only):** the app host is fast and idle, and the network is fine (ping 0.17 ms). The database server the app uses (a separate LAN machine behind a TLS hostname) answers every request/response exchange about 153 ms late: its first packet arrives 306 ms after the TCP connect and a TLS handshake takes 633 ms, while a Galera node on the app host itself answers in 2 to 3 ms. A plain query costs one such exchange (`/api/v1/info` is a constant 155 ms). The page multiplies it: before the fix `GET /ui/api/servers/{id}` sent 49 statements, 35 of them transaction control, because every Spring Data `CrudRepository` call such as `findById` opens its own read-only transaction (`SET SESSION TRANSACTION READ ONLY`, `set autocommit=0`, the select, `COMMIT`, `set autocommit=1`, `SET SESSION TRANSACTION READ WRITE`). The code also loaded every server to find one (`DashboardService.kt:112`) and read the manifest twice.
- **Fix, code part (done):** one read-only transaction around the page, a lookup by id, the manifest loaded once and passed to coverage. Statements per call, measured with MariaDB's general log: server page 49 to 14, users page 6 to 1, session 7 to 1 (a later change that reads only the account's name and role); overview (2) unchanged. At about 153 ms per statement that is roughly 7.5 s down to 2.1 s for the page. **Fix, infrastructure part (open, the owner's decision):** find out why that database host adds about 153 ms per exchange (a reverse-DNS lookup of the client, a proxy in front of it, or the host itself; `skip-name-resolve` is the usual first suspect), or point the app at a nearby node. Do not turn auto-commit off globally: code outside transactions relies on it.
- **Regression test:** an integration test that counts queries for `GET /ui/api/servers/{id}` and fails above a budget; a smoke check that the endpoint answers under a latency threshold.

### F-15 — Large, unpaged tables (Medium)

- **Evidence:** 4 tables, 196 rows, 98 **Classify** buttons, 11,675 px tall at 1366 wide and 40,394 px at 375 wide. "10 of 94 still need review" is shown but there is no way to filter to those rows. The Users page has search and paging, so the pattern exists.
- **Fix:** add a text filter and a "needs review" toggle to the mods table, sort by classification, and page or virtualize past about 50 rows; collapse the manifest section by default. **Test:** component tests for filter and toggle; an a11y spec for the filter control.

### F-03 — Non-admin dead end (Medium)

- **Reproduce:** sign in as an account without ADMIN and open `/ui`. The browser shows "Whitelabel Error Page … Forbidden, status=403". `/ui/api/session` also returns 403, so the SPA cannot show "signed in as X, not authorized".
- **Root cause:** the dashboard chain applies `hasRole("ADMIN")` to everything outside the permitted list; `defaultSuccessUrl("/ui", true)` sends every successful login there.
- **Fix:** register an `AccessDeniedHandler` that redirects to `/ui/login?denied` (rendered by the existing notice component). **Test:** non-admin request gets the redirect.

### F-04 — Root URL shows a Whitelabel error (Medium)

- **Reproduce:** open `https://modsync.alexandermora.de/`: `401`, no doctype (quirks mode), plus a `401` for `/favicon.ico`.
- **Fix:** redirect `/` to `/ui/` and serve or redirect a root favicon. **Test:** `GET /` is a 3xx to `/ui/` for anonymous callers.

### F-05, F-06, F-07 — Delivery performance (Medium, Low, Low)

- **Evidence:** `content-encoding` absent on all JS/CSS (HTML is gzip), 277 KB raw, `cache-control: no-cache` on content-hashed filenames, 6 conditional requests on a repeat visit, all HTTP/1.1.
- **Root cause:** which layer sets this (Spring static handler or `openresty`) cannot be told from outside. Spring compression is off by default.
- **Fix:** enable compression, serve hashed assets with `Cache-Control: public, max-age=31536000, immutable`, keep `index.html` at `no-cache`, and enable HTTP/2 at the proxy. No new dependency.

### F-08 — Layout shift from SSO buttons (Low)

- The shift is on `BODY` at 136 ms; the providers response completes at about 130 ms. The trace names no root cause, so this is a timing correlation. **Fix:** reserve the space or render the provider area server-side. **Test:** a Playwright check that CLS stays under 0.05 on the login route.

### F-09 — Login card not centred (Low)

- **Root cause:** `.login { margin: 10vh auto 0 }` (`login.scss:3`) collapses through `body` (`styles.scss:7-14`: no padding or border), pushing the page 108px past `100vh`.
- **Fix:** centre with `display:grid; place-items:center; min-height:100dvh` and drop the `vh` margin. **Test:** assert `scrollHeight <= innerHeight` on the login route.

### F-10 to F-13 — Hardening and polish (Low)

- **F-10:** add `Referrer-Policy: same-origin` and a restrictive `Permissions-Policy`. Correction found during remediation: Spring Security already sends `Strict-Transport-Security: max-age=31536000 ; includeSubDomains` for any request it sees as HTTPS, so HSTS looked missing only because the server did not see HTTPS (F-01). Fixing F-01 restores it; no code change.
- **F-11:** the Angular client must read the cookie, so `HttpOnly=false` is intentional. Add `SameSite=Lax`. Correction: Spring sets `Secure` from `request.isSecure()`, so it also appears once the server sees HTTPS (F-01); forcing it in code would break plain-HTTP development.
- **F-12:** use `role="alert"` for the failed-sign-in notice.
- **F-13:** add `<meta name="robots" content="noindex">` and a short description.

### F-16 to F-23 — Authenticated-pass polish (Low)

- **F-16:** with two tabs open, the second tab's page load replaced the shared `XSRF-TOKEN` cookie and the first tab's hidden `_csrf` went stale, so `POST /login` returned 403. Handle `AccessDeniedException` on `/login` with a redirect to `/ui/login?csrf` and a message ("Your page was out of date. Try again."). **Test:** MockMvc login with a wrong token expects that redirect.
- **F-17:** after **Cancel**, focus lands on `BODY`. Return focus to the originating **Classify** button. **Test:** component test asserting `document.activeElement` after cancel.
- **F-18:** put `q`, `role`, `status` and `page` in the query string, use correct plurals ("1 account"), add a polite live region for the result count, and give the three fields an `id`/`name`.
- **F-19:** the actions are reversible (**Enable**), but add a confirm step or an undo notice for **Disable account** and **End sessions and tokens** for consistency with **Revoke all devices**.
- **F-20:** format timestamps for humans (local time, seconds), map enums to readable labels, show `unreviewed` in the same style, and show mod names instead of raw Modrinth ids where known.
- **F-21:** remove the list marker (`list-style: none`) from the server list.
- **F-22:** add a skeleton or spinner and, for waits over about 3 s, a "still loading" message. After F-14, re-measure; LCP will then reflect the real content.
- **F-23:** withdrawn, see section 9.

## 14. Recommended solutions, in order

See the roadmap below. All fixes use existing Spring Boot and Angular facilities and need no new dependency.

## 15. Prioritized remediation roadmap

1. **Now (ops, no release):** set `MODSYNC_FORWARD_HEADERS=framework` and confirm the proxy forwards `X-Forwarded-Proto` and `X-Forwarded-Host`. Verify with `curl -si -X POST …/login` that `Location` is `https://`. Clears F-01 and F-02.
2. **Next:** profile the admin API latency (F-14): database slow-query log or a timing log per repository call, plus the app-to-database round-trip time. Change queries only after the numbers show where the time goes.
3. **Next patch (UI):** F-15 (filter and paging), F-03 (denied handler), F-04 (root redirect and favicon), F-16, F-17, F-18, F-19, F-09, F-12, F-21, plus a startup warning for the forwarded-headers misconfiguration.
4. **Delivery pass:** F-05, F-06, F-07 together at the proxy or static handler.
5. **Hardening pass:** F-10, F-11, F-13 (after step 1).
6. **Polish:** F-08, F-20, F-22.

## 16. Untested functionality and limitations

- **State-changing actions, deliberately not executed:** classification approval, policy change, re-evaluate, create, disable or enable user, reset password, end sessions, revoke devices, link or unlink an identity.
- SSO end-to-end (needs personal provider accounts; not authorized).
- Session expiry in practice, concurrent sessions, login throttling (`LoginThrottleFilter`), CSRF rejection without a token on state-changing calls.
- `/ui/users/new` was not opened; authenticated pages were not measured at 430 and 768 widths.
- INP, long-running memory-leak checks, Angular re-render cost under interaction.
- Real screen-reader testing, forced-colors and high-contrast modes.
- Build version of the deployment (not exposed).
- Single run per measurement; no repeated-sample statistics. The cause of F-14 is an inference from timings and code reading, not a database profile.

## 17. Final results

| Measure | Count |
|---|---|
| Pages / routes tested | 9 (plus 3 login notice states) |
| Workflows executed | 19 (plus 5 viewports, 3 Lighthouse runs, 2 traces) |
| Confirmed defects | 20 confirmed, 1 probable (F-08), 1 header-confirmed with suspected consequence (F-02); F-23 was withdrawn after testing |
| Browser console errors | 7 error-level messages across 3 types (CSP `form-action` ×4: 3 login, 1 logout; 401 resource ×2; stale-CSRF 403 ×1), plus 3 distinct issue-level messages |
| Unexpected failed network requests | 7 (4 aborted `POST` login/logout, 1 stale-CSRF 403, 2 root-URL 401s) |
| UX/UI problems | 11 (F-01, F-03, F-04, F-09, F-15, F-16, F-18, F-19, F-20, F-21, F-22) |
| Accessibility problems | 3 confirmed (F-12, F-17, F-18); Lighthouse a11y score 100 on both audited pages |
| Performance findings | 6 (F-05, F-06, F-07, F-08, F-14, F-22) |
| Security-hardening findings | 2 (F-10, F-11) |
| Blocked or untested scenarios | 10 (see section 16) |

**Top five to fix first:** F-01 (`http://` redirects), F-02 (SSO `redirect_uri`, same switch), F-14 (7.5 s admin API), F-15 (unfiltered 94-row tables), F-03 (non-admin Whitelabel 403).

## 18. Remediation status (release 0.6.0)

Status of each finding after the remediation branch. "Fixed in code" means the change is merged into the 0.6.0 release and covered by tests; items marked "needs the host" cannot be fixed by a release.

| Finding | Status |
|---|---|
| F-01, F-02 | **Fixed on the host** (verified live, section 19): `MODSYNC_FORWARD_HEADERS=framework` is set. In code: a start-up `WARN` names the setting; on the live deployment it showed that the proxy was already sending `X-Forwarded-Proto`, so only the variable was missing. |
| F-03, F-04, F-16 | Fixed in code (denied, root and stale-token redirects, with relative `Location` targets). |
| F-05, F-06 | Fixed in code (`MODSYNC_COMPRESSION`, immutable cache for fingerprinted files). |
| F-07 | Needs the host (HTTP/2 on the proxy; nginx-proxy-manager has an HTTP/2 switch on the proxy host's SSL tab). Not re-checked. |
| F-08 | Open (reserve space for the SSO buttons). |
| F-09, F-12, F-18, F-19, F-21 | Fixed in code and checked in a real browser against a local mock API. |
| F-10, F-11 | Fixed in code for Referrer-Policy, Permissions-Policy and `SameSite`; HSTS and `Secure` returned once F-01 was fixed on the host (verified live, section 19). |
| F-13 | Fixed in code (`noindex` and description meta). |
| F-14 | **Cause found, code part fixed, host part open.** The database host adds about 153 ms to every request/response exchange (profiled read-only over SSH). Code: server page 49 to 14 statements, users page 6 to 1. The remaining fix is on the database host or its address. |
| F-15, F-17, F-22 | Fixed in code; on identical data the server page is about 2,800 px tall instead of 11,700. |
| F-20 | Open (readable timestamps and enum labels). |
| F-23 | Withdrawn. |

## 19. Re-test on the live site after 0.6.0 (2026-11-15)

Same deployment, after 0.6.0 was installed and `MODSYNC_FORWARD_HEADERS=framework` was in effect. Read-only apart from signing in and out; the admin and `test` accounts were used as in the first passes.

| Finding | Result |
|---|---|
| F-01 sign-in and sign-out | Fixed. The browser follows `POST /login → /ui → /ui/login?denied` itself, all over `https`, with no CSP error. `POST /logout` answers `Location: https://modsync.alexandermora.de/ui/login?logout` and the session is 401 afterwards. |
| F-02 SSO callback | Fixed. The `redirect_uri` is `https://modsync.alexandermora.de` for GitHub and Google. The real provider round trip was not run. |
| F-03, F-16 | Fixed. The `test` account lands on `/ui/login?denied` with the alert "This account is not an administrator…"; a logout without a token gives `/ui/login?csrf`. |
| F-04 | Fixed. `/` answers `302` to `/ui/`. |
| F-05, F-06 | Fixed. gzip on the login page and on the hashed bundle; `Cache-Control: max-age=31536000, public, immutable` on `main-*.js`; `index.html` stays `no-cache`. |
| F-09, F-13 | Fixed. The login card is centred (111 px above and below at 1366×768) and the page is no taller than the viewport; `noindex` and the description are present. |
| F-10, F-11 | Fixed. `Referrer-Policy: same-origin`, the `Permissions-Policy`, `Strict-Transport-Security: max-age=31536000 ; includeSubDomains` exactly once (the proxy adds no second one), and `Secure; SameSite=Lax` on both cookies. |
| F-15, F-17, F-22 | Fixed. The server page is 3,074 px tall (was 11,675) with 892 DOM nodes (was 1,430); at 375 px it is 7,918 px (was 40,394). The filter `SOPHISTICATED` shows "Showing 2 of 94 mods", Needs review shows 10 of 94, paging runs 1 to 4, and Cancel returns focus to the same Classify button. |
| F-18, F-19 | Fixed. Filters load from `?q=te&role=CLIENT` ("1 account"), the fields have `id` and `name`, and the earlier console issue is gone. Disable and End sessions show a confirmation and Cancel returns focus; the network log holds only GET requests. |
| F-14 | **Unchanged.** `GET /ui/api/servers/{id}` took 8.1 s on two runs and `/ui/api/session` 1.1 s. Still waiting for the profiling numbers. |
| Lighthouse (login page, desktop) | Accessibility 100, Best Practices 100, Agentic Browsing 100 (the layout-shift audit no longer fails). SEO 60 only because the `noindex` meta is intentional. |

What went wrong on the way, kept as a deployment lesson: the first attempt at the host change left the site at `502 Bad Gateway`. The proxy (nginx-proxy-manager) runs on another machine and reaches the server over the LAN, but the server's `.env` ended up with two `MODSYNC_BIND_ADDRESS` lines (the server's LAN address, then `127.0.0.1`). Compose uses the last one, so the port bound to loopback and the proxy could not connect. The server log showed only health checks, never a proxied request. Keeping one line with the LAN address fixed it. The deployment docs now describe a proxy on another machine and this symptom.

Not tested: the real Google or GitHub sign-in round trip, admin sign-out through the UI button on this deployment (tested for the `test` account over HTTP), and HTTP/2 on the proxy (F-07). F-08 and F-20 remain open.
