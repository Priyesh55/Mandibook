import { WEB_URL } from "./ports";
import type { Page } from "@playwright/test";
import { expect, test } from "./fixtures";
import { addPurchaser, apiSignIn, authedRequest, fillLot, pickMaster, sql, fixtures } from "./support";

// Village-first bill entry and the master dialogs, in a real browser against the disposable stack (synthetic data only).
// A bill is typed village first; the farmer list is the farmers OF THAT VILLAGE; a village, farmer or purchaser that is missing
// is added in a centered dialog on top of the bill, and the bill underneath is never reset, whatever the dialog does.

const API = "/api/v1";
const sfx = String(Date.now()).slice(-7);
const f = fixtures();
const vA = `Dlg Village A ${sfx}`;
const vB = `Dlg Village B ${sfx}`;
const vEmpty = `Dlg Village Empty ${sfx}`;

const count = (table: string, name: string) => Number(sql(`select count(*) from ${table} where name = '${name}' and firm_id = ${f.ownerA.firmId}`));

async function typeDraft(page: Page) {
  await page.getByTestId("bill-vehicle").fill(`DRAFT-${sfx}`);
  await fillLot(page, { item: "Onion", pieces: "20", weight: "1000", rate: "1800" });
}
async function draftIntact(page: Page) {
  await expect(page.getByTestId("bill-vehicle")).toHaveValue(`DRAFT-${sfx}`);
  await expect(page.getByTestId("lot-pieces").first()).toHaveValue("20");
  await expect(page.getByTestId("lot-value").first()).toHaveText("18000");
}

test.describe.serial("village first and the master dialogs", () => {
  test.beforeAll(async ({ browser }) => {
    // masters through the real API of the signed-in owner
    const context = await browser.newContext({ baseURL: WEB_URL });
    await apiSignIn(context, f.ownerA.email, f.password);
    const api = authedRequest(context, f.ownerA.firmId);
    await api.post(`${API}/auth/active-firm`, { data: { firmId: f.ownerA.firmId } });
    const village = async (name: string) => (await (await api.post(`${API}/masters/villages`, { data: { name } })).json()).id as number;
    const a = await village(vA);
    const b = await village(vB);
    await village(vEmpty);
    for (const [name, v] of [[`Dlg Farmer A1 ${sfx}`, a], [`Dlg Farmer A2 ${sfx}`, a], [`Dlg Farmer B1 ${sfx}`, b]] as const) {
      expect((await api.post(`${API}/masters/farmers`, { data: { name, villageId: v } })).ok()).toBeTruthy();
    }
    expect((await api.post(`${API}/masters/farmers`, { data: { name: `Dlg Farmer NoVillage ${sfx}` } })).ok()).toBeTruthy();   // entered before villages existed
    await context.close();
  });

  test("the farmer is chosen after the village and only that village's farmers are offered", async ({ page }) => {
    await page.goto("/bills/new");
    const farmer = page.getByTestId("bill-farmer");
    await expect(farmer).toBeDisabled();
    await expect(farmer).toHaveAttribute("placeholder", "Select a village first");
    await expect(page.getByTestId("new-farmer")).toBeDisabled();
    await typeDraft(page);

    await pickMaster(page.getByTestId("bill-village"), vA);
    await expect(farmer).toBeEnabled();
    await farmer.click();
    await expect(page.getByTestId("picker-option")).toHaveCount(2);
    await expect(page.getByTestId("picker-list")).toContainText(`Dlg Farmer A1 ${sfx}`);
    await expect(page.getByTestId("picker-list")).not.toContainText(`Dlg Farmer B1 ${sfx}`);
    await expect(page.getByTestId("picker-list")).not.toContainText("NoVillage");
    await page.getByTestId("picker-option").first().click();
    await expect(farmer).toHaveValue(`Dlg Farmer A1 ${sfx}`);

    // changing the village clears only the farmer, who belonged to the old village; the rest of the bill stays
    await pickMaster(page.getByTestId("bill-village"), vB);
    await expect(farmer).toHaveValue("");
    await draftIntact(page);
    await farmer.click();
    await expect(page.getByTestId("picker-option")).toHaveCount(1);
    await expect(page.getByTestId("picker-list")).toContainText(`Dlg Farmer B1 ${sfx}`);
    await page.keyboard.press("Escape");

    // a village without farmers says so and offers the way out
    await pickMaster(page.getByTestId("bill-village"), vEmpty);
    await expect(page.getByTestId("no-farmers-hint")).toBeVisible();
    await expect(page.getByTestId("new-farmer")).toBeEnabled();
  });

  test("a village is added in a centered dialog, selected, and the bill underneath is untouched", async ({ page, context }) => {
    await page.goto("/bills/new");
    await typeDraft(page);
    const pagesBefore = context.pages().length;
    const name = `Dlg Village New ${sfx}`;
    await page.getByTestId("new-village").click();
    const dialog = page.getByTestId("master-dialog-villages");
    await expect(dialog).toBeVisible();
    expect(context.pages().length).toBe(pagesBefore);                       // no new tab
    const box = (await dialog.boundingBox())!;
    const vp = page.viewportSize()!;
    expect(Math.abs(box.x + box.width / 2 - vp.width / 2)).toBeLessThan(3);   // centered horizontally ...
    expect(Math.abs(box.y + box.height / 2 - vp.height / 2)).toBeLessThan(60); // ... and vertically
    expect(box.width).toBeLessThanOrEqual(540);                              // sized to its content, not the screen
    await expect(page.getByTestId("master-name")).toBeFocused();
    await page.getByTestId("master-name").fill(name);
    await page.getByTestId("master-save").click();
    await expect(dialog).not.toBeVisible();
    await expect(page.getByTestId("bill-village")).toHaveValue(name);          // selected for the bill
    await draftIntact(page);
    expect(count("village", name)).toBe(1);
    await expect(page.getByTestId("new-village")).toBeFocused();               // focus returns to where the operator was
  });

  test("a farmer is added to the bill's village in a dialog and the bill continues", async ({ page }) => {
    await page.goto("/bills/new");
    await pickMaster(page.getByTestId("bill-village"), vEmpty);
    await typeDraft(page);
    const name = `Dlg Farmer New ${sfx}`;
    await page.getByTestId("new-farmer").click();
    const dialog = page.getByTestId("master-dialog-farmers");
    await expect(dialog).toBeVisible();
    await expect(page.getByTestId("master-village-locked")).toHaveText(vEmpty);      // the village is the bill's, not a free choice
    await page.getByTestId("master-name").fill(name);
    await page.getByTestId("master-name-hi").fill(`नया किसान ${sfx}`);
    await page.getByTestId("master-save").click();
    await expect(dialog).not.toBeVisible();
    await expect(page.getByTestId("bill-farmer")).toHaveValue(name);
    await draftIntact(page);
    await expect(page.getByTestId("no-farmers-hint")).toHaveCount(0);
    expect(sql(`select (select name from village where id = village_id) from farmer where name = '${name}' and firm_id = ${f.ownerA.firmId}`)).toBe(vEmpty);
  });

  test("cancelling, Escape and the close button create nothing and keep the bill", async ({ page }) => {
    await page.goto("/bills/new");
    await pickMaster(page.getByTestId("bill-village"), vA);
    await typeDraft(page);
    const before = Number(sql(`select count(*) from farmer where firm_id = ${f.ownerA.firmId}`));
    const dialog = page.getByTestId("master-dialog-farmers");
    const name = `Dlg Never Saved ${sfx}`;
    for (const how of ["cancel", "escape", "close"] as const) {
      await page.getByTestId("new-farmer").click();
      await expect(dialog).toBeVisible();
      await page.getByTestId("master-name").fill(name);
      if (how === "cancel") await page.getByTestId("master-cancel").click();
      else if (how === "escape") await page.keyboard.press("Escape");
      else await page.getByTestId("master-dialog-close").click();
      await expect(dialog).not.toBeVisible();
      await draftIntact(page);
    }
    expect(Number(sql(`select count(*) from farmer where firm_id = ${f.ownerA.firmId}`))).toBe(before);
    expect(count("farmer", name)).toBe(0);
    await expect(page.getByTestId("bill-village")).toHaveValue(vA);
    // a reopened dialog starts empty: nothing typed before was kept
    await page.getByTestId("new-farmer").click();
    await expect(page.getByTestId("master-name")).toHaveValue("");
    await page.getByTestId("master-cancel").click();
  });

  test("invalid master data is explained inside the dialog, and a double click saves once", async ({ page }) => {
    await page.goto("/bills/new");
    await pickMaster(page.getByTestId("bill-village"), vA);
    await typeDraft(page);
    await page.getByTestId("new-farmer").click();
    const dialog = page.getByTestId("master-dialog-farmers");

    let posts = 0;
    await page.route("**/api/v1/masters/farmers", (route) => { if (route.request().method() === "POST") posts++; return route.continue(); });
    await page.getByTestId("master-save").click();                                   // nothing typed
    await expect(dialog.locator(".field-error")).toContainText("Enter a name");
    expect(posts).toBe(0);                                                           // caught in the browser, no request
    await page.getByTestId("master-name").evaluate((el) => el.removeAttribute("maxlength"));   // too long: only the server knows the limit
    await page.getByTestId("master-name").fill("x".repeat(201));
    await page.getByTestId("master-save").click();
    await expect(dialog.locator(".field-error")).toContainText(/200/);
    await expect(dialog).toBeVisible();
    expect(posts).toBe(1);
    await draftIntact(page);

    const name = `Dlg Double ${sfx}`;
    await page.getByTestId("master-name").fill(name);
    await page.getByTestId("master-save").dblclick();                                 // two quick clicks
    await expect(dialog).not.toBeVisible();
    expect(count("farmer", name)).toBe(1);
    await draftIntact(page);
  });

  test("a purchaser is added in a dialog and lands in a purchaser row", async ({ page }) => {
    await page.goto("/bills/new");
    await typeDraft(page);
    const name = `Dlg Buyer New ${sfx}`;
    await page.getByTestId("new-buyer").click();
    const dialog = page.getByTestId("master-dialog-buyers");
    await expect(dialog).toBeVisible();
    await expect(page.getByTestId("master-village")).toHaveCount(0);                 // a purchaser has no village
    await page.getByTestId("master-name").fill(name);
    await page.getByTestId("master-save").click();
    await expect(dialog).not.toBeVisible();
    await expect(page.getByTestId("purchaser-row")).toHaveCount(1);
    await expect(page.getByTestId("purchaser-buyer")).toHaveValue(name);
    await draftIntact(page);
  });

  test("saving with mandatory data missing says exactly what to fix and saves nothing", async ({ page }) => {
    await page.goto("/bills/new");
    const before = Number(sql(`select count(*) from aadat_bill where firm_id = ${f.ownerA.firmId}`));
    await page.getByTestId("bill-save").click();
    await expect(page.getByTestId("review-problems")).toContainText("Choose the farmer.");
    await expect(page.getByTestId("review-problems")).toContainText("Lot 1: choose the item.");
    await expect(page.getByTestId("review-problems")).toContainText("Lot 1: enter the weight and the rate per 100 kg.");
    await expect(page.getByTestId("review-count")).toContainText("to complete");
    await expect(page.getByTestId("review-ready")).toHaveCount(0);
    await expect(page).toHaveURL(/\/bills\/new$/);
    expect(Number(sql(`select count(*) from aadat_bill where firm_id = ${f.ownerA.firmId}`))).toBe(before);
  });

  test("the server refuses a farmer of another village even if the browser did not", async ({ request }) => {
    const villages = await (await request.get(`${API}/masters/villages?q=${encodeURIComponent(vA)}`)).json();
    const other = await (await request.get(`${API}/masters/villages?q=${encodeURIComponent(vB)}`)).json();
    const farmers = await (await request.get(`${API}/masters/farmers?q=${encodeURIComponent(`Dlg Farmer A1 ${sfx}`)}`)).json();
    const items = await (await request.get(`${API}/masters/items`)).json();
    const res = await request.post(`${API}/aadat/bills`, {
      data: { billDate: "2026-10-25", farmerId: farmers[0].id, villageId: other[0].id, lots: [{ itemId: items[0].id, pieces: 1, weight: 1, rate: 1, purchasers: [] }], expenses: [] },
    });
    expect(res.status()).toBe(422);
    expect(await res.text()).toContain("different village");
    const ok = await request.post(`${API}/aadat/bills`, {
      data: { billDate: "2026-10-25", farmerId: farmers[0].id, villageId: villages[0].id, lots: [{ itemId: items[0].id, pieces: 1, weight: 1, rate: 1, purchasers: [] }], expenses: [] },
    });
    expect(ok.status()).toBe(201);
  });

  for (const [width, height] of [[1920, 1080], [1440, 900], [1280, 720], [1024, 768], [390, 844]] as const) {
    test(`the bill page and its dialogs fit a ${width} px screen without sideways scrolling`, async ({ page }) => {
      await page.setViewportSize({ width, height });
      await page.goto("/bills/new");
      await pickMaster(page.getByTestId("bill-village"), vA);
      await pickMaster(page.getByTestId("bill-farmer"), `Dlg Farmer A1 ${sfx}`);
      await fillLot(page, { item: "Onion", pieces: "20", weight: "1000", rate: "1800" });
      await addPurchaser(page, { buyer: `Dlg Buyer New ${sfx}`, haste: "Synthetic Person", pieces: "12", weight: "600" });
      await addPurchaser(page, { buyer: `Dlg Buyer New ${sfx}`, pieces: "8", weight: "400" });
      await page.getByTestId("add-expense").click();
      await page.waitForLoadState("networkidle");
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      expect(overflow, `page overflows by ${overflow}px`).toBeLessThanOrEqual(1);
      if (width === 1440 || width === 1920) {
        // the whole desk is one screen: a typical bill (one lot, two purchasers, an expense row) needs no vertical scrolling at 1440 x 900
        const over = await page.evaluate(() => document.documentElement.scrollHeight - window.innerHeight);
        expect(over, `the desk is ${over}px taller than the ${height} px screen`).toBeLessThanOrEqual(2);
        // and it uses the width: the working area spans (almost) the whole window, not a narrow column
        const used = await page.getByTestId("section-lots").evaluate((el) => el.getBoundingClientRect().width);
        const side = await page.getByTestId("section-expenses").evaluate((el) => el.getBoundingClientRect().width);
        expect((used + side) / (width - 68), "the lots and expenses cards should span about 90 percent of the width beside the menu rail").toBeGreaterThan(0.9);
      }
      if (width >= 1024) {
        // the allocation table itself needs no scrolling at desktop widths
        const table = await page.getByTestId("purchaser-table").evaluate((el) => ({ w: el.scrollWidth, c: el.parentElement!.clientWidth }));
        expect(table.w, "purchaser table wider than its box").toBeLessThanOrEqual(table.c + 1);
      }
      await page.screenshot({ path: `test-results/bill-${width}.png`, fullPage: true });
      await page.getByTestId("new-village").click();
      const box = (await page.getByTestId("master-dialog-villages").boundingBox())!;
      expect(box.x).toBeGreaterThanOrEqual(0);
      expect(box.x + box.width).toBeLessThanOrEqual(width);
      expect(box.y + box.height).toBeLessThanOrEqual(height);
      await page.screenshot({ path: `test-results/dialog-${width}.png` });
      await page.getByTestId("master-cancel").click();
    });
  }

  test("a full bill flow produces no console errors, warnings, failed requests or unexpected error responses", async ({ page }) => {
    const problems: string[] = [];
    page.on("console", (m) => { if (["error", "warning"].includes(m.type())) problems.push(`${m.type()}: ${m.text()}`); });
    page.on("pageerror", (e) => problems.push(`pageerror: ${e.message}`));
    // Next.js cancels its own background page prefetches (?_rsc=) when the page changes: that is not a failure
    page.on("requestfailed", (r) => { if (!r.url().includes("_rsc=")) problems.push(`requestfailed: ${r.url()}`); });
    page.on("response", (r) => { if (r.status() >= 400) problems.push(`http ${r.status()}: ${r.url()}`); });
    await page.goto("/bills/new");
    await pickMaster(page.getByTestId("bill-village"), vA);
    await pickMaster(page.getByTestId("bill-farmer"), `Dlg Farmer A2 ${sfx}`);
    await page.getByTestId("bill-bhada-paid").selectOption("no");
    await fillLot(page, { item: "Potato", pieces: "5", weight: "250", rate: "3000" });
    await addPurchaser(page, { buyer: `Dlg Buyer New ${sfx}`, pieces: "5", weight: "250" });
    await page.getByTestId("new-village").click();
    await page.getByTestId("master-cancel").click();
    await page.getByTestId("bill-save").click();
    await page.waitForURL(/\/bills\/\d+/);
    await page.getByTestId("open-print").click();
    await expect(page.getByTestId("print-sheet")).toBeVisible();
    await page.goto("/");
    await expect(page.getByTestId("bill-row").first()).toBeVisible();
    expect(problems, problems.join("\n")).toEqual([]);
  });
});
