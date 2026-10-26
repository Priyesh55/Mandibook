# Mandi Book: authentication and multi-tenant security — decision memo

Status: **decision memo, nothing implemented.** It reviews `docs/AUTHENTICATION_AND_MULTI_TENANT_SECURITY_BLUEPRINT.md` (version 2, "the blueprint"). Date of review: 2026-10-20.
Identifiers (AP-n approved decisions, RD-n recommended defaults, Q-n open questions, AV-n assumptions) are those of the blueprint.

## 0. How to read this memo

**Evidence labels**

| Label | Meaning |
|---|---|
| **[REPO]** | Fact read from the Mandi Book repository in this review. |
| **[EXP]** | Fact observed by running a **throwaway experiment** in this review. The experiments lived outside the repository (scratch directory, an H2-backed Spring Boot app, a separate disposable PostgreSQL 16 container, a copy of the repository's own Next.js build) and were deleted or stopped afterwards. They never touched the Mandi Book development database or any repository file. |
| **[ASSUMED]** | Not verified here. Each one names what would verify it. |
| **[DECISION]** | Belongs to the product owner; the memo gives a recommended default and the consequence of each answer. |
| **[REC]** | Recommendation of this memo. |

**Requirement columns** (used in every recommendation table): **L** = needed before local development of the feature, **I** = needed before an internal demonstration (synthetic data, trusted people), **P** = needed before real customers' data or real users. "—" means not needed at that stage; "(opt)" means helpful but optional.

**What this memo does not do:** it changes no application code, dependency, configuration, migration, test or other document; it does not commit.

---

## 1. Summary of recommendations

| # | Recommendation | Section |
|---|---|---|
| 1 | Keep the platform console as a **separate deployable** that is started on demand, command-line first, with its own hostname, session store and `mandi_platform` role. The **database role and schema separation is the one non-negotiable layer**; the other layers are cheap once it exists. A single-application design with a second filter chain fails four guarantees (section 3). | 3 |
| 2 | Fix the PostgreSQL boundary in the **exact form tested here**: revoke `USAGE` on schema `public` and `EXECUTE` on functions from `PUBLIC`, set default privileges, no role memberships, and an **allow-list grant-enumeration test generated from the catalog**. With PostgreSQL defaults the experiment showed a business `SECURITY DEFINER` function **returning a firm name to the platform role**. | 4 |
| 3 | Change the blueprint's `platform.owner_user_summary` to list **owner accounts only** (join through `owner_account`), because "all `app_user` rows" would show employee identities, which is business data. | 4.1 |
| 4 | Do not give the platform role a function that writes tokens it can read; use a **void** function that queues the email so no administrator or platform process ever sees a usable token. | 4.2, 5 |
| 5 | **Email delivery is a production launch blocker**, but not a development blocker. | 5 |
| 6 | Lifecycle semantics in section 6 (14 days from acceptance, derived expiry, per-request gate, suspension as an overlay that does not stop the clocks). | 6 |
| 7 | `DEFAULT` firm: a **database-owner runbook with assertions and a read-only verification**, not a platform feature. | 7 |
| 8 | Audit: add `actor_user_id`, `membership_id`, `request_id` **and `actor_role`** (role at time of action). Leave all historical rows untouched. | 8 |
| 9 | **The blueprint's passive session-status endpoint (RD-6) does not work if built as an ordinary controller**: in the experiment every variant except a servlet filter ordered before the session filter extended the idle timer. | 10.3 |
| 10 | **BCrypt:** `encode` rejects more than 72 bytes but **`matches` silently truncates**. The 72-byte rule must be enforced on the login path too. | 10.2 |
| 11 | Start with the smallest slice in section 9: exposure check, identity tables, platform database boundary (database only), authentication, Farmers behind policy with a real two-firm isolation test. | 9 |

---

## 2. Verified facts and what is still assumed

### 2.1 Repository facts [REPO]

| Fact | Where |
|---|---|
| No `spring-security` or session dependency in `backend/pom.xml`; no session, cookie or CSRF configuration in `application*.properties`. | `backend/pom.xml`, `application*.properties` |
| Boot 4.1.1 manages Spring Security 7.1.1, Spring Session 4.1.1, Spring Framework 7.0.9, Tomcat 11.0.24, Hibernate 7.4.5.Final. | Boot BOM resolved for the throwaway app |
| `audit_event(id, entity_type, entity_id, action, details varchar(1000), actor varchar(100) NOT NULL default 'anonymous', created_at, firm_id)` with append-only triggers (UPDATE, DELETE, TRUNCATE blocked). | `V3`, `V4` |
| `V4` creates `firm(id, code, name, name_hi, active boolean default true, created_at)` and inserts the **`DEFAULT` firm in every environment**; `V2` seeds three items (Onion, Potato, Garlic) that `V4` backfills to that firm. | `V2`, `V4` |
| No migration creates an extension. | `grep` over `db/migration` |
| `CurrentFirm` / `SingleFirmContext`; the two-firm test uses a `SwitchableFirm` stand-in. | `FirmIsolationIntegrationTest` |
| Backend listens on `0.0.0.0:8081` and the database container on `0.0.0.0:5433`. **Whether they are reachable from another machine has not been verified.** | configuration; blueprint 12.3 |
| Next.js `rewrites` destination is baked into the build: `http://localhost:8081/api/:path*`. | `.next-e2e/routes-manifest.json` |
| Next.js proxy source: `changeOrigin: true`, a 30 s timeout, and it sets **only** `x-forwarded-host`. | `next/dist/server/lib/router-utils/proxy-request.js` |

### 2.2 Blueprint assumptions after this review

| AV | Result | Evidence |
|---|---|---|
| AV-1 proxy, cookies, CSRF | **Largely verified** over plain HTTP with a throwaway backend behind the repository's Next.js build; **TLS and real browsers not tested** | 10.4 |
| AV-2 BCrypt behaviour | **Verified**; one dangerous finding | 10.2 |
| AV-3 passive status endpoint | **Verified, with a correction**: the obvious implementation fails | 10.3 |
| AV-4 per-request lookups cheap | **Not measured** (the experiment used an in-memory map) | — |
| AV-5 definer functions and cross-schema views | **Verified** for grants and visibility; **`pg_dump`/restore not tested** | 4.3 |
| AV-6 two applications, multi-module Maven | **Not verified** | — |
| AV-7 RLS overhead | Not in scope | — |
| AV-8 TOTP and email libraries for Boot 4 | **Not verified** | — |
| AV-9 database time as the single clock | **Not tested**; the experiment mixed JVM and session-table times | 10.3 |

---

## 3. Point 1 — Platform-administrator isolation

**Constraint (AP-1):** the platform administrator manages owner accounts only and has no access to firms or business data by any path.

### 3.1 What each element of the proposed design guarantees

The blueprint proposes five elements. Each guards against a different failure, and they do not all need the same strength.

| Element | Guarantee it gives | Without it | Genuine separation needed? |
|---|---|---|---|
| **A. `mandi_platform` role and schema with no grants on business objects** | Even code or SQL injected into the platform side cannot read or write business tables, functions or views. **[EXP: about 50 attempted accesses in two configurations plus two misconfiguration cases, 4.3]** | The guarantee reduces to "the platform code is careful". | **Yes, mandatory.** It is the only layer that survives a bug in platform code. |
| **B. Separate process (deployable) with only the platform database credentials** | A remote-code or dependency flaw in the console does not yield the business credentials, the business connection pool or business sessions, because they are not in that process. Also lets the console be **stopped when unused** and exposed on a different network. | Both credentials live in one JVM; anything that runs inside the JVM can use the business pool. | **Yes for production.** Not needed for local development. |
| **C. Separate Maven module** | Compile-time guarantee that platform classes cannot import business entities or repositories (and the reverse). | An ArchUnit rule achieves most of the same in one module, but a second deployable needs its own artifact anyway. | **No** if B is done with its own build artifact; it is the natural form of B, not an extra layer. |
| **D. Separate session store and cookie** | A business session cannot be replayed on the platform side or the reverse. | One shared table is a business table that the platform role must not read (it is on the revoke list) — the separate store follows from A. | **Follows from A.** Costs one table in schema `platform`. |
| **E. Separate hostname** | Browser **origin** isolation: script injected into the business origin cannot make same-origin calls to platform endpoints, and cookies of one are not sent to the other. Cookies are scoped by **host, not port** (browser rule, **[ASSUMED]**, not re-tested here), so a different port on the same host does not isolate them. | An XSS in the business application (which the platform role cannot prevent) could call platform routes with a platform admin's browser session if both share an origin. | **Yes for production** (a distinct host name or subdomain); a different port plus different cookie names is acceptable for local development. |
| **F. Network allow-list / VPN** | The console is unreachable from the public internet and from business users. | Exposure depends on application authentication alone. | **Yes for production.** |

### 3.2 The simpler alternative and what it fails to guarantee

**Alternative S:** one Spring Boot application, a `/platform/**` route group with its own `SecurityFilterChain`, its own cookie name, a second datasource whose credentials are the `mandi_platform` role, and ArchUnit rules.

| Guarantee | Alternative S | Proposed design |
|---|---|---|
| Console code cannot read business data through SQL | **Holds** if A is done | Holds |
| Compromise of console code (injection, dependency flaw, deserialization) yields the **business** credentials and pool | **Fails**: both pools live in one process | Holds |
| Platform routes are never reachable through the business listener or the business reverse proxy | **Fails by default**: they share a listener, and one proxy rule or mistake exposes them | Holds (no business route exists on the platform listener) |
| Business application can be patched or restarted without the console, and the console can be **off** | **Fails**: one process | Holds |
| An XSS or session confusion in business pages cannot reach platform routes | **Fails** on a shared origin | Holds with E |
| Logs, secrets, and error pages of one cannot disclose the other's | **Fails**: one process, one log stream | Holds |
| Operational cost | Lowest | A second process, port and hostname, a runbook, a multi-module build |

### 3.3 Recommendation [REC]

**Adopt the proposed design in its leanest form** (RD-1 and RD-13 combined): a separate deployable, **command-line first**, **started on demand**, bound to loopback or an allow-listed network, own hostname when a web UI is later added, own `platform.spring_session` store if a web UI exists, and the `mandi_platform` role. Do not build a web console until it is needed.

| Recommendation | Security benefit | Complexity / cost | Risk if deferred | L | I | P |
|---|---|---|---|---|---|---|
| `mandi_platform` role and `platform` schema, no business grants (A) | Survives bugs in platform code | Low: the repository already has `DatabaseRoleGuard`, grant scripts and a role-verification pattern | The boundary becomes a code-review promise | — | **Yes** (skeleton and grant test) | **Yes** |
| Separate deployable with only platform credentials (B), command-line first | Credential and blast-radius isolation; console can be off | Medium: Maven multi-module refactor (AV-6 unverified) and a second Spring Boot main; a command-line tool is small | Later retrofit is a larger refactor than starting separate | — | — (opt) | **Yes** |
| Maven module split (C) | Compile-time dependency direction | Part of B | Same as B | — | — | Yes (with B) |
| Separate hostname (E) and network allow-list (F) | Origin and network isolation | Low once a web UI exists; none for a command-line tool | A shared origin makes any business XSS a platform risk | — | — | **Yes** (when a web UI exists) |
| Mandatory platform-admin MFA (RD-2) | A phished administrator password is not enough | Medium (TOTP library, AV-8 unverified) | Single factor on the most privileged account | — | — | **Yes** |
| First platform-admin creation by a documented out-of-band database-owner step | No self-registration path | Low: runbook | An unaudited seed path | — | — | **Yes** |

**Why this does not weaken AP-1:** every layer only removes capability. The administrator's role has no grant to `firm`, `firm_member`, `audit_event`, `credential_token`, MFA tables, sessions or business tables (verified in 4.3), and no route accepts or returns a firm id (route-absence test, blueprint section 14.1).

---

## 4. Point 2 — The database boundary

### 4.1 Exact owner-account metadata the platform administrator needs

The administrator may need to answer only: *who is this owner, can they sign in, what is their commercial state, and what happened to the account.*

| Field | Source | Needed for | Notes |
|---|---|---|---|
| owner account id, display name (organisation label chosen by the admin or owner) | `platform.owner_account` | find the account | Not a firm name. Do not copy a firm's name here. |
| stored commercial status, `trial_started_at`, `trial_ends_at`, `paid_until`, `access_grace_until`, `plan_code` | `platform.owner_account` | trial and subscription management | Derived effective status through the view `public.account_access` |
| `suspended_reason_code` (fixed taxonomy: `NON_PAYMENT`, `SECURITY_REVIEW`, `OWNER_REQUEST`, `OTHER`) | `platform.owner_account` | audit of suspension | Free text is a leak risk: if used, it is the admin's own note, never business data |
| invitation state: `PENDING`, `ACCEPTED`, `EXPIRED`, `REVOKED`, sent/accepted/expires timestamps | `platform.owner_invitation` | onboarding follow-up | **Never the token or link** |
| user id, **normalized login email**, display name, authentication status (`ACTIVE`/`DISABLED`/locked flag), `last_login_at`, `mfa_enabled` (boolean), `email_verified_at` | view `platform.owner_user_summary` | support the owner without seeing secrets | **Restrict the view to owner users only** (below) |
| account events (renewals, suspensions, reinstatements, invitation events) | `platform.subscription_event`, `platform.platform_audit_event` | accountability | Append-only |

**Must not be visible, even aggregated:** firm count, firm names or codes, number of employees, employee identities, number of bills, data size, last business activity, login IP or user agent, password hash, MFA secret, recovery codes, any token, sessions. An aggregate such as "owner has 3 firms" is itself a statement about the owner's firms, so AP-1 forbids it. A later plan-limits feature would need a separate decision.

**Correction to the blueprint [REC]:** blueprint 3.3 describes `platform.owner_user_summary` as columns of `app_user`. If it selects all users, it lists **employees** (who works for whom is business data). The view must join `platform.owner_account.owner_user_id` and return only those users. The test in 4.4 inserts a synthetic employee and asserts it is absent.

| Recommendation | Security benefit | Complexity / cost | Risk if deferred | L | I | P |
|---|---|---|---|---|---|---|
| Owner-only `owner_user_summary` with the column allow-list above | Employee identities stay hidden | One join in a view | Silent disclosure of employee identities | — | **Yes** | **Yes** |
| No aggregated business metadata for the administrator | AP-1 holds even for counts | None | Counts creep in as "useful" features | — | Yes | **Yes** |
| No cross-schema **foreign key** from `platform.owner_account` to `public.app_user`; keep a plain id column checked by a function | Avoids a referential link whose errors can reveal existence and whose cascade rules cross the boundary | Low | An FK error message can reveal whether an id exists. **[ASSUMED]** — not tested here | — | opt | opt |

### 4.2 Minimum views, functions and grants

| Object | Granted to | Direction | Contents |
|---|---|---|---|
| Schema `platform` | `USAGE` to `mandi_platform` | — | — |
| Tables `platform.owner_account`, `owner_invitation`, `platform_admin` (and sessions if a web UI exists) | `SELECT, INSERT, UPDATE` to `mandi_platform` | platform owns | — |
| `platform.subscription_event`, `platform.platform_audit_event` | `SELECT, INSERT` only (no UPDATE, DELETE, TRUNCATE) plus append-only triggers | platform owns | — |
| View `platform.owner_user_summary` (owner users only) | `SELECT` to `mandi_platform` | platform reads | allow-list in 4.1 |
| View `public.account_access` | `SELECT` to `mandi_app` | business reads | account id, stored and derived status, timestamps, grace; **no personal data** |
| View `public.account_event_view` | `SELECT` to `mandi_app` | business reads | owner-visible account events |
| Function `public.accept_owner_invitation(bytea, bigint)` `SECURITY DEFINER`, pinned `search_path` | `EXECUTE` to **`mandi_app` only** | business writes | verifies invitation, links user, starts trial |
| **New:** function `public.queue_owner_credential_email(owner_account_id, purpose)` returning **void**, `SECURITY DEFINER` | `EXECUTE` to `mandi_platform` only | platform triggers | creates the token and an outbox row inside the business side; **returns nothing** and the business mailer sends it to the **registered** address. Without it the platform would have to hold or see a token |
| Everything else in `public` | **no** grant to `mandi_platform` | — | `REVOKE USAGE ON SCHEMA public FROM PUBLIC` and explicit grant to the roles that need it |

Notes:
* Suspension does **not** need a function that revokes sessions: the business application checks `public.account_access` on **every** request (blueprint 10.1), so a suspension takes effect on the owner's next request without the platform ever touching `public.spring_session`. This keeps the contract surface smaller than the blueprint's "owner's sessions revoked" wording. The business side may additionally delete the owner's session rows when it next sees the suspended state.
* Invitation tokens for **new** accounts are created by the platform process and only the hash is stored. The administrator never sees the link; the residual risk (an operator with host access) applies to an empty new account and is accepted, whereas **recovery** of an existing account never involves a token the platform can read.

### 4.3 What was tested [EXP]

A disposable PostgreSQL 16 container (same major version as the development database) held a synthetic `public` schema (firm, farmer, app_user, credential_token, audit_event with fake secrets), a `platform` schema, a view, a `SECURITY DEFINER` function, and the roles `mandi_app` and `mandi_platform` (both `LOGIN`, no memberships, no special attributes). Every statement ran as `mandi_platform` unless stated.

| Test | PostgreSQL defaults | Hardened |
|---|---|---|
| `SELECT` on each of 5 business tables | denied | denied |
| `SELECT` on `public.account_access` (a business-side view) | denied | denied |
| `owner_user_summary` readable; `password_hash` and `mfa_secret` columns | readable; columns do not exist | same |
| `platform.owner_account` readable | yes | yes |
| `CREATE TABLE` or `CREATE FUNCTION` in `public` or `platform` | denied | denied |
| `SET ROLE mandi_app`, `pg_read_file`, `COPY firm TO STDOUT`, `CREATE EXTENSION`, `SET session_replication_role` | denied | denied |
| **Call an ordinary public function** | **allowed** (`helper executed`) | denied |
| **Call a business `SECURITY DEFINER` function that returns firm names** | **ALLOWED: it returned `SECRET FIRM NAME`** | denied |
| Call `accept_owner_invitation` (meant for `mandi_app` only) | **allowed** (a write into platform data) | denied; `mandi_app` still allowed |
| Function created **after** the hardening | not applicable | denied (default privileges) |
| `has_table_privilege` over every table and view in `public` | all `false` | all `false` |
| Table and column **names** in the catalog | visible | visible |
| After `GRANT mandi_app TO mandi_platform` (a configuration mistake) | n/a | platform immediately reads `account_access` and calls `accept_owner_invitation`; detected by `pg_auth_members` (1 row); removed by `REVOKE` |
| A view over `firm` granted to the platform role by mistake | returns `SECRET FIRM NAME` (views run with their **owner's** rights); **detected** by a query listing grants outside the allow-list; removed | — |
| `SECURITY DEFINER` functions: pinned `search_path` | present in the test; reported through `pg_proc.proconfig` | — |

**Conclusions.**
1. Table grants alone are sound: the platform role could not read any table, including through `COPY`.
2. **The defaults are not enough**: by default `PUBLIC` may execute any function, and `SECURITY DEFINER` functions run with their owner's rights, so one business helper function written without thinking about the platform role is a data path. The fix that worked: `REVOKE USAGE ON SCHEMA public FROM PUBLIC` (and grant `USAGE` explicitly to the roles that need it), `REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC`, and `ALTER DEFAULT PRIVILEGES … REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC`.
3. Views and definer functions are **grant-driven**: only an allow-list test (below) notices a mistaken grant.
4. Schema **metadata** (table and column names) cannot be hidden from any connected role. This is acceptable only because no object is named after a tenant; **never create per-tenant tables or name objects after firms**.
5. One check was **inconclusive**: whether another role's running query text is visible through `pg_stat_activity`. The probe returned no row at all, which could mean "hidden" or "the probe query had already ended". Treat it as unverified and keep the platform role out of `pg_monitor`/`pg_read_all_stats`.
6. **Not tested:** `pg_dump` and restore with this layout (AV-5 remainder); the effect of revoking `PUBLIC` `USAGE` on the existing `mandi_app` role and on Flyway. Migrations contain no extensions **[REPO]**, which lowers the risk, but the existing suites must be re-run after the change.

### 4.4 How tests will prove the platform credentials cannot reach business data

The tests run in the existing integration-test style (a disposable database, the real migrations, synthetic data, the real `mandi_platform` login). They are generated from the **catalog**, not from a hard-coded table list, so a table added later is covered automatically.

| # | Test | Passes when |
|---|---|---|
| DB-1 | For **every** table, view, materialized view, sequence and function in **every** schema except the allow-list, evaluate `has_table_privilege`, `has_column_privilege`, `has_sequence_privilege`, `has_function_privilege` and `has_schema_privilege` for `mandi_platform` | all false |
| DB-2 | Run `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`, `COPY ... TO` against every business table **using a valid firm id and owner id** | every statement fails with SQLSTATE `42501` |
| DB-3 | Enumerate grants of `mandi_platform` and of `mandi_app` (including grants inherited through `PUBLIC`) and compare to the allow-list in 4.2 | no difference in either direction (a mistaken grant such as the `leaky_view` case fails the build) |
| DB-4 | `pg_auth_members` for `mandi_platform` (as member) | zero rows, including predefined roles (`pg_read_all_data`, `pg_monitor`, …) |
| DB-5 | Role attributes | `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS`, a connection limit, and not the table owner |
| DB-6 | For every function executable by `mandi_platform`: owner is the migration role, `prosecdef` functions have a pinned `search_path`, and the set equals the allow-list | exact match |
| DB-7 | For each allowed view: column list equals the allow-list; with a synthetic employee and a synthetic owner inserted, only the owner appears | exact match |
| DB-8 | After the real migrations: create a **new** table and a **new** function as the migration role and check DB-1 again | still all denied (default privileges work) |
| DB-9 | The platform application at start-up asserts `current_user = 'mandi_platform'` and refuses to start otherwise (the pattern of the existing `DatabaseRoleGuard`); its configuration contains no business datasource | start-up test |
| DB-10 | Business role in reverse: `mandi_app` cannot read or write any `platform` table | denied |
| DB-11 | **Other services:** the platform process's environment and `application` configuration contain no business credential (a configuration-scan test); the business process has no platform credential | scan clean |
| DB-12 | HTTP/CLI level: route and command enumeration shows no operation that accepts, returns or filters by a firm id; none under `/platform` lists firms | enumeration equals the allow-list |
| DB-13 | Backup/restore drill with both schemas, then DB-1 to DB-10 again on the restored database | pass |

| Recommendation | Security benefit | Complexity / cost | Risk if deferred | L | I | P |
|---|---|---|---|---|---|---|
| Hardening statements (public usage, function execute, default privileges) | Closes the definer-function path found in the experiment | Low; must be re-run against the existing suites | Any later helper function may leak | — | **Yes** | **Yes** |
| Catalog-generated tests DB-1 to DB-13 | A new object cannot silently open a path | Medium (a test class, a few hundred lines) | Boundary erosion | — | **Yes** | **Yes** |
| Review rule: every new `SECURITY DEFINER` function needs a security review and an allow-list entry | Stops "helpful" definer functions | Low | Same | — | Yes | **Yes** |

---

## 5. Point 3 — Recovery and onboarding

### 5.1 Lifecycle of each flow

All tokens: **256 bits from `SecureRandom`**, only the **SHA-256 stored**, single use consumed atomically, a new token supersedes earlier ones, link carries the token in the **URL fragment**, `Referrer-Policy: no-referrer`, generic responses with similar timing, throttled per email, per address and globally (blueprint 7.1). Authentication events are written to `auth_event` in their own transaction.

| Flow | Trigger | Token life | What proves control | Result | What the platform administrator sees | What the administrator can never see or set |
|---|---|---|---|---|---|---|
| **Owner invitation and email verification** | Admin provisions an owner account and the system sends the invitation | **7 days** (blueprint) — a decision for the owner whether shorter | Receiving the email and setting a password verifies the address (`email_verified_at`) | `app_user` created, **trial starts on acceptance** (RD-8) | State, sent/accepted/expires timestamps | Link, token, the owner's password |
| **Employee invitation** | Owner invites an email to a firm | 7 days | Same; an **existing** account must accept explicitly (Q-8) | `firm_member` created | **Nothing** (business data) | — |
| **Password reset** | Any user requests | **30 minutes** | Email delivery | New password; **all sessions revoked**; "password changed" notice to the address | Nothing about the request (no counts) | Token; a way to set the password |
| **Email address change** | Owner requests | 30 minutes each | Link to the **old** address, then to the **new** | Login email changed; notice | The new normalized email in the summary | **Cannot change an owner's login email** (change-then-recover is a takeover) |
| **Optional owner MFA enrolment** | Owner chooses in settings | Code entered within the enrolment session | Valid TOTP code | Secret stored encrypted; ten recovery codes shown once, stored as hashes; owner's other sessions revoked | `mfa_enabled` boolean only | Secret, recovery codes |
| **MFA recovery (lost device and codes)** | Owner requests; identity checked out-of-band by a documented procedure | 48-hour cooling-off during which the owner (notified at the registered address) can cancel | Registered-email confirmation plus **two** platform admins | MFA removed; password and email unchanged | The recovery request and its state | Any secret; the owner's password |

**Invariant to test [REC]:** *no single platform-administrator action, and no pair of them, yields access to an owner account.* Access needs at least one secret held only by the owner (password, recovery code, or registered mailbox). A test enumerates every platform operation and asserts that none returns, stores in a readable column, logs, or lets the caller choose a token, password, recovery code, MFA secret or login email.

### 5.2 What must be ready, and when

| Item | Security benefit | Complexity / cost | Risk if deferred | L | I | P |
|---|---|---|---|---|---|---|
| Email port with a **development sink** (a test-profile-only bean that records messages; production must refuse to start with it) | Lets the flows be built and tested without a provider | Low | Flows untested | **Yes** | Yes | forbidden in production |
| Production email delivery with sender-domain authentication (SPF, DKIM, DMARC) and bounce handling **[ASSUMED]: provider not chosen (Q-1)** | Invitations, reset and notices actually reach owners; without it admin-assisted recovery is the only fallback and conflicts with AP-1 | Medium: a provider, a domain, DNS work | **Launch blocker** | — | — (use the sink) | **Yes** |
| Invitation acceptance, reset, session revocation after reset, throttling, `auth_event` writes, notices | The core recovery chain | Medium | Locked-out owners; abuse | Yes | **Yes** | **Yes** |
| Platform-admin MFA | Protects the privileged account | Medium | Weak admin login | — | — | **Yes** |
| Owner MFA (optional for owners) | A stolen owner password is not enough | Medium-high (TOTP, recovery codes, recovery process) | Owner accounts hold all business data; password-only at first launch is a **residual risk to accept explicitly** | — | — | Optional for first customers; recommended within the first release |
| Break-glass admin-assisted recovery without email | Only if the owner insists on shipping before email (Q-2) | High and **detective, not preventive** | Account takeover risk accepted | — | — | **Not recommended**; email first |
| A written recovery runbook (who verifies identity, how, evidence recorded) | Consistent handling | Low | Ad hoc handling | — | — | **Yes** |

**Decision [DECISION, Q-1/Q-2]:** choose the email channel and confirm that real onboarding waits for it. **Recommended: wait.**

---

## 6. Point 4 — Trial and suspension semantics

Statuses are separate (AP-10). *Approved* items are decisions; the rest are recommendations marked [REC] or open [DECISION].

| Topic | Semantics | Status |
|---|---|---|
| **Trial length** | 14 days, `trial_ends_at = trial_started_at + 14 × 24 h`, computed by the server from **database** time (`now()`); access allowed while `now() < trial_ends_at`; display in the owner's local time zone; calendar-day wording avoided | Length approved (AP-10). Start-on-acceptance and arithmetic [REC, RD-8]. |
| **Trial start** | At **invitation acceptance**, not at provisioning, so unaccepted invitations do not use trial days | [REC, RD-8] |
| **Invitation expiry** | 7 days from sending; resending supersedes; an expired invitation needs the admin to issue a new one; it does not affect any trial (none started) | 7 days [REC] |
| **Trial expiry** | **Derived**, not stored: `status = 'TRIAL' and now() >= trial_ends_at`. Sign-in, account page and logout still work; protected operations are blocked; **no data deleted** | [REC, RD-7] |
| **Trial extension** | Allowed **by the administrator only**, with a reason code, recorded in `subscription_event`; it changes `trial_ends_at`; recommended: at most once per owner account, up to a fixed number of days | **Open [DECISION, Q-5]**: whether extensions exist and whether the trial is once per owner or per email |
| **Payment renewal** | Manual (no billing integration): the administrator records payment and sets `paid_until`. Rule recommended: the new period starts at the **later of** the current `paid_until` and the renewal date | Manual activation approved (AP-10); start rule [REC] |
| **Paid-period lapse** | When `paid_until` passes and `access_grace_until` is not in the future, the derived state is `EXPIRED` | [REC] |
| **Grace period** | None automatic. The administrator may set `access_grace_until` per account; during it access is **full** (no read-only mode) because a read-only mode needs code in every write endpoint | [REC, RD-12]; whether a read-only grace and a data export during expiry or suspension are required is **open [DECISION, Q-3]** |
| **Suspension** | Stored `SUSPENDED` with a reason code, set by the administrator. Effective on the owner's **next request** because the business side checks the account on **every** request; employees of the owner's firms are blocked the same way; **no data deleted**; the clocks (`trial_ends_at`, `paid_until`) **keep running** (suspension does not stop the trial) | Suspension approved (AP-10); clock rule [REC] |
| **Reactivation** | Administrator sets the account back to its stored status (`TRIAL` or `ACTIVE`), subject to the derived rule: reactivating a suspended expired trial yields an expired trial unless the administrator also extends or activates it. The owner re-signs in if sessions were removed | [REC] |
| **Employee deactivation** | Done **by the owner**; takes effect on that employee's next request in that firm (the per-request membership check); other firms unaffected; history preserved; reactivation by the owner. A deactivated employee can still sign in but sees no firms | [REC, approved by AP-2/AP-10 in principle]. **Verified** that a membership revoked mid-session produced `403` on the next read and write [EXP, 10.5] |
| **Data retention** | No automatic deletion on expiry or suspension; deletion needs a retention and anonymization policy | **Open [DECISION, Q-4]** |
| **What an expired or suspended owner can still do** | Sign in, view account page, change password, sign out. Recommended default: **nothing else** until renewal | Default [REC]; the lockout consequence (no access to own records) must be accepted explicitly [DECISION, Q-3] |

| Recommendation | Security benefit | Complexity / cost | Risk if deferred | L | I | P |
|---|---|---|---|---|---|---|
| Derived expiry with database time | No scheduler, no drift | A view and an index | Disagreeing states | — | Yes | **Yes** |
| Per-request account gate (`account_access`) | Immediate effect of suspension and expiry | Two indexed reads per request; **not measured** (AV-4) | Suspension lags until the session ends | — | **Yes** | **Yes** |
| Suspension does not stop the clocks | Predictable accounting | None | Disputes | — | — | Yes |
| Decide Q-3, Q-4, Q-5 | Removes unwritable behaviours | Owner time | Rework of the lifecycle later | — | — | **Yes** |

---

## 7. Point 5 — The existing `DEFAULT` firm

**Facts [REPO]:** `V4` inserts firm `DEFAULT` (`active = true`) in every environment and backfills existing rows to it. Without an owner or membership, nobody can use it once authentication exists (fail-closed), but it is still a row owned by no one that production should not carry active.

**Procedure [REC], run by the database owner, never through the platform console:**

| Step | Action | Evidence recorded |
|---|---|---|
| 1 | Verified backup and a restore drill into a scratch database (the existing `scripts/db` backup and restore scripts) | Backup checksum, restore row counts |
| 2 | **Read-only classification**, as the database owner: count rows per firm-scoped table grouped by `firm_id`, and list firms | Expect, for a fresh production database: one firm `DEFAULT`; only the three seed items; **no bills, lots, parties** |
| 3 | **Branch A, expected (no real data in `DEFAULT`):** a runbook script asserts the preconditions in step 2 and **no** `firm_member` or owner link, then sets `firm.active = false`. Verification: the business API cannot select the firm; `DEFAULT` appears in no owner's firm list; the platform role still cannot read `firm` (DB-1) | Before and after counts; the script's assertion output |
| 4 | **Branch B, only if `DEFAULT` holds real data** (for example an import of existing records): an approved adoption procedure. A named owner account is invited through the normal flow; after acceptance, the database-owner script sets `firm.owner_account_id` and creates the **owner's** membership; per-table row counts and checksums (counts and sums of key amount columns) are compared before and after; a second person reviews the output. The **platform administrator takes no part and sees nothing** | Counts, checksums, two-person sign-off |
| 5 | Rollback: `active = true` (Branch A); restore from the step-1 backup (Branch B) | — |

**Why a runbook rather than a migration:** a migration conditioned on "not the demo profile" runs against whatever is in the database at that time and cannot ask for a human check; a runbook asserts, records evidence and allows review. If the owner prefers automation, a guarded production-only migration that **only** deactivates a `DEFAULT` firm that passes the same assertions is acceptable (blueprint 13.2, option a).

| Recommendation | Security benefit | Complexity / cost | Risk if deferred | L | I | P |
|---|---|---|---|---|---|---|
| Deactivate or adopt `DEFAULT` by a runbook with assertions | No orphan firm; proof of what existed | Low (one script, two checks) | An active unowned firm; or accidental adoption by an unrelated owner | — | — | **Yes** (before the first production migration set) |
| Block sign-in selection of `DEFAULT` in non-demo profiles as a second line | Defence in depth | Low | A mistaken membership exposes it | — | — | Yes |

---

## 8. Point 6 — Audit migration

**Facts [REPO]:** `audit_event` is append-only (triggers on UPDATE, DELETE, TRUNCATE); `actor` is a text column that defaults to `'anonymous'`; `V4` added `firm_id` without firing the triggers.

### 8.1 Minimum audit columns

| Column | Type | Why | Required |
|---|---|---|---|
| `actor_user_id` | `bigint NULL`, FK to `app_user` | Stable identifier of who acted; never from the browser | Yes |
| `membership_id` | `bigint NULL`, composite FK `(membership_id, firm_id)` to `firm_member (id, firm_id)` | Which membership, in the same firm as the row | Yes |
| `actor_role` | `varchar(20) NULL` (`OWNER`/`EMPLOYEE`) | **Role at the time of the action** | **Yes [REC]** (the blueprint left this open) |
| `request_id` | `uuid NULL` | Correlate to logs | Yes |

* **Role at time:** `firm_member.role` is mutable, so `membership_id` alone cannot say what the actor was **then**. A one-word snapshot is simpler than a membership-history table; it is written in the same `INSERT` and cannot be altered (append-only triggers).
* **Membership status at time:** not stored. It is implied (the action happened, so the membership was active); a deactivation is recorded as its own audit or `auth_event` entry. `firm_member` rows are never deleted, so `membership_id` always resolves.
* **Text `actor`:** keep writing it for compatibility (`user:<id>` or `system:<job>`); never a name.
* **Display name:** shown from the user's **current** `app_user` row, so anonymization is possible without touching append-only data (Q-4).

### 8.2 Historical anonymous events

* Existing rows stay exactly as they are: `actor = 'anonymous'`, new columns `NULL`; **no backfill, no guess** (the triggers would block an UPDATE anyway).
* Adding nullable columns without defaults is a metadata change and does not fire row triggers: the `V4` precedent in this repository shows the same approach worked for `firm_id`. **Adding the foreign keys needs a `NOT VALID` then `VALIDATE` step or an index first** to avoid long locks **[ASSUMED]** (standard PostgreSQL practice, not tested here).
* A constraint for **new** rows only: `CHECK (actor_user_id IS NOT NULL OR actor LIKE 'system:%' OR created_at < <cutover timestamp>) NOT VALID`, so every new business event names a person or a system job, while old rows remain valid by definition.
* The interface labels old rows "recorded before sign-in was introduced".

| Recommendation | Security benefit | Complexity / cost | Risk if deferred | L | I | P |
|---|---|---|---|---|---|---|
| Add `actor_user_id`, `membership_id`, `request_id` | Attributable history | Low (one migration, entity fields) | Anonymous history for authenticated actions | Yes | Yes | **Yes** |
| Add `actor_role` | Correct "who was what" after role changes | Trivial | History that cannot say the actor's role then | — | Yes | **Yes** |
| `NOT VALID` check for new rows | Forces attribution | Low | New anonymous events | — | Yes | **Yes** |
| Do not backfill or alter old rows | Preserves evidence | None | Falsified history | Yes | Yes | **Yes** |
| Tests: triggers still block UPDATE, DELETE, TRUNCATE after the migration; old rows unchanged (compare checksums before and after); the migration runs on a copy of a populated database | Proof of non-destruction | Medium | Silent damage | Yes | Yes | **Yes** |

---

## 9. Point 7 — Implementation sequence and what each decision blocks

### 9.1 Decisions

| Decision | Blocks authentication development? | Blocks platform-admin development? | Blocks only real-customer onboarding? |
|---|---|---|---|
| Q-1 email channel/provider | No (use the sink) | No (sink) | **Yes** |
| Q-2 admin-assisted recovery policy | No | No | **Yes** |
| Q-7 who the platform admins are; who holds the database-owner credential | No | Only the first real admin | **Yes** |
| RD-1 platform deployable model | No | **Yes** (decides modules) | No |
| RD-2 platform-admin MFA | No | **Yes** (the console login) | Yes |
| RD-5/RD-6 BCrypt cost and session values | No (defaults 12, 30 min, 18 h) | No | Tune before production |
| RD-8, Q-3, Q-5 trial start, grace, extension | No | **Yes** (lifecycle screens and rules) | Yes |
| RD-9 audit columns including `actor_role` | No | No | Blocks the audit migration |
| `DEFAULT` firm procedure (section 7) | No | No | **Yes** (before the first production migration set) |
| Q-4 retention and privacy | No | No | **Yes** (before any real data) |
| Q-6, Q-12 starter data, firm naming | No | No | Blocks firm creation (not the first slice) |
| Q-9 employee restrictions | No (same permissions initially) | No | No |
| Verification of the `0.0.0.0` listeners | **Yes in practice**: it should be done first | No | **Yes** |

### 9.2 Smallest secure vertical slice [REC]

Order, each step ending with all suites green:

1. **Exposure check and loopback binding** (blueprint 12.3, phase 1). Nothing that follows protects data that is reachable from the network without a password.
2. **Identity core**: `V6` (`app_user`, `auth_event`, throttle), the password service with the byte rules, the email port with the sink.
3. **Platform database boundary, database only** (no application): `platform` schema, `mandi_platform` role, hardening statements, tests DB-1 to DB-8. No module split yet, because no platform code exists to separate. **The module split and the separate deployable must exist before the first platform feature, not before the first business feature.**
4. **Authentication**: Spring Security chain, Spring Session JDBC, CSRF, the idle/absolute rules, the passive status filter (10.3), the firm guard header, `AuthenticatedCurrentFirm` failing closed.
5. **Farmers master only** behind the central policy, with `firm_member` and **the real two-firm isolation test** (an owner in two firms; an employee in one; revocation mid-session).

| Recommendation | Security benefit | Complexity / cost | Risk if deferred | L | I | P |
|---|---|---|---|---|---|---|
| Steps 1 to 5 as the first release of security work | A working, tested boundary on one resource before the rest is converted | About 10 to 12 working days by the blueprint's rough sizes **[ASSUMED]** | Converting all routes before the pattern is proven | Yes | Yes | Yes |
| Exposure verification first | Closes the largest present risk | 0.5 to 1 day | Data reachable without login | Yes | **Yes** | **Yes** |
| Module split before the first platform feature | Dependency direction enforced at compile time | Medium | Retrofit cost | — | — | Yes |

---

## 10. Point 8 — Technical checks

### 10.1 What was run

| Experiment | What | Result file location (scratch) |
|---|---|---|
| E1 | BCrypt with Spring Security 7.1.1 on JDK 21 | scratch `bcrypt/` |
| E2 | PostgreSQL boundary tests (4.3) | scratch `pgtest/out.txt` |
| E3 | Next.js build with a backend behind its `rewrites` (the repository's own `.next-e2e` build) | scratch `miniapp/proxy.txt` |
| E4 | A throwaway Spring Boot 4.1.1 app with Spring Security 7.1.1 and Spring Session JDBC, using **shortened** limits (idle 20 s, absolute 45 s) | scratch `miniapp/direct*.txt` |

**Limits of the experiments:** plain HTTP only (no TLS), curl rather than a browser, in-memory users and memberships (so **AV-4 is not measured**), H2 for sessions, and shortened clocks. The production values (1800 s, 64800 s) were **not** run to completion; the checks verify the **mechanism**, and a production test must also assert the configured numbers.

### 10.2 BCrypt byte-length handling [EXP]

| Check | Result |
|---|---|
| `encode` with 72 ASCII bytes; 24 Devanagari characters (72 bytes) | accepted |
| `encode` with 73 ASCII bytes; 25 Devanagari characters (75 bytes) | **throws `IllegalArgumentException: password cannot be more than 72 bytes`** |
| **`matches(candidate, hash)` where the candidate is 73 characters and the hash is of the 72-character prefix** | **returns `true`: silent truncation on the match path** |
| Cost 10 / 12 / 14 per hash on this development machine (mean of 3 runs) | about 97 ms / 374 ms / 1.5 s |
| Default of `DelegatingPasswordEncoder` | prefix `{bcrypt}`, cost 10 (so the blueprint's cost 12 must be set explicitly) |

**What this means [REC]:**
* The blueprint's "72-byte limit enforced, no silent truncation" is right but **incomplete**: it must be enforced where the password is **set** *and* where it is **verified**. A longer attempted password must be rejected (generic failure, without hashing it) — otherwise anyone who knows the first 72 bytes of a longer password is accepted.
* Count **UTF-8 bytes**, not characters: Hindi passwords reach the limit at 24 characters. Show the byte rule in Hindi and English.
* Cost 12 costs about 0.4 s of CPU per attempt on this machine (production hardware unmeasured **[ASSUMED]**), so **login is a CPU-exhaustion vector**: apply the throttle **before** hashing, and bound concurrent hashing.
* Pre-hashing (to lift the 72-byte limit) is not needed once the limit is enforced; do not add it.
* Use `{bcrypt}` delegating storage with an `upgradeEncoding` check so the cost can be raised later.

| Recommendation | Security benefit | Complexity / cost | Risk if deferred | L | I | P |
|---|---|---|---|---|---|---|
| Enforce 72 bytes on set **and** verify | Prevents truncation acceptance | Trivial | False accept of a different password | **Yes** | Yes | **Yes** |
| Cost 12 set explicitly; measure on production hardware | Resistance to offline guessing | Low | Cost 10 by default | Yes | Yes | **Yes** |
| Throttle before hashing; bounded hashing concurrency | Prevents CPU denial of service | Low-medium | Login-based denial of service | — | Yes | **Yes** |

### 10.3 Idle and absolute session limits [EXP]

| Check | Result |
|---|---|
| Idle timeout (20 s test value): silence of 24 s, then a request | rejected `401`; session row gone |
| Activity extends idle: request at 16 s then 28 s then 36 s | accepted (without activity it would have ended at 20 s) |
| Absolute limit (45 s test value) with continuous activity | request at 46 s answered **`401 ABSOLUTE_SESSION_LIMIT`** and the session invalidated; reusing the cookie → `401` |
| Logout, then reuse of the old cookie | `401` |
| Absolute limit implementation | A filter comparing `now - authAt` stored as a **session attribute** set at login. Spring Session itself has **no** absolute limit |

**Passive status endpoint (AV-3) — the finding that changes the blueprint [EXP].** The blueprint says the expiry countdown must not keep a session alive. In three consecutive implementations in the same application, repeated status requests **kept the session alive past its idle limit**:
1. a controller that read the session through `SessionRepository.findById` (with a stateless security chain, no security context) — **extended**;
2. the same with anonymous authentication disabled — **extended**;
3. the same reading the session row with plain SQL — **extended**.

A fourth implementation worked: a **servlet filter registered with the highest precedence (before the session filter) that answers the request itself from a read-only SQL query on the session table** — idle seconds fell 20, 14, 7, 1 and the status was `expired` at 24 s, with no extension, while a normal request (`/api/me`) did extend. The exact cause inside the request path was **not isolated** (a call to `getSession(false)` somewhere in Spring MVC or Security is the likely mechanism; **[ASSUMED]**). Consequences:
* **Do not implement the status endpoint as an ordinary controller.** Either use a filter placed before the session filter, or (simpler) **do not poll at all**: return `idleExpiresAt` and `absoluteExpiresAt` in the login response and in an ordinary response header on each user-initiated request, and let the client count down (blueprint RD-6 already says "no heartbeat polling").
* Use the explicit **`authAt`** attribute for the absolute limit, **not** the session's creation time: the session id is changed at login, and a session created before login would otherwise start the 18 hours early **[ASSUMED]** (not tested with a pre-login session).
* Use **database** time for session and trial arithmetic (AV-9); the experiment mixed clocks, which is fine on one machine but not guaranteed.

| Recommendation | Security benefit | Complexity / cost | Risk if deferred | L | I | P |
|---|---|---|---|---|---|---|
| Absolute 18 h by an `authAt` filter, idle 30 min by `maxInactiveInterval` | Both limits enforced server-side | Low | Sessions that outlive policy | Yes | **Yes** | **Yes** |
| No ordinary status endpoint; expiry values in responses (or a pre-session filter) | Countdown cannot extend a session | Low | A polling client keeps a session alive forever | Yes | Yes | **Yes** |
| A production-value test asserting the configured numbers (1800 s, 64800 s) and a fast-clock test of the mechanism | Catches unit mistakes | Low | A wrong unit (seconds vs minutes) ships | Yes | Yes | **Yes** |

### 10.4 CSRF, cookies and the proxy [EXP]

| Check | Direct to the backend | Through the Next.js proxy |
|---|---|---|
| Session cookie attributes | `__Host-mandi_session; Path=/; Secure; HttpOnly; SameSite=Lax` | **identical** (`Set-Cookie` passes through unmodified) |
| CSRF cookie | `XSRF-TOKEN=…; Path=/` — **no `Secure`, no `SameSite`**, readable by script (by design for double-submit) | same |
| State-changing request without the CSRF header | rejected (`401` with the entry point used) | same |
| Wrong CSRF header | rejected | same |
| Foreign `Origin` header (`http://evil.example`, `null`) | `403 ORIGIN_NOT_ALLOWED` by the allow-list filter | same (with a proxy-origin allow-list) |
| No `Origin` header at all | accepted **if** the CSRF token is valid | same |
| Session fixation: pre-login session id, then login | session id **changed**; old id then `401` | same |
| CSRF token after login | **not rotated** (no new `Set-Cookie`) in my hand-written login endpoint | same |
| What reaches the backend through the proxy | n/a | `Host` rewritten to `localhost:8081`; `Origin` unchanged (the browser's); `Cookie`, `X-XSRF-TOKEN` and custom headers preserved; `X-Forwarded-Host` set; **no `X-Forwarded-Proto`, no `X-Forwarded-For`**; `remoteAddr` is the proxy; `isSecure() = false` |

**What this means [REC]:**
* **Session cookie:** keep `__Host-` + `Secure` + `HttpOnly` + `SameSite=Lax` + `Path=/` and no `Domain`. Verified through the proxy at the header level; **real browser acceptance over HTTPS is not tested here**.
* **`Secure` on the CSRF cookie** was **absent** because the backend sees plain HTTP behind the proxy (no `X-Forwarded-Proto`). In production the backend must treat the proxy as trusted (`server.forward-headers-strategy` or equivalent) **and** the proxy must send the scheme and client address; otherwise the CSRF cookie is not `Secure` and **per-IP throttling would key on the proxy's address** (every user the same). The Next.js rewrite proxy does **not** set them (source and experiment agree). **Decision for the deployment [DECISION]:** either run a TLS-terminating reverse proxy in front of both applications that sets them and does the routing (recommended), or configure the backend explicitly. Not decided in the blueprint's deployment-neutral baseline.
* **CSRF `401` vs `403`:** an unauthenticated state-changing request without a valid token produced `401` (the entry point), not `403`. The frontend must read the **error body code**, not the status alone, to tell "not signed in" from "missing token".
* **Login must go through Spring's authentication strategies**, so the CSRF token and session id both rotate; my hand-written endpoint rotated the session id (by `changeSessionId`) but **not** the CSRF token. Use `SessionAuthenticationStrategy` / the standard login filter or call the strategies explicitly **[REC, not verified]**.
* **Origin allow-list** must contain the **public frontend origin** (what the browser sends), not the backend address. The backend sees `Host: localhost:8081` and a different `Origin`.
* **SameSite is a defence in depth, not the CSRF control** (Lax allows top-level GET navigation): keep the token and the Origin check.

| Recommendation | Security benefit | Complexity / cost | Risk if deferred | L | I | P |
|---|---|---|---|---|---|---|
| CSRF token on every unsafe request plus the Origin allow-list | Blocks cross-site writes | Low | Cross-site request forgery | Yes | **Yes** | **Yes** |
| Rotate the CSRF token and session id at login and at MFA completion | Fixation and token reuse | Low (use Spring's strategies) | Reuse of a pre-login token | Yes | Yes | **Yes** |
| TLS-terminating proxy that sets scheme and client address; backend trusts it explicitly | `Secure` on cookies, correct throttle keys | Medium (deployment) | Missing `Secure`; one shared throttle bucket | — | — | **Yes** |
| Real-browser check over HTTPS (cookie prefix acceptance, SameSite) | Confirms the headers in practice | Low | A headers-only assumption | — | opt | **Yes** |

### 10.5 Stale firm selection [EXP]

Setup: an owner with memberships in firms 1 and 2 (no auto-selection when there are two), and an employee with one firm (auto-selected). The firm check header is `X-Mandi-Firm-Check` (RD-11).

| Request | Result |
|---|---|
| Business read with **no firm selected** | `409 FIRM_SELECTION_REQUIRED` |
| Selecting a firm the owner is a member of (2) / **not** a member of (3) | `200` / `403 NO_ACTIVE_MEMBERSHIP` |
| Business read with the header **missing** | `409 FIRM_CHECK_REQUIRED` |
| Business read **and write** with header = 1 while the session's active firm is 2 (a stale tab) | `409 FIRM_MISMATCH` for both |
| Business read with header = 2 | `200`, data from firm 2 |
| **Membership removed in firm 2 while the session is live**, then read and write with header = 2 | `403 NO_ACTIVE_MEMBERSHIP` immediately on the next request |
| Same results through the Next.js proxy | identical |

**Notes:**
* The client-supplied header is only a **cross-check** against the server-side active firm; the **server** decides from the session and the membership. A forged header cannot select another firm (header ≠ active → `409`; header = active but no membership → `403`).
* The membership check ran on every request, so revocation took effect at once; its **cost** in production (a database read, AV-4) is unmeasured.
* The blueprint's rule that the browser tab never decides the firm holds; the frontend must treat `409` as "reload the firm context", not as an error to retry.

| Recommendation | Security benefit | Complexity / cost | Risk if deferred | L | I | P |
|---|---|---|---|---|---|---|
| Firm guard header on every business request, `409` on mismatch | A stale tab cannot write into another firm | Low | Silent cross-firm writes after a firm switch in another tab | Yes | **Yes** | **Yes** |
| Per-request membership and account check | Immediate revocation | Two indexed reads; to be measured | Revocation lags until session end | Yes | Yes | **Yes** |
| A test that a revoked membership returns `403` on the **same live session** | Proves immediate revocation | Low | Regression | Yes | Yes | **Yes** |

---

## 11. Corrections to the blueprint implied by this review

| Blueprint section | Change recommended |
|---|---|
| 3.3 contract surface, `platform.owner_user_summary` | Owner users only; add the void `queue_owner_credential_email` function; drop "revoke owner sessions" from the suspension path |
| 3.3 layer 5 | State the hardening statements (`REVOKE … FROM PUBLIC` for `public` schema usage and function execution, default privileges) and the catalog-generated tests DB-1 to DB-13 |
| 6.2 | The 72-byte rule applies to **verification** too (silent truncation on `matches`) |
| 6.4 | Passive status must not be an ordinary endpoint; use `authAt`, not creation time |
| 8.3 / RD-11 | `409` codes `FIRM_SELECTION_REQUIRED`, `FIRM_CHECK_REQUIRED`, `FIRM_MISMATCH` verified; `403` for a revoked membership |
| 9.2 / RD-9 | Add `actor_role`; `NOT VALID` attribution check for new rows |
| 12 | The deployment decision needs a TLS-terminating proxy that sets scheme and client address, because the Next.js proxy does not |
| 13.2 | `DEFAULT` firm: database-owner runbook with assertions (section 7) |
| 17 | The items in section 9.1 of this memo refine which decisions block which work |

These are **not applied** (the blueprint was not edited in this task).

---

## 12. Open decisions for the product owner

1. Email channel and whether real onboarding waits for it (Q-1, Q-2; recommended: wait).
2. Who the platform administrators are, how many, and that none holds the database-owner credential (Q-7).
3. Platform deployment: confirm the on-demand command-line-first deployable (section 3.3).
4. Trial extension, one trial per owner or per email, grace and read-only behaviour, what an expired or suspended owner can still do (Q-3, Q-5, section 6).
5. Retention and privacy (Q-4).
6. Whether `DEFAULT` holds any real data (decides branch A or B in section 7).
7. TLS-terminating proxy as the deployment baseline (section 10.4).
8. Whether owner MFA is available at the first release (optional) or follows within it.

*No repository file other than this memo was created or changed, nothing was committed, and the development database was not modified.*
