import type { Page } from "@playwright/test";
import { expect, test } from "./fixtures";
import { pickMaster } from "./support";

// The layouts of the bill entry that are being compared (temporary), the UI rules of the day (defaults, Party wording) and the menu.
// Every layout is the same bill logic, so the same typing gives the same figures in each of them.

const NAMES = ["Classic desk", "Lot cards", "Guided steps", "Sheet", "Split view", "Live bill"];

async function netShown(page: Page): Promise<string> {
  const live = page.getByTestId("preview-net");
  return ((await live.count()) > 0 ? await live.textContent() : await page.getByTestId("total-net").first().textContent()) ?? "";
}

/** Types a small bill (one lot of 1000 kg at 1800 per 100 kg = 18000, one party of 600 kg) in whatever layout is open and saves it. */
async function typeAndSave(page: Page, farmer: string) {
  await pickMaster(page.getByTestId("bill-village"), "Demo Village A");
  await pickMaster(page.getByTestId("bill-farmer"), farmer);
  await page.getByTestId("bill-bhada-paid").selectOption("no");
  const layout = Number(await page.getByTestId("bill-form").getAttribute("data-layout"));
  if (layout === 3) await page.getByTestId("step-next").click();                    // guided steps: lots and parties are step 2
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
  if (layout === 3) { await page.getByTestId("step-next").click(); await page.getByTestId("step-next").click(); }   // expenses, then the review
  await page.getByTestId("bill-save").click();
}

test.describe.serial("layouts of the bill entry", () => {
  const sfx = String(Date.now()).slice(-6);

  test("the menu lists the layouts under a temporary group and each one opens the bill entry in that layout", async ({ page }) => {
    await page.goto("/");
    for (let n = 1; n <= 6; n++) {
      await page.getByTestId("nav-group-layouts").click();
      await expect(page.getByTestId(`nav-item-layout-${n}`)).toContainText(NAMES[n - 1]);
      await page.getByTestId(`nav-item-layout-${n}`).click();
      await expect(page).toHaveURL(new RegExp(`/bills/new\\?layout=${n}$`));
      await expect(page.getByTestId("bill-form")).toHaveAttribute("data-layout", String(n));
      await expect(page.getByTestId(`layout-switch-${n}`)).toHaveAttribute("aria-current", "true");
    }
    // an unknown choice falls back to the classic desk
    await page.goto("/bills/new?layout=99");
    await expect(page.getByTestId("bill-form")).toHaveAttribute("data-layout", "1");
  });

  test("switching layouts keeps everything typed and every layout shows the same figures", async ({ page, request }) => {
    const villages = await (await request.get("/api/v1/masters/villages?q=Demo Village A")).json();
    expect((await request.post("/api/v1/masters/farmers", { data: { name: `Lay Switch Farmer ${sfx}`, villageId: villages[0].id } })).ok()).toBeTruthy();
    await page.goto("/bills/new");
    await pickMaster(page.getByTestId("bill-village"), "Demo Village A");
    await pickMaster(page.getByTestId("bill-farmer"), `Lay Switch Farmer ${sfx}`);
    await page.getByTestId("bill-bhada-paid").selectOption("no");
    await page.getByTestId("bill-vehicle").fill(`LAY-${sfx}`);
    const lot = page.getByTestId("lot-row").first();
    await lot.getByTestId("lot-item").selectOption({ label: "Onion" });
    await lot.getByTestId("lot-pieces").fill("20");
    await lot.getByTestId("lot-weight").fill("1000");
    await lot.getByTestId("lot-rate").fill("1800");
    await page.getByTestId("add-purchaser").click();
    const row = page.getByTestId("purchaser-row").last();
    await pickMaster(row.getByTestId("purchaser-buyer"), "Demo Buyer 1");
    await row.getByTestId("purchaser-weight").fill("600");
    await page.getByTestId("add-expense").click();
    await page.getByTestId("expense-row").last().getByTestId("expense-head").selectOption({ label: "Demo Rated Expense" });   // 20 pieces x 2 = 40

    for (let n = 1; n <= 6; n++) {
      await page.getByTestId(`layout-switch-${n}`).click();
      await expect(page.getByTestId("bill-form")).toHaveAttribute("data-layout", String(n));
      expect(await netShown(page), `net in layout ${n}`).toBe("17960");                    // 18000 - 40
      if (n === 3) await page.getByTestId("step-1").click();
      if (n !== 3) await expect(page.getByTestId("lot-value").first()).toHaveText("18000");
      if (n === 3) { await expect(page.getByTestId("bill-vehicle")).toHaveValue(`LAY-${sfx}`); }
      else await expect(page.getByTestId("bill-vehicle")).toHaveValue(`LAY-${sfx}`);
    }
    // nothing was lost on the way: back in the classic desk the party and the expense are still there
    await page.getByTestId("layout-switch-1").click();
    await expect(page.getByTestId("purchaser-weight")).toHaveValue("600");
    await expect(page.getByTestId("expense-amount-calc")).toHaveText("40");
    expect(new URL(page.url()).searchParams.get("layout")).toBe("1");
  });

  for (let n = 1; n <= 6; n++) {
    test(`layout ${n} (${NAMES[n - 1]}) saves a bill and the saved bill is the same`, async ({ page, request }) => {
      const name = `Lay${n} Farmer ${sfx}`;
      const villages = await (await request.get("/api/v1/masters/villages?q=Demo Village A")).json();
      const made = await request.post("/api/v1/masters/farmers", { data: { name, villageId: villages[0].id } });
      expect(made.ok()).toBeTruthy();
      const errors: string[] = [];
      page.on("console", (m) => { if (["error", "warning"].includes(m.type())) errors.push(`${m.type()}: ${m.text()}`); });
      page.on("pageerror", (e) => errors.push(`pageerror: ${e.message}`));
      await page.goto(`/bills/new?layout=${n}`);
      await expect(page.getByTestId("bill-form")).toHaveAttribute("data-layout", String(n));
      await typeAndSave(page, name);
      await page.waitForURL(/\/bills\/\d+/);
      await expect(page.getByTestId("view-lot-row")).toHaveCount(1);
      await expect(page.getByTestId("view-totals").getByTestId("total-gross")).toHaveText("18000");
      await expect(page.getByTestId("view-totals").getByTestId("total-net")).toHaveText("18000");
      expect(errors, errors.join("\n")).toEqual([]);
      await page.screenshot({ path: `test-results/layout-${n}-saved.png` });
    });
  }

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
