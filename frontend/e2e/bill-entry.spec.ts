import type { Page } from "@playwright/test";
import { expect, test } from "./fixtures";
import { pickMaster } from "./support";

// The bill entry (lot cards layout): saving a bill, the UI rules of the day (defaults, Party wording) and the menu.

/** Types a small bill (one lot of 1000 kg at 1800 per 100 kg = 18000, one party of 600 kg) and saves it. */
async function typeAndSave(page: Page, farmer: string) {
  await pickMaster(page.getByTestId("bill-village"), "Demo Village A");
  await pickMaster(page.getByTestId("bill-farmer"), farmer);
  await page.getByTestId("bill-bhada-paid").selectOption("no");
  const lot = page.getByTestId("lot-row").first();
  await lot.getByTestId("lot-item").selectOption({ label: "Onion" });
  await lot.getByTestId("lot-pieces").fill("20");
  await lot.getByTestId("lot-weight").fill("1000");
  await lot.getByTestId("lot-rate").fill("1800");
  await page.getByTestId("add-purchaser").first().click();
  const row = page.getByTestId("purchaser-row").last();
  await pickMaster(row.getByTestId("purchaser-buyer"), "Demo Buyer 1");
  await row.getByTestId("purchaser-weight").fill("600");
  await expect(row.getByTestId("purchaser-value")).toHaveText("10800");
  await expect(row.getByTestId("purchaser-commission-amount")).toHaveText("540");
  await page.getByTestId("bill-save").click();
}

test.describe.serial("bill entry", () => {
  const sfx = String(Date.now()).slice(-6);

  test("the menu has no layout group and the bill entry has no layout switcher", async ({ page }) => {
    await page.goto("/bills/new");
    await expect(page.getByTestId("nav-group-layouts")).toHaveCount(0);
    await expect(page.getByTestId("layout-switch")).toHaveCount(0);
    await expect(page.getByTestId("lot-row")).toHaveCount(1);                              // lot cards
    await page.goto("/bills/new?layout=4");                                               // an old link still opens the same page
    await expect(page.getByTestId("lot-row")).toHaveCount(1);
  });

  test("a bill is saved and the saved bill shows the same figures", async ({ page, request }) => {
    const name = `Lot Cards Farmer ${sfx}`;
    const villages = await (await request.get("/api/v1/masters/villages?q=Demo Village A")).json();
    const made = await request.post("/api/v1/masters/farmers", { data: { name, villageId: villages[0].id } });
    expect(made.ok()).toBeTruthy();
    const errors: string[] = [];
    page.on("console", (m) => { if (["error", "warning"].includes(m.type())) errors.push(`${m.type()}: ${m.text()}`); });
    page.on("pageerror", (e) => errors.push(`pageerror: ${e.message}`));
    await page.goto("/bills/new");
    await typeAndSave(page, name);
    await page.waitForURL(/\/bills\/\d+/);
    await expect(page.getByTestId("view-lot-row")).toHaveCount(1);
    await expect(page.getByTestId("view-totals").getByTestId("total-gross")).toHaveText("18000");
    await expect(page.getByTestId("view-totals").getByTestId("total-net")).toHaveText("18000");
    expect(errors, errors.join(" | ")).toEqual([]);
  });

  test("the rules of the form: bhada paid is Yes and bill type is Credit (Baaki) by default, with only two choices each; a party, not a purchaser", async ({ page }) => {
    await page.goto("/bills/new");
    await expect(page.getByTestId("bill-bhada-paid")).toHaveValue("yes");
    await expect(page.getByTestId("bill-bhada-paid").locator("option")).toHaveText(["Yes", "No"]);
    await expect(page.getByTestId("bill-bhada-amount")).toBeEnabled();                    // yes: its amount is asked for
    await expect(page.getByTestId("bill-type")).toHaveValue("CREDIT");
    await expect(page.getByTestId("bill-type").locator("option")).toHaveText(["Credit (Baaki)", "Cash"]);
    await page.getByTestId("bill-bhada-paid").selectOption("no");
    await expect(page.getByTestId("bill-bhada-amount")).toBeDisabled();
    // saving with the default Yes and no amount says what is missing
    await page.getByTestId("bill-bhada-paid").selectOption("yes");
    await page.getByTestId("bill-save").click();
    await expect(page.getByTestId("review-problems")).toContainText("bhada");
    // the wording: party
    await expect(page.getByTestId("add-purchaser")).toHaveText("+ Add party");
    await expect(page.getByTestId("new-buyer")).toHaveText("+ New party");
    await expect(page.getByTestId("lot-detail")).toContainText("Parties of lot 1");
    await expect(page.locator("main")).not.toContainText(/purchaser/i);
    await page.getByTestId("lang-select").selectOption("hi");
    await expect(page.getByTestId("add-purchaser")).toContainText("पार्टी");
    await expect(page.locator("main")).not.toContainText("खरीदार पंक्ति");
  });

  test("the opened menu is a light panel with the pages of a group below it on a thin line", async ({ page }) => {
    await page.goto("/bills/new");
    await page.getByTestId("nav-group-masters").click();
    const side = page.getByTestId("sidebar");
    expect(await side.evaluate((el) => getComputedStyle(el).backgroundColor)).toBe("rgb(255, 255, 255)");    // light, not dark, while open
    const panel = page.getByTestId("nav-panel-masters");
    await expect(panel).toBeVisible();
    expect(await panel.evaluate((el) => getComputedStyle(el).borderLeftWidth)).toBe("2px");
    const group = (await page.getByTestId("nav-group-masters").boundingBox())!;
    const item = (await page.getByTestId("nav-item-villages").boundingBox())!;
    expect(item.x, "the pages are indented under their group").toBeGreaterThan(group.x + 30);
    expect(await page.getByTestId("nav-item-villages").evaluate((el) => parseFloat(getComputedStyle(el).fontSize))).toBeLessThan(
      await page.getByTestId("nav-group-masters").evaluate((el) => parseFloat(getComputedStyle(el).fontSize)),
    );
    await page.keyboard.press("Escape");
    expect(await side.evaluate((el) => getComputedStyle(el).backgroundColor)).toBe("rgb(23, 42, 110)");        // the rail of symbols is dark again
  });
});
