# Third-party dependencies

No third-party source is copied into this repository. The projects below are used as declared dependencies (`backend/pom.xml`, `frontend/package.json`, `frontend/package-lock.json`) and keep their own licences. The licence names are from general knowledge of these projects and **have not been machine-verified**: run a licence scanner (for example `license-checker` for npm, the Maven `license` plugin for Java) before redistribution, and include the notice files those licences require. `npm audit` reported 0 known vulnerabilities on 2026-10-26 (`npm ci` output).

| Component | Use | Licence (believed) |
|---|---|---|
| Spring Boot, Spring Security, Spring Session, Spring Data JPA, Hibernate | Backend | Apache-2.0 (Hibernate: LGPL-2.1 / Apache-2.0) |
| Flyway Community, flyway-database-postgresql | Migrations | Apache-2.0 |
| PostgreSQL JDBC driver | Database access | BSD-2-Clause |
| PostgreSQL 16 (container image `postgres:16-alpine`) | Database | PostgreSQL Licence |
| Testcontainers, JUnit, AssertJ, Mockito | Backend tests | MIT / EPL-2.0 / Apache-2.0 |
| Next.js, React, React DOM | Frontend | MIT |
| TypeScript, Playwright | Build, browser tests | Apache-2.0 |
| ESLint, eslint-config-next | Lint | MIT |
| Noto Sans, Noto Sans Devanagari (loaded by `next/font/google` at build time) | UI fonts | SIL Open Font Licence 1.1 |

The Next.js production build downloads the two Noto fonts from Google Fonts at build time, so it needs network access.
