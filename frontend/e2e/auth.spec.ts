import { WEB_URL } from "./ports";
import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import { apiSignIn, authedRequest, fixtures, sql } from "./support";

// Sign-in, firm selection, employees and every refusal, in a real browser against the real backend and a disposable
// PostgreSQL database. All accounts are synthetic (global-setup). Nothing here uses the shortcut of fixtures.ts.

const f = fixtures();
const SFX = Date.now().toString(36);

async function uiSignIn(page: Page, email: string, password = f.password) {
  await page.goto("/");
  await page.getByTestId("login-email").fill(email);
  await page.getByTestId("login-password").fill(password);
  await page.getByTestId("login-submit").click();
  await expect(page.getByTestId("login")).toHaveCount(0);      // the sign-in finished (a page change now would cancel it)
}

async function selectFirmByName(page: Page, name: string) {
  await page.getByTestId("firm-option").filter({ hasText: name }).click();
  await expect(page.getByTestId("session-firm")).toContainText(name);
}

test.describe("sign in and out", () => {
  test("an owner signs in, works, signs out, and the server session really ends", async ({ page, context }) => {
    await page.goto("/");
    await expect(page.getByTestId("login")).toBeVisible();
    await expect(page.getByTestId("main-menu")).toHaveCount(0);        // no menu, no content before sign-in
    await expect(page.getByTestId("session-bar")).toHaveCount(0);

    await uiSignIn(page, f.ownerA.email);
    await expect(page.getByTestId("session-firm")).toContainText(f.ownerA.firmName);   // one firm: selected without asking
    await expect(page.getByTestId("main-menu")).toBeVisible();
    await expect(page.getByTestId("login")).toHaveCount(0);

    // the session cookie cannot be read by the page; nothing about the sign-in is kept in browser storage
    const cookies = await context.cookies();
    const session = cookies.find((c) => c.name === "mandi_session");
    const sessionId = Buffer.from(session?.value ?? "", "base64").toString("utf8");
    expect(sessionId).toMatch(/^[0-9a-f-]{36}$/);
    expect(session?.httpOnly).toBe(true);
    expect(session?.sameSite).toBe("Lax");
    const storage = await page.evaluate(() => ({ local: Object.keys(window.localStorage), session: Object.keys(window.sessionStorage), cookie: document.cookie }));
    expect(storage.local.filter((k) => k !== "mandi.lang")).toEqual([]);
    expect(storage.session).toEqual([]);
    expect(storage.cookie).not.toContain("mandi_session");
    expect(JSON.stringify(storage)).not.toContain(f.password);

    // reload: the page asks the server and finds the session again
    await page.reload();
    await expect(page.getByTestId("session-firm")).toContainText(f.ownerA.firmName);

    await page.getByTestId("sign-out").click();
    await expect(page.getByTestId("login")).toBeVisible();
    await page.reload();
    await expect(page.getByTestId("login")).toBeVisible();
    const direct = await page.request.get("/api/v1/masters/farmers", { headers: { "X-Mandi-Firm-Check": String(f.ownerA.firmId) } });
    expect(direct.status()).toBe(401);
    expect(sql(`select count(*) from spring_session where session_id = '${sessionId}'`)).toBe("0");          // THIS session is gone from the server
  });

  test("wrong credentials give one generic message and no session", async ({ page, context }) => {
    await page.goto("/");
    await page.getByTestId("login-submit").click();
    await expect(page.getByTestId("login-error")).toContainText("Enter your email");
    await page.getByTestId("login-email").fill(f.ownerA.email);
    await page.getByTestId("login-submit").click();
    await expect(page.getByTestId("login-error")).toContainText("Enter your password");

    await page.getByTestId("login-password").fill("not-the-password-123");
    await page.getByTestId("login-submit").click();
    const wrongPassword = await page.getByTestId("login-error").innerText();
    await page.getByTestId("login-email").fill(`nobody-${SFX}@example.test`);
    await page.getByTestId("login-password").fill("not-the-password-123");
    await page.getByTestId("login-submit").click();
    await expect(page.getByTestId("login-error")).toHaveText(wrongPassword);          // unknown email: the same words
    expect(wrongPassword).toContain("not correct");
    await expect(page.getByTestId("login-password")).toHaveValue("");               // the password is cleared after a failure
    expect((await context.cookies()).some((c) => c.name === "mandi_session")).toBe(false);
  });

  test("there is no self-registration and no way to give yourself a role", async ({ page, request }) => {
    await page.goto("/");
    await expect(page.getByText("created by invitation only")).toBeVisible();
    await expect(page.getByRole("link", { name: /register|sign up|create account/i })).toHaveCount(0);
    // direct attempts: nothing answers, nothing is created
    const before = sql("select count(*) from app_user");
    const csrf = ((await (await request.get(`${f.api}/api/v1/auth/csrf`)).json()) as { token: string }).token;
    for (const url of ["/api/v1/auth/register", "/api/v1/auth/signup", "/api/v1/users", "/api/v1/register"]) {
      const r = await request.post(`${f.api}${url}`, { data: { email: `mallory-${SFX}@example.test`, password: "mallory-password-1", role: "OWNER" }, headers: { "X-XSRF-TOKEN": csrf } });
      expect(r.status(), url).toBe(401);
    }
    expect(sql("select count(*) from app_user")).toBe(before);
    // the invitation page needs a real token
    await page.goto("/accept-invitation");
    await expect(page.getByTestId("accept-no-token")).toBeVisible();
    await page.goto(`/accept-invitation#token=${"A".repeat(43)}`);
    await page.getByTestId("accept-password").fill("a-long-enough-password");
    await page.getByTestId("accept-confirm").fill("a-long-enough-password");
    await page.getByTestId("accept-submit").click();
    await expect(page.getByTestId("accept-error")).toContainText("not valid any more");
    expect(sql("select count(*) from app_user")).toBe(before);
  });
});

test.describe("CSRF and expiry", () => {
  test("a state-changing request without the CSRF token is refused and nothing changes", async ({ page }) => {
    await uiSignIn(page, f.ownerA.email);
    await expect(page.getByTestId("session-firm")).toBeVisible();
    const status = await page.evaluate(async () => {
      const r = await fetch("/api/v1/auth/logout", { method: "POST", credentials: "same-origin" });
      return { code: r.status, body: await r.text() };
    });
    expect(status.code).toBe(403);
    expect(status.body).toContain("CSRF_REJECTED");
    const login = await page.evaluate(async () => {
      const r = await fetch("/api/v1/auth/login", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email: "x@example.test", password: "x" }) });
      return r.status;
    });
    expect(login).toBe(403);
    await page.reload();
    await expect(page.getByTestId("session-firm")).toBeVisible();      // still signed in: the refused logout did nothing
  });

  test("an expired session keeps what is on the screen under a sign-in dialog", async ({ page }) => {
    await uiSignIn(page, f.ownerA.email);
    await page.goto("/masters/villages");
    const typed = `Typed before expiry ${SFX}`;
    await page.getByTestId("master-name").fill(typed);
    sql(`delete from spring_session where principal_name = 'u:' || (select id from app_user where email = '${f.ownerA.email}')`);   // the server session ends
    await page.getByTestId("master-save").click();
    await expect(page.getByTestId("relogin-dialog")).toBeVisible();
    await expect(page.getByTestId("login-expired")).toBeVisible();
    await expect(page.getByTestId("master-name")).toHaveValue(typed);             // not lost
    await page.getByTestId("login-email").fill(f.ownerA.email);
    await page.getByTestId("login-password").fill(f.password);
    await page.getByTestId("login-submit").click();
    await expect(page.getByTestId("relogin-dialog")).toHaveCount(0);
    await expect(page.getByTestId("master-name")).toHaveValue(typed);
    await page.getByTestId("master-save").click();
    await expect(page.getByTestId("master-saved")).toBeVisible();                 // and can now be saved
    expect(sql(`select count(*) from village where name = '${typed}' and firm_id = ${f.ownerA.firmId}`)).toBe("1");
  });
});

test.describe("firms", () => {
  test("an owner with several firms chooses one and sees only its data; switching changes everything", async ({ page }) => {
    const [b1, b2] = f.ownerB.firms;
    await uiSignIn(page, f.ownerB.email);
    await expect(page.getByTestId("firm-picker")).toBeVisible();
    await expect(page.getByTestId("firm-option")).toHaveCount(2);
    await expect(page.getByTestId("firm-option").filter({ hasText: f.ownerA.firmName })).toHaveCount(0);   // never another owner's firm
    await selectFirmByName(page, b1.name);
    await page.goto("/masters/villages");
    await page.getByTestId("master-name").fill(`B1 only ${SFX}`);
    await page.getByTestId("master-save").click();
    await expect(page.getByTestId("master-saved")).toBeVisible();
    await page.getByTestId("master-search").fill(`B1 only ${SFX}`);
    await expect(page.getByTestId("master-row")).toHaveCount(1);

    await page.getByTestId("switch-firm").click();
    await selectFirmByName(page, b2.name);
    await page.goto("/masters/villages");
    await page.getByTestId("master-search").fill(`B1 only ${SFX}`);
    await expect(page.getByTestId("master-row")).toHaveCount(0);                   // firm two cannot see firm one's farmer
    expect(sql(`select firm_id from village where name = 'B1 only ${SFX}'`)).toBe(String(b1.id));
  });

  test("a stale window is stopped instead of writing into another firm", async ({ browser }) => {
    const [b1, b2] = f.ownerB.firms;
    const context = await browser.newContext({ baseURL: WEB_URL });
    const tab1 = await context.newPage();
    await uiSignIn(tab1, f.ownerB.email);
    await selectFirmByName(tab1, b1.name);
    await tab1.goto("/masters/villages");
    await expect(tab1.getByTestId("master-name")).toBeVisible();

    // another window of the same session switches to firm two
    const tab2 = await context.newPage();
    await tab2.goto("/");
    await expect(tab2.getByTestId("session-firm")).toContainText(b1.name);
    await tab2.getByTestId("switch-firm").click();
    await selectFirmByName(tab2, b2.name);

    // the first window still believes it is in firm one and tries to save
    const name = `Stale write ${SFX}`;
    await tab1.getByTestId("master-name").fill(name);
    await tab1.getByTestId("master-save").click();
    await expect(tab1.getByTestId("stale-firm")).toBeVisible();
    expect(sql(`select count(*) from village where name = '${name}'`)).toBe("0");             // written to neither firm
    await tab1.getByTestId("stale-adopt").click();
    await expect(tab1.getByTestId("session-firm")).toContainText(b2.name);
    await context.close();
  });

  test("another firm's records cannot be reached by id, header or selection", async ({ page, context }) => {
    await apiSignIn(context, f.ownerA.email, f.password);
    const api = authedRequest(context, f.ownerA.firmId);
    await page.goto("/");
    await expect(page.getByTestId("session-firm")).toContainText(f.ownerA.firmName);
    const before = sql(`select name || '/' || active from farmer where id = ${f.ownerC.farmerId}`);

    expect((await api.put(`/api/v1/masters/farmers/${f.ownerC.farmerId}`, { data: { name: "Hijacked" } })).status()).toBe(404);
    expect((await api.patch(`/api/v1/masters/farmers/${f.ownerC.farmerId}/active`, { data: { active: false } })).status()).toBe(404);
    const asOther = authedRequest(context, f.ownerC.firmId);
    const mismatch = await asOther.get("/api/v1/masters/farmers");
    expect(mismatch.status()).toBe(409);
    expect(await mismatch.text()).toContain("FIRM_MISMATCH");
    const select = await api.post("/api/v1/auth/active-firm", { data: { firmId: f.ownerC.firmId } });
    expect(select.status()).toBe(403);
    expect(await select.text()).toContain("NO_MEMBERSHIP");
    expect(sql(`select name || '/' || active from farmer where id = ${f.ownerC.farmerId}`)).toBe(before);
    const own = await (await api.get("/api/v1/masters/farmers")).json();
    expect(JSON.stringify(own)).not.toContain("C Farmer");
  });
});

test.describe.serial("employees", () => {
  const employee = `e2e-employee-${SFX}@example.test`;
  const employeePassword = "employee-Password-456";

  async function inviteLink(request: import("@playwright/test").APIRequestContext): Promise<string> {
    const mail = (await (await request.get(`${f.api}/api/v1/dev/mail`)).json()) as { to: string; link: string }[];
    const m = mail.find((x) => x.to === employee);
    if (!m) throw new Error("no invitation mail for the employee");
    return m.link;
  }

  test("the owner invites; the employee chooses a password, signs in and sees only that firm", async ({ page, browser, request }) => {
    await uiSignIn(page, f.ownerA.email);
    await page.goto("/team");
    await expect(page.getByTestId("team")).toBeVisible();
    await page.getByTestId("invite-email").fill(employee);
    await page.getByTestId("invite-submit").click();
    await expect(page.getByTestId("team-notice")).toBeVisible();
    await expect(page.getByTestId("pending-row")).toHaveCount(1);
    // the link never appears on the owner's screen
    expect(await page.content()).not.toContain("accept-invitation#token");

    const link = await inviteLink(request);
    const person = await browser.newContext({ baseURL: WEB_URL });
    const p = await person.newPage();
    await p.goto(link);
    await expect(p.getByTestId("accept")).toBeVisible();
    expect(p.url()).not.toContain("token=");                                    // the fragment is removed from the address bar
    await p.getByTestId("accept-name").fill("E2E Employee");
    await p.getByTestId("accept-password").fill("short");
    await p.getByTestId("accept-confirm").fill("short");
    await p.getByTestId("accept-submit").click();
    await expect(p.getByTestId("accept-error")).toContainText("at least 10");
    await p.getByTestId("accept-password").fill(employeePassword);
    await p.getByTestId("accept-confirm").fill(employeePassword + "x");
    await p.getByTestId("accept-submit").click();
    await expect(p.getByTestId("accept-error")).toContainText("not the same");
    await p.getByTestId("accept-password").fill(employeePassword);
    await p.getByTestId("accept-confirm").fill(employeePassword);
    await p.getByTestId("accept-submit").click();
    await expect(p.getByTestId("accept-done")).toBeVisible();
    expect(sql(`select count(*) from firm_invitation where email = '${employee}' and accepted_at is not null`)).toBe("1");

    await p.goto("/");
    await p.getByTestId("login-email").fill(employee);
    await p.getByTestId("login-password").fill(employeePassword);
    await p.getByTestId("login-submit").click();
    await expect(p.getByTestId("session-firm")).toContainText(f.ownerA.firmName);
    await expect(p.getByTestId("session-firm")).toContainText("Employee");
    await expect(p.getByTestId("nav-team")).toHaveCount(0);
    await p.goto("/team");
    await expect(p.getByTestId("team-owner-only")).toBeVisible();
    await p.goto("/masters/villages");
    await expect(p.getByTestId("master-name")).toBeVisible();

    // direct attempts by the employee: no authority over the team, firms or other firms
    const api = authedRequest(person, f.ownerA.firmId);
    const invite = await api.post("/api/v1/firm/invitations", { data: { email: `x-${SFX}@example.test` } });
    expect(invite.status()).toBe(403);
    expect(await invite.text()).toContain("PERMISSION_DENIED");
    const makeFirm = await api.post("/api/v1/auth/firms", { data: { name: "Employee Firm" } });
    expect(makeFirm.status()).toBe(403);
    expect(await makeFirm.text()).toContain("NOT_AN_OWNER");
    expect((await api.post("/api/v1/auth/active-firm", { data: { firmId: f.ownerB.firms[0].id } })).status()).toBe(403);
    expect(sql(`select count(*) from firm where name = 'Employee Firm'`)).toBe("0");
    await person.close();
  });

  test("when the owner removes the employee, the employee's open window stops at once; reactivation restores it", async ({ page, browser }) => {
    const person = await browser.newContext({ baseURL: WEB_URL });
    const p = await person.newPage();
    await uiSignIn(p, employee, employeePassword);
    await expect(p.getByTestId("session-firm")).toContainText(f.ownerA.firmName);
    await p.goto("/masters/villages");
    await expect(p.getByTestId("master-name")).toBeVisible();

    await uiSignIn(page, f.ownerA.email);
    await page.goto("/team");
    const row = page.getByTestId("member-row").filter({ hasText: employee });
    await row.getByTestId("member-toggle").click();
    await expect(row).toContainText("Inactive");

    await p.getByTestId("master-name").fill(`Revoked write ${SFX}`);
    await p.getByTestId("master-save").click();                                  // the next request of the live session
    await expect(p.getByTestId("no-access")).toBeVisible();
    await expect(p.getByTestId("no-access-reason")).toContainText("no longer have access");
    expect(sql(`select count(*) from village where name = 'Revoked write ${SFX}'`)).toBe("0");

    await row.getByTestId("member-toggle").click();
    await expect(row).toContainText("Active");
    await p.getByTestId("no-access-choose").click();
    await selectFirmByName(p, f.ownerA.firmName);
    await p.goto("/masters/villages");
    await expect(p.getByTestId("master-name")).toBeVisible();
    await person.close();
  });
});

test.describe.serial("suspended account", () => {
  test("a suspended owner account closes the firm on the next request and reinstatement reopens it", async ({ page }) => {
    await uiSignIn(page, f.ownerC.email);
    await expect(page.getByTestId("session-firm")).toContainText(f.ownerC.firmName);
    await page.goto("/masters/villages");
    await expect(page.getByTestId("master-name")).toBeVisible();
    const owner = `(select id from app_user where email = '${f.ownerC.email}')`;
    sql(`update platform.owner_account set status = 'SUSPENDED', suspended_reason_code = 'NON_PAYMENT' where owner_user_id = ${owner}`);
    try {
      await page.getByTestId("master-name").fill(`Suspended write ${SFX}`);
      await page.getByTestId("master-save").click();
      await expect(page.getByTestId("no-access")).toBeVisible();
      await expect(page.getByTestId("no-access-reason")).toContainText("suspended or has expired");
      expect(sql(`select count(*) from village where name = 'Suspended write ${SFX}'`)).toBe("0");
    } finally {
      sql(`update platform.owner_account set status = 'ACTIVE', suspended_reason_code = null where owner_user_id = ${owner}`);
    }
    await page.getByTestId("no-access-choose").click();
    await selectFirmByName(page, f.ownerC.firmName);
    await page.goto("/masters/villages");
    await expect(page.getByTestId("master-name")).toBeVisible();
  });
});

test.describe("provisioning of owners", () => {
  test("an invited owner chooses a password, signs in and creates a firm with the starter items", async ({ browser }) => {
    const email = `e2e-new-owner-${SFX}@example.test`;
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { provisionOwner } = require("../../scripts/dev/provision-owner.js") as { provisionOwner: (o: object) => { token: string } };
    const { token } = provisionOwner({ email, organisation: `E2E New ${SFX}`, container: f.container, db: f.db, admin: "mandi", baseUrl: WEB_URL });
    const context: BrowserContext = await browser.newContext({ baseURL: WEB_URL });
    const page = await context.newPage();
    await page.goto(`/accept-invitation#token=${token}`);
    await page.getByTestId("accept-name").fill("New Owner");
    await page.getByTestId("accept-password").fill(f.password);
    await page.getByTestId("accept-confirm").fill(f.password);
    await page.getByTestId("accept-submit").click();
    await expect(page.getByTestId("accept-done")).toBeVisible();
    await page.goto("/");
    await page.getByTestId("login-email").fill(email);
    await page.getByTestId("login-password").fill(f.password);
    await page.getByTestId("login-submit").click();
    await expect(page.getByTestId("firm-picker")).toBeVisible();                  // an owner without a firm yet
    await expect(page.getByTestId("firm-none")).toBeVisible();
    await page.getByTestId("firm-create").locator("summary").click();
    await page.getByTestId("firm-name").fill(`New Owner Firm ${SFX}`);
    await page.getByTestId("firm-create-submit").click();
    await expect(page.getByTestId("session-firm")).toContainText(`New Owner Firm ${SFX}`);
    expect(sql(`select string_agg(name, ',' order by name) from item where firm_id = (select id from firm where name = 'New Owner Firm ${SFX}')`)).toBe("Garlic,Onion,Potato");
    expect(sql(`select trial_ends_at - trial_started_at from platform.owner_account where owner_user_id = (select id from app_user where email = '${email}')`)).toContain("14 days");
    // the same link cannot be used twice
    await page.goto(`/accept-invitation#token=${token}`);
    await page.getByTestId("accept-password").fill(f.password);
    await page.getByTestId("accept-confirm").fill(f.password);
    await page.getByTestId("accept-submit").click();
    await expect(page.getByTestId("accept-error")).toContainText("not valid any more");
    await context.close();
  });
});
