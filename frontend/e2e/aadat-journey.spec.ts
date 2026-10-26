import type { APIRequestContext, Page } from "@playwright/test";
import { expect, test } from "./fixtures";
import { addExpense, addPurchaser, fillLot, pickMaster } from "./support";

const API = "/api/v1";
const sfx = String(Date.now()).slice(-7);
const farmerName = `E2E Farmer ${sfx}`;
const farmerNameHi = `ई2ई किसान ${sfx}`;
const vehicle = `E2E-${sfx}`;

async function billIdFromUrl(page: Page): Promise<number> {
  await page.waitForURL(/\/bills\/\d+/);
  return Number(new URL(page.url()).pathname.split("/")[2]);
}

async function getBill(request: APIRequestContext, id: number) {
  const res = await request.get(`${API}/aadat/bills/${id}`);
  expect(res.ok()).toBeTruthy();
  return res.json();
}

test.describe.serial("Aadat billing journey (synthetic data)", () => {
  test("masters: add a farmer with a Hindi name and find it by English and Hindi search", async ({ page }) => {
    await page.goto("/masters/farmers");
    await page.getByTestId("master-name").fill(farmerName);
    await page.getByTestId("master-name-hi").fill(farmerNameHi);
    await pickMaster(page.getByTestId("master-village"), "Demo Village A");          // a farmer belongs to a village
    await page.getByTestId("master-save").click();
    await expect(page.getByTestId("master-saved")).toBeVisible();
    // The list is paged (50 per page), so a new record is found by searching for it.
    await page.getByTestId("master-search").fill(farmerName);
    await expect(page.getByTestId("master-row").filter({ hasText: farmerName })).toHaveCount(1);

    await page.getByTestId("master-search").fill(sfx);
    await expect(page.getByTestId("master-row")).toHaveCount(1);
    await page.getByTestId("master-search").fill("किसान " + sfx);
    await expect(page.getByTestId("master-row")).toHaveCount(1);
  });

  test("create, save, search, reopen and print a calculated bill in English and Hindi", async ({ page, request }) => {
    await page.goto("/bills/new");
    await pickMaster(page.getByTestId("bill-village"), "Demo Village A");
    await pickMaster(page.getByTestId("bill-farmer"), farmerName);
    await page.getByTestId("bill-vehicle").fill(vehicle);
    await page.getByTestId("bill-bhada-paid").selectOption("no");        // bhada paid is Yes by default, and then needs its amount
    // rates are per 100 kg (18.25 a kg is entered as 1825): lot 1: 1000.5 kg x 18.25 = 18259.125 -> 18259; one purchaser buying all of it: value 18259, commission 5% = 912.95 -> 913
    await fillLot(page, { item: "Onion", pieces: "20", weight: "1000.5", rate: "1825" });
    await addPurchaser(page, { buyer: "Demo Buyer 1", pieces: "20", weight: "1000.5", rate: "1825" });
    // lot 2: 250 kg at 90 a kg (entered 9000) = 22500
    await page.getByTestId("add-lot").click();
    await fillLot(page, { item: "Garlic", pieces: "5", weight: "250", rate: "9000" });
    // expenses: a typed one (500) and one with a predefined rate of 2 per piece: 25 pieces x 2 = 50
    await addExpense(page, "Demo Expense A (placeholder)", "500");
    await addExpense(page, "Demo Rated Expense");

    // the figures shown while typing are the figures that will be saved
    await expect(page.getByTestId("lot-value").nth(0)).toHaveText("18259");
    await expect(page.getByTestId("lot-value").nth(1)).toHaveText("22500");
    await expect(page.getByTestId("expense-amount-calc")).toHaveText("50");
    await expect(page.getByTestId("expense-rate").last()).toHaveText("2");
    await expect(page.getByTestId("total-gross")).toHaveText("40759");
    await expect(page.getByTestId("total-expenses")).toHaveText("550");
    await expect(page.getByTestId("total-net")).toHaveText("40209");   // 40759 - 550
    await expect(page.getByTestId("total-buyer-value")).toHaveText("18259");
    await expect(page.getByTestId("total-buyer-commission")).toHaveText("913");
    await expect(page.getByTestId("total-buyer-purchase")).toHaveText("19172");

    await page.getByTestId("bill-save").click();
    const id = await billIdFromUrl(page);
    await expect(page.getByTestId("saved-msg")).toBeVisible();
    await expect(page.getByTestId("view-vehicle")).toHaveText(vehicle);
    await expect(page.getByTestId("view-status")).toHaveText("Calculated");
    await expect(page.getByTestId("view-lot-row")).toHaveCount(2);
    await expect(page.getByTestId("calc-notice")).toHaveCount(0);
    await expect(page.getByTestId("view-fy")).toHaveCount(0);                    // the bill shows its date, not the financial year
    await expect(page.getByTestId("view-totals")).toContainText("weight × rate");
    // the summary of the farmer bill shows the farmer side only; the buyer side appears once, inside the folded purchasers block
    await expect(page.getByTestId("view-totals-buyer")).toHaveCount(0);
    await expect(page.getByTestId("view-totals").getByTestId("total-buyer-purchase")).toHaveCount(0);
    // the purchasers are the buyer side: folded away, not part of the farmer bill
    await expect(page.getByTestId("view-purchaser-row")).toBeHidden();
    await page.getByTestId("view-buyer-side").locator("summary").click();
    await expect(page.getByTestId("view-purchaser-row")).toHaveCount(1);
    await expect(page.getByTestId("view-buyer-totals").getByTestId("total-buyer-purchase")).toHaveText("19172");
    await expect(page.getByTestId("total-buyer-purchase")).toHaveCount(1);

    // Independent verification of the persisted record through the API.
    const bill = await getBill(request, id);
    expect(bill.calculationStatus).toBe("CALCULATED");
    expect(bill.ruleSetId).toBe("OWNER_2026_10_25_Q100");
    expect(bill.farmer.name).toBe(farmerName);
    expect(bill.village.name).toBe("Demo Village A");
    expect(bill.vehicleNo).toBe(vehicle);
    expect(bill.lots).toHaveLength(2);
    expect(bill.lots[0]).toMatchObject({ pieces: 20, weight: 1000.5, rate: 1825, lotValue: 18259 });
    expect(bill.lots[0].item.name).toBe("Onion");
    expect(bill.lots[0].purchasers[0].buyer.name).toBe("Demo Buyer 1");
    expect(bill.lots[0].purchasers[0]).toMatchObject({ value: 18259, commissionPercent: 5, commissionAmount: 913 });
    expect(bill.lots[1]).toMatchObject({ lotValue: 22500 });
    expect(bill.lots[1].purchasers).toHaveLength(0);
    expect(bill.expenses[0].amount).toBe(500);
    expect(bill.expenses[1]).toMatchObject({ amount: 50, quantity: 25, rate: 2 });
    expect(bill.calculation.verified).toBe(true);
    expect(bill.totals.basis).toBe("CALCULATED");
    expect(bill.totals.farmerSide.netOfEnteredAmounts).toBe(40209);   // 18259 + 22500 - 500 - 50, added by hand here

    // the bill list carries the farmer-side figures
    await page.goto("/");
    await page.getByTestId("search-q").fill(vehicle);
    await expect(page.getByTestId("bill-row")).toHaveCount(1);
    await expect(page.getByTestId("bill-gross")).toHaveText("40759");
    await expect(page.getByTestId("bill-expenses")).toHaveText("550");
    await expect(page.getByTestId("bill-net")).toHaveText("40209");
    await page.getByTestId("bill-open").click();
    await expect(page.getByTestId("bill-ref")).toHaveText(String(bill.billNo));

    // Printable bill, English: the farmer bill only (no purchasers, no financial year).
    await page.getByTestId("open-print").click();
    await expect(page.getByTestId("print-doc-title")).toHaveText("Aadat Bill");
    await expect(page.getByTestId("print-ref")).toHaveText(String(bill.billNo));
    await expect(page.getByTestId("print-farmer")).toHaveText(farmerName);
    await expect(page.getByTestId("print-watermark")).toHaveCount(0);
    await expect(page.getByTestId("print-purchasers")).toHaveCount(0);
    await expect(page.getByTestId("print-fy")).toHaveCount(0);
    await expect(page.getByTestId("print-gross")).toHaveText("40759");
    await expect(page.getByTestId("print-net")).toHaveText("40209");
    await expect(page.getByTestId("print-totals-note")).toContainText("calculated from the weight and the rate");
    await expect(page.getByTestId("print-expenses")).toContainText("Demo Rated Expense");
    await page.screenshot({ path: "test-results/print-en.png", fullPage: true });

    // Printable bill, Hindi.
    await page.getByTestId("lang-select").selectOption("hi");
    await expect(page.getByTestId("print-doc-title")).toHaveText("आढ़त बिल");
    await expect(page.getByTestId("print-farmer")).toHaveText(farmerNameHi);
    await expect(page.getByTestId("print-lots")).toContainText("प्याज़");
    await expect(page.getByTestId("print-totals-note")).toContainText("वज़न और भाव");
    await page.screenshot({ path: "test-results/print-hi.png", fullPage: true });
  });

  test("the bill form has no demonstration calculation and no typed amounts for lots or purchasers", async ({ page }) => {
    await page.goto("/bills/new");
    await expect(page.locator(".demo-box")).toHaveCount(0);
    await expect(page.getByTestId("bill-preview")).toHaveCount(0);
    await expect(page.getByTestId("bill-fy")).toHaveCount(0);
    // the value of a lot is an output, not a field
    await expect(page.getByTestId("lot-value").first()).toHaveJSProperty("tagName", "OUTPUT");
    await pickMaster(page.getByTestId("bill-village"), "Demo Village A");
    await pickMaster(page.getByTestId("bill-farmer"), farmerName);
    await fillLot(page, { item: "Potato", pieces: "10", weight: "100", rate: "250" });          // 2.50 a kg, entered per 100 kg
    await expect(page.getByTestId("lot-value").first()).toHaveText("250");
    await page.getByTestId("add-purchaser").click();
    const row = page.getByTestId("purchaser-row").first();
    await expect(row.getByTestId("purchaser-value")).toHaveJSProperty("tagName", "OUTPUT");
    await expect(row.getByTestId("purchaser-commission-amount")).toHaveJSProperty("tagName", "OUTPUT");
    await expect(row.getByTestId("purchaser-rate")).toHaveValue("250");                 // the lot rate is offered, and can be changed
    await row.getByTestId("purchaser-weight").fill("40");
    await expect(row.getByTestId("purchaser-value")).toHaveText("100");                // 40 kg x 2.50 a kg
    await expect(row.getByTestId("purchaser-commission-amount")).toHaveText("5");      // 5 percent of 100
    await expect(row.getByTestId("purchaser-total")).toHaveText("105");
    await row.getByTestId("purchaser-rate").fill("300");                                // a buyer may pay another rate than the lot (3 a kg)
    await expect(row.getByTestId("purchaser-value")).toHaveText("120");
    await expect(page.getByTestId("lot-value").first()).toHaveText("250");             // and the lot value does not move
  });
});
