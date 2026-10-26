import { WEB_URL } from "./ports";
import fs from "node:fs";
import path from "node:path";
import { expect, test } from "./fixtures";
import { addExpense, addPurchaser, apiSignIn, authedRequest, chooseLot, fillLot, fixtures, pickMaster } from "./support";

// Evidence screenshots of the whole bill journey at the required viewport widths, against the disposable stack (synthetic data
// only). Files go to E2E_EVIDENCE_DIR (default test-results/evidence). Each step also asserts something, so a screenshot is never
// taken of a broken page: no horizontal scrolling, the draft survives a dialog, the saved bill and print are reachable.

const API = "/api/v1";
const OUT = process.env.E2E_EVIDENCE_DIR ?? path.join("test-results", "evidence");
const sfx = String(Date.now()).slice(-7);
const f = fixtures();
const village = `Ev Village ${sfx}`;
const farmer = `Ev Farmer ${sfx}`;
const buyers = [`Ev Buyer One ${sfx}`, `Ev Buyer Two ${sfx}`];

const SIZES = [
  { name: "1440", width: 1440, height: 900 },
  { name: "1280", width: 1280, height: 800 },
  { name: "1024", width: 1024, height: 768 },
  { name: "390", width: 390, height: 844 },
];

test.beforeAll(async ({ browser }) => {
  fs.mkdirSync(OUT, { recursive: true });
  const context = await browser.newContext({ baseURL: WEB_URL });
  await apiSignIn(context, f.ownerA.email, f.password);
  const api = authedRequest(context, f.ownerA.firmId);
  await api.post(`${API}/auth/active-firm`, { data: { firmId: f.ownerA.firmId } });
  const v = (await (await api.post(`${API}/masters/villages`, { data: { name: village } })).json()).id as number;
  expect((await api.post(`${API}/masters/farmers`, { data: { name: farmer, villageId: v } })).ok()).toBeTruthy();
  for (const b of buyers) expect((await api.post(`${API}/masters/buyers`, { data: { name: b } })).ok()).toBeTruthy();
  await context.close();
});

for (const size of SIZES) {
  test.describe(`bill journey at ${size.name}px`, () => {
    test.use({ viewport: { width: size.width, height: size.height } });

    test(`new bill, village first, dialog, lots and purchasers, expenses, review, save, search and print at ${size.name}`, async ({ page }) => {
      const shot = async (name: string, fullPage = false) => {
        await page.mouse.move(size.width - 3, size.height - 3);   // pointer away from the sidebar and the controls
        const expanded = await page.getByTestId("sidebar").getAttribute("data-expanded").catch(() => "n/a");
        test.info().annotations.push({ type: "sidebar", description: `${name}: data-expanded=${expanded}` });
        const sb = await page.getByTestId("sidebar").boundingBox().catch(() => null);
        const focused = await page.evaluate(() => (document.activeElement ? document.activeElement.tagName + "/" + (document.activeElement.getAttribute("data-testid") ?? "") : "none"));
        console.log(`SIDEBAR ${size.name} ${name} data-expanded=${expanded} width=${sb ? Math.round(sb.width) : "n/a"} focus=${focused}`);
        await page.screenshot({ path: path.join(OUT, `${size.name}-${name}.png`), fullPage });
        // A full-page capture of a page taller than the window shows the fixed navigation sidebar stretched over the content (a capture
        // artifact: the live sidebar is 68 px wide and data-expanded=false). The viewport-only twin is the clean evidence image.
        if (fullPage) await page.screenshot({ path: path.join(OUT, `${size.name}-${name}-viewport.png`), fullPage: false });
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
        expect(overflow, `horizontal overflow on ${name}`).toBeLessThanOrEqual(1);
      };

      await page.goto("/bills/new");
      await page.waitForLoadState("networkidle");
      await shot("01-new-bill-empty");

      // village first: the farmer is not offered until the village is chosen
      await expect(page.getByTestId("bill-farmer")).toBeDisabled();
      await page.getByTestId("bill-vehicle").fill(`EV-${sfx}`);
      await pickMaster(page.getByTestId("bill-village"), village);
      await page.getByTestId("bill-farmer").click();
      await expect(page.getByTestId("picker-list")).toContainText(farmer);
      await shot("02-village-first-farmer-list");
      await page.getByTestId("picker-option").first().click();
      await expect(page.getByTestId("bill-farmer")).toHaveValue(farmer);

      // a master dialog opens on top of the bill and leaves the draft untouched
      await page.getByTestId("new-village").click();
      await expect(page.getByTestId("master-dialog-villages")).toBeVisible();
      await shot("03-master-dialog");
      await page.keyboard.press("Escape");
      await expect(page.getByTestId("master-dialog-villages")).not.toBeVisible();
      await expect(page.getByTestId("bill-vehicle")).toHaveValue(`EV-${sfx}`);
      await expect(page.getByTestId("bill-farmer")).toHaveValue(farmer);

      // two lots, the first split between two purchasers
      await fillLot(page, { item: "Onion", pieces: "20", weight: "1000", rate: "1800" });
      await page.getByTestId("bill-bhada-paid").selectOption("yes");
      await page.getByTestId("bill-bhada-amount").fill("1000");
      await addPurchaser(page, { buyer: buyers[0], pieces: "12", weight: "600", rate: "1800" });
      await addPurchaser(page, { buyer: buyers[1], pieces: "8", weight: "400", rate: "1800" });
      await page.getByTestId("add-lot").click();
      await chooseLot(page, 1);
      await fillLot(page, { item: "Potato", pieces: "15", weight: "1500", rate: "1500" });
      await addPurchaser(page, { buyer: buyers[1], pieces: "15", weight: "1500", rate: "1500" });
      await expect(page.getByTestId("lot-value").first()).toHaveText("18000");
      await shot("04-lots-and-purchasers", true);

      await addExpense(page, "Demo Rated Expense");
      await addExpense(page, "Demo Expense A (placeholder)", "100");
      await shot("05-expenses", true);
      await expect(page.getByTestId("review-ready").or(page.getByTestId("review-warnings"))).toBeVisible();
      await shot("06-review-and-save", true);

      await page.getByTestId("bill-save").click();
      await expect(page.getByTestId("saved-msg")).toBeVisible();
      await shot("07-saved-bill", true);

      // search and reopen
      await page.goto("/");
      await expect(page.getByTestId("bill-row").first()).toBeVisible();
      await shot("08-bill-list");
      await page.getByTestId("bill-open").first().click();
      await shot("09-bill-view", true);

      // print in English and in Hindi
      await page.getByTestId("open-print").click();
      await expect(page.getByTestId("print-title")).toBeVisible();
      await shot("10-print-english", true);
      await page.getByTestId("lang-select").selectOption("hi");
      await expect(page.getByTestId("print-title")).toBeVisible();
      await shot("11-print-hindi", true);
      await page.getByTestId("lang-select").selectOption("en");
    });
  });
}
