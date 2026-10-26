import { expect, test } from "./fixtures";
import { pickMaster, sql, fixtures } from "./support";

// The bill workflow where it used to fail silently: long master lists and saves whose answer is lost. Real browser, real
// backend, disposable database, signed in as a synthetic owner (fixtures.ts). Synthetic data only.

const tag = `Wf${Date.now().toString(36)}`;
const f = fixtures();
const villageName = `${tag} Village`;
let villageIdCache = 0;

/** The farmers of these tests live in one village of the firm (a bill is village first). */
async function ensureVillage(request: import("@playwright/test").APIRequestContext): Promise<number> {
  if (villageIdCache) return villageIdCache;
  const r = await request.post("/api/v1/masters/villages", { data: { name: villageName } });
  expect(r.ok()).toBeTruthy();
  villageIdCache = (await r.json()).id;
  return villageIdCache;
}

test.describe.serial("master pickers", () => {
  test.beforeEach(async ({ request }) => {
    // 120 farmers: far more than one page of the picker
    const vid = await ensureVillage(request);
    const have = Number(sql(`select count(*) from farmer where name like '${tag} Farmer %' and firm_id = ${f.ownerA.firmId}`));
    if (have >= 120) return;
    for (let i = have + 1; i <= 120; i++) {
      const r = await request.post("/api/v1/masters/farmers", { data: { name: `${tag} Farmer ${String(i).padStart(3, "0")}`, villageId: vid } });
      expect(r.ok()).toBeTruthy();
    }
  });

  test("a farmer beyond the first page is found by typing, with a visible hint that there are more", async ({ page }) => {
    await page.goto("/bills/new");
    await pickMaster(page.getByTestId("bill-village"), villageName);
    const farmer = page.getByTestId("bill-farmer");
    await farmer.click();
    await farmer.fill(`${tag} Farmer`);
    await expect(page.getByTestId("picker-option")).toHaveCount(20);                 // one page ...
    await expect(page.getByTestId("picker-more")).toContainText("of 120");            // ... and it says so
    await farmer.fill(`${tag} Farmer 117`);
    await expect(page.getByTestId("picker-option")).toHaveCount(1);
    await page.getByTestId("picker-option").first().click();
    await expect(farmer).toHaveValue(`${tag} Farmer 117`);
    await expect(farmer).not.toHaveAttribute("data-selected-id", "");
  });

  test("keyboard: arrows and Enter choose, Escape keeps the earlier choice, Enter in a closed list moves on", async ({ page }) => {
    await page.goto("/bills/new");
    await pickMaster(page.getByTestId("bill-village"), villageName);
    const farmer = page.getByTestId("bill-farmer");
    await farmer.click();
    await farmer.fill(`${tag} Farmer 05`);
    // wait for the FILTERED list: the options of the previous, unfiltered search may still be on screen for a moment
    await expect(page.getByTestId("picker-option").first()).toContainText(`${tag} Farmer 05`);
    await farmer.press("ArrowDown");
    await farmer.press("Enter");
    const chosen = await farmer.inputValue();
    expect(chosen).toMatch(new RegExp(`${tag} Farmer 05\\d`));
    // typing another search and abandoning it with Escape keeps the chosen farmer
    await farmer.fill("something else");
    await farmer.press("Escape");
    await expect(farmer).toHaveValue(chosen);
    // leaving the box also keeps it
    await farmer.fill(`${tag} Farmer 01`);
    await page.getByTestId("bill-vehicle").click();
    await expect(farmer).toHaveValue(chosen);
    // Enter in the lot fields still moves to the next field (the picker list is closed, so it does not pick anything)
    const lot = page.getByTestId("lot-row").first();
    await lot.getByTestId("lot-pieces").focus();
    await lot.getByTestId("lot-pieces").press("Enter");
    await expect(lot.getByTestId("lot-weight")).toBeFocused();
    await lot.getByTestId("lot-rate").focus();
    await lot.getByTestId("lot-rate").press("Enter");
    await expect(lot.getByTestId("lot-marka")).toBeFocused();   // the next field of the lot (the purchaser is chosen in its own rows)
    await expect(page.getByTestId("picker-list")).toHaveCount(0);
  });

  test("a slow answer to an old search never replaces the answer to the newest one", async ({ page }) => {
    await page.route("**/api/v1/masters/farmers/page**", async (route) => {
      const url = new URL(route.request().url());
      if (url.searchParams.get("q") === "Wf") await new Promise((r) => setTimeout(r, 1500));   // the OLD search is slow
      await route.continue();
    });
    await page.goto("/bills/new");
    await pickMaster(page.getByTestId("bill-village"), villageName);
    const farmer = page.getByTestId("bill-farmer");
    await farmer.click();
    await farmer.fill("Wf");
    await farmer.fill(`${tag} Farmer 117`);                                          // the newest search is fast
    await expect(page.getByTestId("picker-option")).toHaveCount(1);
    await page.waitForTimeout(2200);                                                  // the slow answer has arrived by now
    await expect(page.getByTestId("picker-option")).toHaveCount(1);                   // and was ignored
    await expect(page.getByTestId("picker-option").first()).toContainText("117");
  });
});

test.describe("saving a bill", () => {
  async function fillMinimalBill(page: import("@playwright/test").Page, farmerName: string) {
    await page.goto("/bills/new");
    await pickMaster(page.getByTestId("bill-village"), villageName);
    await pickMaster(page.getByTestId("bill-farmer"), farmerName);
    await page.getByTestId("bill-bhada-paid").selectOption("no");        // bhada paid is Yes by default, and then needs its amount
    const lot = page.getByTestId("lot-row").first();
    await lot.getByTestId("lot-item").selectOption({ label: "Onion" });
    await lot.getByTestId("lot-pieces").fill("3");
    await lot.getByTestId("lot-weight").fill("120");
    await lot.getByTestId("lot-rate").fill("7");
  }

  test("a save whose answer was lost can be repeated and still produces exactly one bill", async ({ page, request }) => {
    const name = `${tag} Retry Farmer`;
    const made = await (await request.post("/api/v1/masters/farmers", { data: { name, villageId: await ensureVillage(request) } })).json();
    await fillMinimalBill(page, name);

    let intercepted = 0;
    await page.route("**/api/v1/aadat/bills", async (route) => {
      if (route.request().method() !== "POST" || intercepted > 0) return route.continue();
      intercepted++;
      await route.fetch();                       // the server receives and SAVES the bill ...
      await route.abort("failed");               // ... but the browser never gets the answer
    });
    await page.getByTestId("bill-save").click();
    await expect(page.getByTestId("form-errors")).toContainText("Press Save again");
    expect(sql(`select count(*) from aadat_bill where farmer_id = ${made.id}`)).toBe("1");

    await page.getByTestId("bill-save").click();                              // the retry
    await page.waitForURL(/\/bills\/\d+/);
    expect(sql(`select count(*) from aadat_bill where farmer_id = ${made.id}`)).toBe("1");       // still one bill
    expect(sql(`select count(distinct bill_no) from aadat_bill where farmer_id = ${made.id}`)).toBe("1");  // one number, none wasted
    expect(sql(`select count(*) from audit_event where entity_type = 'AADAT_BILL' and entity_id = (select id from aadat_bill where farmer_id = ${made.id})`)).toBe("1");
    // the history names the person who saved it
    expect(sql(`select actor_role || '/' || (actor_user_id is not null) from audit_event where entity_type = 'AADAT_BILL' and entity_id = (select id from aadat_bill where farmer_id = ${made.id})`)).toBe("OWNER/true");
  });

  test("an unacceptable entry shows the reason, creates nothing and is not reported as saved", async ({ page, request }) => {
    const name = `${tag} Refused Farmer`;
    const made = await (await request.post("/api/v1/masters/farmers", { data: { name, villageId: await ensureVillage(request) } })).json();
    await fillMinimalBill(page, name);
    await page.getByTestId("lot-row").first().getByTestId("lot-weight").fill("-5");
    await page.getByTestId("bill-save").click();
    await expect(page.getByTestId("review-problems")).toBeVisible();
    await expect(page).toHaveURL(/\/bills\/new/);
    expect(sql(`select count(*) from aadat_bill where farmer_id = ${made.id}`)).toBe("0");
    // correcting the content and saving again is a NEW save and works
    await page.getByTestId("lot-row").first().getByTestId("lot-weight").fill("5");
    await page.getByTestId("bill-save").click();
    await page.waitForURL(/\/bills\/\d+/);
    expect(sql(`select count(*) from aadat_bill where farmer_id = ${made.id}`)).toBe("1");
  });
});
