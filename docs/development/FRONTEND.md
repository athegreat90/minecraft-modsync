# Frontend development (Angular)

The administration interface lives in `frontend/` and ships inside `modsync-server.jar` under `/ui`. See [ARCHITECTURE.md](../../ARCHITECTURE.md#administration-interface) for how it fits the server and [the SSO guide](../security/SSO_SETUP.md) for sign-in.

## Layout

```text
frontend/src/app/
  core/        auth (session, guard, csrf), http (interceptors), api (typed client + models), navigation
  features/    authentication, dashboard, servers, users, account (lazy-loaded routes)
  shared/      layouts (application shell)
frontend/src/styles/_tokens.scss   design tokens (colour, type, spacing, motion, breakpoints)
frontend/src/styles.scss           global building blocks (.ms-card, .ms-table, .ms-button, ...)
```

Standalone components, strict TypeScript, signals, `OnPush`, lazy routes, typed reactive forms, functional interceptors and guards. The prefix is `ms`. Components hold presentation state only; the server decides every security and business question.

## Commands

```sh
cd frontend
npm ci
npm start               # http://localhost:4200/ui/ with a proxy to the server on :8080 (proxy.conf.json)
npm run lint            # angular-eslint
npm test                # Vitest through the Angular unit-test builder (43 tests)
npm run test:coverage   # also writes coverage/frontend/lcov.info
npm run build           # production build into frontend/dist
```

Gradle builds the application automatically for the server (`:modsync-server:buildFrontend` runs `npm ci` and `npm run build`); pass `-PwithoutFrontend` to skip it when only Kotlin matters (the jar then has no UI). Node 24 and npm 11 are used in CI and in the Docker build.

## Design system

Original Norse/Nordic-inspired identity (no third-party assets or fonts): near-black background, charcoal surfaces, forest green and teal, emerald highlight, aged-gold accents and warm off-white text, defined once as CSS custom properties in `_tokens.scss`. Headings use the system's serif stack and operational data the system sans stack, so no web fonts are downloaded. Motion uses the `--ms-motion-*` tokens and collapses to zero under `prefers-reduced-motion`. Breakpoints: compact below 48rem (top bar with a navigation drawer, tables stacked into cards through each cell's `data-label`), wide from 64rem (sidebar). Interactive controls are at least 2.75rem (44 px) high; focus rings are always visible.

## Accessibility and responsiveness checks

`accessibility.spec.ts` runs axe-core on the login page, shell, overview and the account form (colour contrast needs real layout and is not checked there). Not automated yet: browser-level viewport tests (320, 375, 390, 430, 768, 1024, 1440 px, portrait and landscape), contrast measurement and screen-reader testing. These are outstanding and should be added before claiming WCAG 2.2 AA.

## Budgets

The production build fails above 500 kB initial JavaScript (warns at 350 kB). Current initial size is about 74 kB gzipped. PWA/offline support is deliberately not included: offline administration would cache authenticated data and adds complexity for no stated need.
