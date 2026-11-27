# Frontend development (Angular)

The administration interface lives in `frontend/` and ships inside `modsync-server.jar` under `/ui`. See [ARCHITECTURE.md](../../ARCHITECTURE.md#administration-interface) for how it fits the server and [the SSO guide](../security/SSO_SETUP.md) for sign-in.

## Layout

```text
frontend/src/app/
  core/        auth (session, guard, csrf), http (interceptors), api (typed client + models), navigation, theme (ThemeService)
  features/    authentication, dashboard, servers, users, account (lazy-loaded routes)
  shared/      layouts (application shell), components (theme switcher)
frontend/src/styles.scss              imports only: bootstrap, themes, utilities (order matters)
frontend/src/styles/_bootstrap.scss   Bootstrap 5.3 as CSS only: Sass variables, the partials and utilities the app uses
frontend/src/styles/_tokens.scss      accent/text colours, fonts and the mixin that emits --ms-* and --bs-* from a theme map
frontend/src/styles/themes/           one Sass map per theme (_dark.scss, _amoled.scss)
frontend/src/styles/_utilities.scss   the few rules Bootstrap cannot express (focus ring, stacked table, Valhalla accents)
frontend/public/theme-init.js         applies the saved theme before first paint (same-origin script, CSP-safe)
frontend/e2e/                         real-browser checks (Playwright), local only
```

Standalone components, strict TypeScript, signals, `OnPush`, lazy routes, typed reactive forms, functional interceptors and guards. The prefix is `ms`. Components hold presentation state only; the server decides every security and business question. Commands that change data (policy, classification, account actions, provider links) go through the shared `runCommand` helper in `core/api/run-command.ts`, which clears the previous messages, shows the success text and reloads, or shows a safe error message; keep new pages on it rather than copying the pattern (SonarQube flags duplicated blocks on new code).

## Commands

```sh
cd frontend
npm ci
npm start               # http://localhost:4200/ui/ with a proxy to the server on :8080 (proxy.conf.json)
npm run lint            # angular-eslint
npm test                # Vitest through the Angular unit-test builder (117 tests)
npm run test:coverage   # also writes coverage/frontend/lcov.info
npm run build           # production build into frontend/dist
npm run test:e2e        # real browser (Chromium) against frontend/dist served with the production CSP; run npm run build first
```

The first `npm run test:e2e` needs a browser: `npx playwright install chromium`. Set `E2E_SCREENSHOTS=<dir>` to also write full-page screenshots per theme and width. The browser checks are not part of CI.

Gradle builds the application automatically for the server (`:modsync-server:buildFrontend` runs `npm ci` and `npm run build`); pass `-PwithoutFrontend` to skip it when only Kotlin matters (the jar then has no UI). Node 24 and npm 11 are used in CI and in the Docker build.

## Design system

Original Norse/Nordic-inspired identity (no third-party assets or fonts): near-black background, charcoal surfaces, forest green and teal, emerald highlight, aged-gold accents and warm off-white text. Headings use the system's serif stack and operational data the system sans stack, so no web fonts are downloaded.

**Bootstrap 5.3 is the layout and component foundation, as CSS only.** There is no Bootstrap JavaScript, no Popper and no jQuery; Angular owns every interactive state (for example the navigation panel toggles `.show` from a signal). `styles/_bootstrap.scss` sets the Sass variables first and imports only the partials the app uses, and it trims the utilities and the grid to what the templates use, because the stylesheet counts toward the 350 kB initial-bundle warning. When a template needs a Bootstrap class that is not in the build (an unused utility, a new component), add it to `_bootstrap.scss` and check the build size.

### Themes

| Theme | id | Character |
|---|---|---|
| Valhalla Dark (default) | `dark` | dark forest surfaces with the page gradient |
| AMOLED Black | `amoled` | `#000000` page, navigation and cards, `#101010` raised surfaces, no gradient, no shadows |

`core/theme/ThemeService` (signals, root singleton) validates the id, writes `data-theme` (application) and `data-bs-theme="dark"` (Bootstrap; both themes are dark) on `<html>`, updates `<meta name="theme-color">`, persists the choice in `localStorage` under `modsync.theme` and follows other tabs through the `storage` event. Unavailable or invalid storage falls back to Valhalla Dark. Nothing is sent to the server. `public/theme-init.js` is a blocking same-origin script in `<head>` that applies the saved theme before the first paint, so there is no flash; it is an external file because the CSP allows only `script-src 'self'`. The service adopts the attributes the script already set, and `theme-init.spec.ts` fails when the script's ids or colours drift from `THEMES` in `theme.model.ts`. The selector (`ms-theme-switcher`) is a native radio group, so arrow keys and the selected state work without extra code; it appears in the header, on the sign-in page and on the Account page. Browsers expose no reliable AMOLED detection, so the theme is always an explicit choice.

**Single source of truth.** Each theme is one Sass map in `styles/themes/`; the `emit` mixin in `_tokens.scss` writes both the `--ms-*` tokens and the Bootstrap variables (`--bs-body-bg`, `--bs-body-color`, `--bs-border-color`, `--bs-link-color` and their `-rgb` companions) from that map, so Bootstrap components inherit the theme and feature components never mention a colour. Accents (forest, teal, emerald, gold, danger, warning, focus) and text colours are shared by all themes and defined once in `_tokens.scss`.

**Adding a theme:** add `styles/themes/_<id>.scss` with a map (`bg`, `surface`, `surface-raised`, `border`, `border-control`, `page`) and `@use` it in `styles.scss`; add the entry to `THEMES` in `theme.model.ts` and the id and colour to `public/theme-init.js`; run `npm test` and `npm run test:e2e`. No feature component changes.

**Interactive boundaries.** `border` only separates surfaces. Input, select and checkbox outlines use `--ms-border-control`, chosen to reach 3:1 against every surface of the theme (WCAG 1.4.11); AMOLED's decorative `#363636` border would not.

### Conventions

- Use Bootstrap classes and utilities (`btn btn-primary`, `card`, `row row-cols-*`, `d-flex flex-wrap gap-2`, `text-body-secondary`, `badge text-bg-*`, `alert`, `form-label` / `form-control`, `invalid-feedback`). Do not add component SCSS or inline styles; a rule belongs in `_utilities.scss` only when Bootstrap cannot express it.
- Layout is mobile-first with Bootstrap's breakpoints (576, 768, 992, 1200, 1400 px). Data-heavy pages fill the width; forms, sign-in and account content are constrained with grid columns (for example `col-12 col-md-10 col-lg-8 col-xl-6`). Tables use `table ms-table-stack`: below `md` each row becomes a card that prints each cell's `data-label`, so there is no horizontal scrolling and the column names stay available. Navigation is a sidebar from `lg` and a collapsible panel below it.
- Colour never carries meaning alone: badges and states always have text. Interactive controls are at least 2.75rem (44 px) high (`$input-btn-padding-y`, and a `min-height` on `.form-select`). The focus ring is the global `:focus-visible` outline, restated for the Bootstrap controls that remove it.
- **CSP and `data:` images.** The CSP has no `img-src`, so `data:` images are blocked. Bootstrap embeds some (select chevron, check mark, radio dot, switch, validation icons); they are disabled in `_bootstrap.scss`, and checkboxes, radios and selects use native rendering. Do not enable validation icons, switches or other components that rely on embedded images without changing the CSP deliberately. A new asset type (fonts, SVG, images) also needs the public allow-list and cache patterns in the server (`DashboardSecurity.kt`, `StaticAssets.kt`).
- Motion collapses under `prefers-reduced-motion`; the AMOLED theme adds no shadows or glow.

## Accessibility and responsiveness checks

`accessibility.spec.ts` runs axe-core in both themes on the login page, shell, overview, users list, account detail, account creation, account page and server page (colour contrast needs real layout and is not checked in jsdom). Tests must assert something SonarQube can see (`expect(...)`): a test that only calls `http.expectOne(...)` or `http.expectNone(...)` is reported as `typescript:S2699`; assert the request method or body instead.

`e2e/responsive.spec.ts` (Playwright, `npm run test:e2e`) drives a real Chromium against the production build, served by `e2e/mock-server.mjs` with the same CSP header as Spring Boot and fixture API data. For every page and theme it checks, at 320, 375, 390, 575, 576, 767, 768, 991, 992, 1199, 1200, 1399, 1400, 1440, 1920, 2560 and 3440 px plus landscape phone and tablet sizes: no horizontal overflow, no CSP violation, no console error; and axe (WCAG 2.0/2.1/2.2 A and AA, colour contrast included) at 320, 768 and 1440 px. It also checks that AMOLED paints `rgb(0, 0, 0)` with no gradient, that the theme survives a reload and is applied with Angular's scripts blocked (no flash), that switching needs no request, that another tab is followed, that the drawer opens below `lg` and the sidebar is persistent from `lg`, and the 44 px touch target. `e2e/real-server.spec.ts` repeats the sign-in, theme-persistence, overflow, CSP and axe checks against a real server and database; it is skipped unless `E2E_REAL_URL` (plus `E2E_REAL_USER` and `E2E_REAL_PASSWORD`) is set, for example `E2E_REAL_URL=http://localhost:8080 E2E_REAL_USER=admin E2E_REAL_PASSWORD=... npm run test:e2e -- real-server`. Not covered: screen-reader testing.

## Budgets

The production build fails above 500 kB initial JavaScript and CSS (warns at 350 kB). Measured at 0.7.0: initial total 348.7 kB raw (84.4 kB estimated transfer), about 1.3 kB below the warning, so adding Bootstrap partials or utilities needs the size check described above. PWA/offline support is deliberately not included: offline administration would cache authenticated data and adds complexity for no stated need.

### Bootstrap migration, measured

Both columns were measured with the same commands on a checkout of `main` (0.6.5) and on this branch (0.7.0).

| | 0.6.5 | 0.7.0 |
|---|---|---|
| Custom SCSS | 204 lines in 4 files | 322 lines in 6 files (256 without blank and comment lines): the theme map, token emission and Bootstrap configuration are new |
| Component SCSS files | 2 (`shell.scss`, `login.scss`) | 0 |
| Global `.ms-*` classes | 14 | 2 (`.ms-table-stack`, `.ms-sidebar`) |
| Production CSS | 4.41 kB raw, 1.26 kB estimated transfer | 77.5 kB raw, 11.2 kB estimated transfer (13.8 kB gzip) |
| Initial total | 272.4 kB raw, 75.6 kB estimated transfer | 348.7 kB raw, 84.4 kB estimated transfer |
| Unit tests, line coverage | 79 tests, 97.1% | 117 tests, 97.2% |
| Horizontal overflow at 320 px (shell pages) | 28 px on 6 of 7 pages | none (all pages, both themes) |

Bootstrap adds about 73 kB of raw CSS because its grid, forms, buttons and reboot are real CSS. The number of custom lines went up, not down: what shrank is the hand-written component styling and the number of bespoke classes, and themes and Bootstrap's Sass configuration are new code.
