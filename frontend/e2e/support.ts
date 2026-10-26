import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { expect, type APIRequestContext, type BrowserContext, type Locator } from "@playwright/test";

/** Synthetic accounts of the disposable stack (scripts/e2e/stack.js). Written by global-setup, read by the specs. */
export interface Fixtures {
  password: string;
  api: string;
  container: string;
  db: string;
  ownerA: { email: string; firmId: number; firmName: string };
  ownerB: { email: string; firms: { id: number; name: string }[] };
  ownerC: { email: string; firmId: number; firmName: string; farmerId: number };
}

export const FIXTURES_FILE = process.env.E2E_FIXTURES ?? path.join(__dirname, ".fixtures.json");

export function fixtures(): Fixtures {
  if (!fs.existsSync(FIXTURES_FILE)) throw new Error("e2e fixtures missing: run the browser tests through global-setup (see docs/RUNNING.md)");
  return JSON.parse(fs.readFileSync(FIXTURES_FILE, "utf8")) as Fixtures;
}

/** Runs SQL as the database OWNER of the disposable container: used to simulate what happens elsewhere (an administrator
 * suspending an account, a session timing out) and to verify what was stored. Never the development database. */
export function sql(query: string): string {
  const f = fixtures();
  const r = spawnSync("docker", ["exec", "-i", f.container, "psql", "-U", "mandi", "-d", f.db, "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1"], { input: query, encoding: "utf8" });
  if (r.status !== 0) throw new Error(`sql failed: ${r.stderr}`);
  return r.stdout.trim();
}

/** Chooses a master record in a search-as-you-type picker by typing part of its name and clicking the match. */
export async function pickMaster(input: Locator, text: string): Promise<void> {
  await input.click();
  await input.fill(text);
  const option = input.page().getByTestId("picker-option").filter({ hasText: text }).first();
  await expect(option).toBeVisible();
  await option.click();
  await expect(input).not.toHaveAttribute("data-selected-id", "");   // a record was really chosen
}

function cookieValue(cookies: { name: string; value: string }[], name: string): string | undefined {
  return cookies.find((c) => c.name === name)?.value;
}

/** Signs in through the real endpoints with the browser context's own cookie jar (no UI), like a person would after the form. */
export async function apiSignIn(context: BrowserContext, email: string, password: string): Promise<void> {
  const csrf = await context.request.get("/api/v1/auth/csrf");
  expect(csrf.ok()).toBeTruthy();
  const token = ((await csrf.json()) as { token: string }).token;
  const login = await context.request.post("/api/v1/auth/login", { data: { email, password }, headers: { "X-XSRF-TOKEN": token } });
  expect(login.status(), await login.text()).toBe(200);
}

/** A request client for the specs that talk to the API directly: same session as the browser, with the CSRF token and the
 * firm check header that the application itself sends. It is NOT a bypass: the server applies every check to it. */
export function authedRequest(context: BrowserContext, firmId: number): APIRequestContext {
  const call = async (method: string, url: string, options: Record<string, unknown> = {}) => {
    const cookies = await context.cookies();
    const csrf = cookieValue(cookies, "XSRF-TOKEN") ?? "";
    const headers = { ...((options.headers as Record<string, string>) ?? {}), "X-XSRF-TOKEN": csrf, "X-Mandi-Firm-Check": String(firmId) };
    return context.request.fetch(url, { ...options, method, headers });
  };
  return {
    get: (url: string, o?: Record<string, unknown>) => call("GET", url, o),
    post: (url: string, o?: Record<string, unknown>) => call("POST", url, o),
    put: (url: string, o?: Record<string, unknown>) => call("PUT", url, o),
    patch: (url: string, o?: Record<string, unknown>) => call("PATCH", url, o),
    delete: (url: string, o?: Record<string, unknown>) => call("DELETE", url, o),
  } as unknown as APIRequestContext;
}

// ---- the billing desk: one row per lot, the purchasers of the selected lot below it, calculated amounts ---------------------------

import type { Page } from "@playwright/test";

/** The row of lot number n (0-based). */
export const lotRow = (page: Page, n: number): Locator => page.getByTestId("lot-row").nth(n);

/** The lot whose purchasers are shown (the one being worked in). */
export const currentLotRow = (page: Page): Locator => page.locator("[data-testid=lot-row][aria-current=true]");

/** Makes lot number n (0-based) the lot being edited. Everything typed in other lots stays where it is. */
export async function chooseLot(page: Page, n: number): Promise<void> {
  await page.getByTestId("lot-tab").nth(n).click();
  await expect(page.getByTestId("lot-detail")).toHaveAttribute("data-lot-index", String(n));
}

/** Types the farmer-side fields of the lot being edited. Its value is calculated by the page (weight x rate), never typed. */
export async function fillLot(page: Page, v: { item: string; pieces?: string; weight?: string; rate?: string; marka?: string }): Promise<void> {
  const row = currentLotRow(page);
  await row.getByTestId("lot-item").selectOption({ label: v.item });
  if (v.pieces !== undefined) await row.getByTestId("lot-pieces").fill(v.pieces);
  if (v.weight !== undefined) await row.getByTestId("lot-weight").fill(v.weight);
  if (v.rate !== undefined) await row.getByTestId("lot-rate").fill(v.rate);
  if (v.marka !== undefined) await row.getByTestId("lot-marka").fill(v.marka);
}

/** Adds a purchaser row to the lot being edited and types what that purchaser bought (weight and rate; value and commission are calculated). */
export async function addPurchaser(page: Page, v: { buyer: string; haste?: string; pieces?: string; weight?: string; rate?: string }): Promise<void> {
  await page.getByTestId("add-purchaser").click();
  const row = page.getByTestId("purchaser-row").last();
  await pickMaster(row.getByTestId("purchaser-buyer"), v.buyer);
  if (v.haste !== undefined) await row.getByTestId("purchaser-haste").fill(v.haste);
  if (v.pieces !== undefined) await row.getByTestId("purchaser-pieces").fill(v.pieces);
  if (v.weight !== undefined) await row.getByTestId("purchaser-weight").fill(v.weight);
  if (v.rate !== undefined) await row.getByTestId("purchaser-rate").fill(v.rate);
}

/** Adds an expense row. A head with a predefined rate has no amount to type; any other head takes the amount. */
export async function addExpense(page: Page, headLabel: string, amount?: string): Promise<void> {
  await page.getByTestId("add-expense").click();
  // the Motor Bhada row of the bill details is automatic and has no head to pick: the new row is the last of the others
  const row = page.locator("[data-testid=expense-row]:not([data-auto])").last();
  await row.getByTestId("expense-head").selectOption({ label: headLabel });
  if (amount !== undefined) await row.getByTestId("expense-amount").fill(amount);
}
