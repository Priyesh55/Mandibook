import { WEB_URL } from "./ports";
import { request as pwRequest, type APIRequestContext } from "@playwright/test";
import fs from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import { FIXTURES_FILE, type Fixtures } from "./support";

const { provisionOwner } = createRequire(__filename)(path.join(__dirname, "..", "..", "scripts", "dev", "provision-owner.js")) as {
  provisionOwner: (o: { email: string; organisation: string; status?: string; container: string; db: string; admin: string; baseUrl: string }) => { token: string };
};

const API = process.env.E2E_API ?? "http://127.0.0.1:8091";
const CONTAINER = process.env.E2E_PG_CONTAINER ?? "mandi-e2e-pg";
const DB = "e2e";
const PASSWORD = "e2e-Password-123";
const SFX = Date.now().toString(36);

async function client(): Promise<APIRequestContext> {
  return pwRequest.newContext({ baseURL: API });
}

async function csrf(c: APIRequestContext): Promise<string> {
  const r = await c.get("/api/v1/auth/csrf");
  return ((await r.json()) as { token: string }).token;
}

async function post(c: APIRequestContext, url: string, data: object, firm?: number) {
  const headers: Record<string, string> = { "X-XSRF-TOKEN": await csrf(c) };
  // after a login the token cookie was replaced: always read the current one
  const cookies = (await c.storageState()).cookies;
  const current = cookies.find((k) => k.name === "XSRF-TOKEN")?.value;
  if (current) headers["X-XSRF-TOKEN"] = current;
  if (firm !== undefined) headers["X-Mandi-Firm-Check"] = String(firm);
  const r = await c.post(url, { data, headers });
  if (!r.ok()) throw new Error(`${url} -> ${r.status()} ${await r.text()}`);
  return r.json().catch(() => ({}));
}

/** The whole real path: platform-side invitation (as the database owner), the owner accepts and chooses a password, signs in. */
async function newOwner(tag: string): Promise<{ email: string; session: APIRequestContext }> {
  const email = `e2e-${tag}-${SFX}@example.test`;
  const { token } = provisionOwner({ email, organisation: `E2E ${tag}`, container: CONTAINER, db: DB, admin: "mandi", baseUrl: WEB_URL });
  const anon = await client();
  await post(anon, "/api/v1/auth/invitations/accept", { token, password: PASSWORD, displayName: `Owner ${tag}` });
  await anon.dispose();
  // the owner account is on TRIAL (14 days from acceptance); sign in
  const session = await client();
  await post(session, "/api/v1/auth/login", { email, password: PASSWORD });
  return { email, session };
}

async function newFirm(session: APIRequestContext, name: string): Promise<number> {
  const made = (await post(session, "/api/v1/auth/firms", { name, nameHi: null })) as { id: number };
  return made.id;
}

export default async function globalSetup() {
  const a = await newOwner("owner-a");
  const firmAName = `E2E Firm A ${SFX}`;
  const firmA = await newFirm(a.session, firmAName);

  // the synthetic masters of the existing specs (the demo seed belongs to the DEFAULT firm, which no owner has)
  await post(a.session, "/api/v1/auth/active-firm", { firmId: firmA });
  const seed: [string, string, string][] = [
    ["villages", "Demo Village A", "डेमो गाँव ए"], ["villages", "Demo Village B", "डेमो गाँव बी"],
    ["farmers", "Demo Farmer 1", "डेमो किसान 1"], ["farmers", "Demo Farmer 2", "डेमो किसान 2"],
    ["buyers", "Demo Buyer 1", "डेमो खरीदार 1"], ["buyers", "Demo Buyer 2", "डेमो खरीदार 2"],
    ["expense-heads", "Demo Expense A (placeholder)", "डेमो खर्च ए (प्लेसहोल्डर)"], ["expense-heads", "Demo Expense B (placeholder)", "डेमो खर्च बी (प्लेसहोल्डर)"],
  ];
  for (const [type, name, nameHi] of seed) await post(a.session, `/api/v1/masters/${type}`, { name, nameHi }, firmA);
  // an expense head with a predefined rate per piece (a synthetic rate of 2, not a real one): the bill multiplies its pieces by it
  await post(a.session, "/api/v1/masters/expense-heads", { name: "Demo Rated Expense", nameHi: "डेमो दर खर्च", rateBasis: "PER_PIECE", rate: 2 }, firmA);

  const b = await newOwner("owner-b");
  const b1 = await newFirm(b.session, `E2E Firm B1 ${SFX}`);
  const b2 = await newFirm(b.session, `E2E Firm B2 ${SFX}`);

  const c = await newOwner("owner-c");
  const firmCName = `E2E Firm C ${SFX}`;
  const firmC = await newFirm(c.session, firmCName);
  await post(c.session, "/api/v1/auth/active-firm", { firmId: firmC });
  const farmer = (await post(c.session, "/api/v1/masters/farmers", { name: `C Farmer ${SFX}` }, firmC)) as { id: number };

  const out: Fixtures = {
    password: PASSWORD,
    api: API,
    container: CONTAINER,
    db: DB,
    ownerA: { email: a.email, firmId: firmA, firmName: firmAName },
    ownerB: { email: b.email, firms: [{ id: b1, name: `E2E Firm B1 ${SFX}` }, { id: b2, name: `E2E Firm B2 ${SFX}` }] },
    ownerC: { email: c.email, firmId: firmC, firmName: firmCName, farmerId: farmer.id },
  };
  fs.writeFileSync(FIXTURES_FILE, JSON.stringify(out, null, 2));
  for (const s of [a.session, b.session, c.session]) await s.dispose();
}
