import { WEB_URL } from "./ports";
import { expect, test } from "./fixtures";
import { addExpense, addPurchaser, apiSignIn, authedRequest, chooseLot, fillLot, fixtures, lotRow, pickMaster, sql } from "./support";

// The demonstration journey of the October 2026 MVP, end to end in a real browser against the disposable stack:
// a bill with header details, two lots, one lot split between two purchasers, two expenses calculated from a rate per piece and
// three typed ones, a lost save response, search by bill number, the saved farmer bill, its print, and the walls around it.
// Synthetic data only; every expected figure below was worked out by hand, not by the code under test:
//   rates are entered per 100 kg: 18 a kg is 1800
//   lot 1 = 1000 kg x 18 = 18000, lot 2 = 1500 kg x 15 = 22500, gross 40500 (35 pieces)
//   purchasers: 600 kg x 18 = 10800 (commission 5% = 540) and 400 kg x 18 = 7200 (commission 360)
//   expenses: 35 x 2.50 = 87.50, 35 x 1.75 = 61.25, typed 100 + 75.50, and the Motor Bhada of the bill details (1000), added
//   automatically = 1324.25
//   net = 40500 - 1324.25 = 39175.75 -> 39176

const API = "/api/v1";
const sfx = String(Date.now()).slice(-7);
const f = fixtures();

test.describe.serial("MVP demonstration journey", () => {
  let farmerId = 0;
  let billId = 0;
  let billNo = 0;
  const farmerName = `Mvp Farmer ${sfx}`;
  const buyerOne = `Mvp Buyer One ${sfx}`;
  const buyerTwo = `Mvp Buyer Two ${sfx}`;
  const heads = [1, 2, 3, 4, 5].map((n) => `Mvp Head ${n} ${sfx}`);

  test("the firm print profile is set and the numbering page reflects the series", async ({ page, request }) => {
    // masters used by the journey (through the real API of the signed-in owner)
    const villages = await (await request.get(`${API}/masters/villages?q=Demo Village A`)).json();
    const villageId = villages[0].id as number;
    farmerId = (await (await request.post(`${API}/masters/farmers`, { data: { name: farmerName, nameHi: `एमवीपी किसान ${sfx}`, villageId } })).json()).id;
    for (const b of [buyerOne, buyerTwo]) expect((await request.post(`${API}/masters/buyers`, { data: { name: b } })).ok()).toBeTruthy();
    // heads 1 and 2 carry synthetic per-piece rates 2.5 and 1.75 configured by this test (fixture names are generic), the others are typed on the bill
    const rates: Record<number, number> = { 0: 2.5, 1: 1.75 };
    for (const [i, h] of heads.entries()) {
      const data = rates[i] !== undefined ? { name: h, rateBasis: "PER_PIECE", rate: rates[i] } : { name: h };
      expect((await request.post(`${API}/masters/expense-heads`, { data })).ok()).toBeTruthy();
    }

    await page.goto("/settings/firm");
    await expect(page.getByTestId("firm-name")).toHaveText(f.ownerA.firmName);
    await page.getByTestId("firm-address").fill("Synthetic Road, Synthetic Town");
    await page.getByTestId("firm-phone").fill("0000000000");
    await page.getByTestId("firm-licence").fill("LIC-SYN-1");
    await page.getByTestId("firm-save").click();
    await expect(page.getByTestId("settings-saved")).toBeVisible();
    await page.reload();
    await expect(page.getByTestId("firm-licence")).toHaveValue("LIC-SYN-1");
    await expect(page.getByTestId("numbering-next")).not.toHaveText("—");
  });

  test("a bill with two lots, split purchasers and calculated expenses: draft survives date changes, totals add up, a lost response never duplicates", async ({ page }) => {
    await page.goto("/bills/new");
    await expect(page.getByTestId("bill-status")).toHaveText("Draft");
    await expect(page.getByTestId("bill-number-note")).toContainText("assigned when saved");   // no number is promised before the save
    await expect(page.getByTestId("bill-fy")).toHaveCount(0);                                  // no financial year on the bill: the date is enough

    // header
    await pickMaster(page.getByTestId("bill-village"), "Demo Village A");
    await pickMaster(page.getByTestId("bill-farmer"), farmerName);
    await page.getByTestId("bill-vehicle").fill(`MVP-${sfx}`);
    await page.getByTestId("bill-transport").fill("Synthetic Carrier");
    await page.getByTestId("bill-bhada-paid").selectOption("yes");
    await page.getByTestId("bill-bhada-amount").fill("1000");
    await page.getByTestId("bill-type").selectOption("CREDIT");
    await page.getByTestId("bill-refs").locator("summary").click();
    await page.getByTestId("bill-panjiyan").fill("P-9");
    await page.getByTestId("bill-entry-no").fill("E-3");

    // lot 1 (onion) split between two purchasers: what each ACTUALLY bought, not an equal share
    await fillLot(page, { item: "Onion", pieces: "20", weight: "1000", rate: "1800", marka: "M1" });
    await expect(page.getByTestId("lot-value").first()).toHaveText("18000");
    await addPurchaser(page, { buyer: buyerOne, haste: "Synthetic Person", pieces: "12", weight: "600" });
    await addPurchaser(page, { buyer: buyerTwo, pieces: "8", weight: "400" });
    await expect(page.getByTestId("purchaser-row")).toHaveCount(2);
    await expect(page.getByTestId("purchaser-pieces").nth(0)).toHaveValue("12");
    await expect(page.getByTestId("purchaser-pieces").nth(1)).toHaveValue("8");         // never divided automatically
    await expect(page.getByTestId("purchaser-value").nth(0)).toHaveText("10800");        // 600 kg x 18
    await expect(page.getByTestId("purchaser-commission-amount").nth(0)).toHaveText("540");
    await expect(page.getByTestId("purchaser-total").nth(0)).toHaveText("11340");        // 10800 + 540
    await expect(page.getByTestId("purchaser-total").nth(1)).toHaveText("7560");         // 7200 + 360
    await expect(page.getByTestId("lot-note")).toHaveCount(0);                           // 12 + 8 = 20 pieces, 600 + 400 = 1000 kg: nothing to note

    // lot 2 (potato) without purchasers
    await page.getByTestId("add-lot").click();
    await fillLot(page, { item: "Potato", pieces: "15", weight: "1500", rate: "1500" });
    await expect(page.getByTestId("lot-tab")).toHaveCount(2);
    await expect(page.getByTestId("lot-value").nth(1)).toHaveText("22500");

    // expenses: two calculated from the pieces (35), two typed, and the Motor Bhada of the details, added without pressing anything
    await addExpense(page, heads[0]);
    await addExpense(page, heads[1]);
    await expect(page.getByTestId("expense-amount-calc").nth(0)).toHaveText("87.5");     // 35 x 2.5
    await expect(page.getByTestId("expense-amount-calc").nth(1)).toHaveText("61.25");    // 35 x 1.75
    await expect(page.getByTestId("expense-rate").nth(0)).toHaveText("2.5");
    await addExpense(page, heads[2], "100");
    await addExpense(page, heads[3], "75.50");
    await expect(page.getByTestId("add-bhada-expense")).toHaveCount(0);                   // there is no button: the row is there on its own
    await expect(page.getByTestId("expense-auto-head")).toHaveText("Motor Bhada");         // with its head selected
    await expect(page.getByTestId("expense-amount-bhada")).toHaveText("1000");              // and the amount of the bill details
    await expect(page.getByTestId("expense-head").locator("option", { hasText: "Motor Bhada" })).toHaveCount(0);   // it cannot be picked a second time by hand

    // an unreadable date is a message that comes and goes; nothing else changes
    await page.getByTestId("bill-date").fill("");
    await expect(page.getByTestId("date-error")).toContainText("not a valid date");
    await chooseLot(page, 0);
    await expect(lotRow(page, 0).getByTestId("lot-item")).toHaveValue(/\d+/);
    await expect(page.getByTestId("purchaser-row")).toHaveCount(2);
    await expect(page.getByTestId("expense-row")).toHaveCount(5);        // four typed or calculated rows and the Motor Bhada row
    await expect(page.getByTestId("bill-vehicle")).toHaveValue(`MVP-${sfx}`);
    await expect(page.getByTestId("bill-farmer")).not.toHaveAttribute("data-selected-id", "");
    await page.getByTestId("bill-date").fill("2026-10-24");
    await expect(page.getByTestId("date-error")).toHaveCount(0);

    // live totals bar and review: farmer side and buyer side apart
    const summary = page.getByTestId("bill-summary");
    await expect(summary.getByTestId("total-gross")).toHaveText("40500");               // 18000 + 22500
    await expect(summary.getByTestId("total-expenses")).toHaveText("1324.25");          // 87.5 + 61.25 + 100 + 75.50 + 1000
    await expect(summary.getByTestId("total-net")).toHaveText("39176");                 // 39175.75 to the rupee
    await expect(summary.getByTestId("total-buyer-value")).toHaveText("18000");         // 10800 + 7200
    await expect(summary.getByTestId("total-buyer-commission")).toHaveText("900");      // 540 + 360
    await expect(summary.getByTestId("total-buyer-purchase")).toHaveText("18900");
    await expect(page.getByTestId("review-warnings")).toContainText("Lot 2");           // no purchaser entered for lot 2
    await expect(page.getByTestId("review-ready")).toBeVisible();
    await expect(page.getByTestId("review-problems")).toHaveCount(0);
    await page.screenshot({ path: "test-results/mvp-bill-form.png", fullPage: true });

    // save; the answer is lost on the way back: the bill IS saved, the screen says so honestly, nothing is reported as saved
    let intercepted = 0;
    await page.route("**/api/v1/aadat/bills", async (route) => {
      if (route.request().method() !== "POST" || intercepted > 0) return route.continue();
      intercepted++;
      await route.fetch();
      await route.abort("failed");
    });
    await page.getByTestId("bill-save").click();
    await expect(page.getByTestId("form-errors")).toContainText("Press Save again");
    await expect(page).toHaveURL(/\/bills\/new$/);
    expect(sql(`select count(*) from aadat_bill where farmer_id = ${farmerId}`)).toBe("1");
    await expect(page.getByTestId("lot-tab")).toHaveCount(2);                            // the form kept everything

    await page.getByTestId("bill-save").click();                                         // the retry
    await page.waitForURL(/\/bills\/\d+/);
    billId = Number(new URL(page.url()).pathname.split("/")[2]);
    expect(sql(`select count(*) from aadat_bill where farmer_id = ${farmerId}`)).toBe("1");          // still exactly one bill
    billNo = Number(sql(`select bill_no from aadat_bill where id = ${billId}`));
    expect(billNo).toBeGreaterThan(0);
    expect(sql(`select count(*) from aadat_lot_buyer where bill_id = ${billId}`)).toBe("2");
    expect(sql(`select actor_role || '/' || (actor_user_id is not null) from audit_event where entity_type = 'AADAT_BILL' and entity_id = ${billId}`)).toBe("OWNER/true");
    expect(sql(`select fy_start_year from aadat_bill where id = ${billId}`)).toBe("2026");
    // the server stored its own calculation, rate and quantity of the per-piece expenses included
    expect(sql(`select string_agg(amount::numeric(18,2) || '/' || coalesce(quantity::int::text, '-') || '/' || coalesce(rate::numeric(18,2)::text, '-'), ' ' order by seq) from aadat_expense where bill_id = ${billId}`))
      .toBe("87.50/35/2.50 61.25/35/1.75 100.00/-/- 75.50/-/- 1000.00/-/-");

    // the saved bill shows the same figures: the farmer bill, the purchasers folded away
    await expect(page.getByTestId("saved-msg")).toContainText(String(billNo));
    await expect(page.getByTestId("view-bill-no")).toHaveText(String(billNo));
    await expect(page.getByTestId("view-fy")).toHaveCount(0);
    await expect(page.getByTestId("view-bill-type")).toHaveText("Credit (Baaki)");
    await expect(page.getByTestId("view-bhada")).toHaveText("Yes (1000)");
    const totals = page.getByTestId("view-totals");
    await expect(totals.getByTestId("total-gross")).toHaveText("40500");
    await expect(totals.getByTestId("total-expenses")).toHaveText("1324.25");
    await expect(totals.getByTestId("total-net")).toHaveText("39176");
    await expect(page.getByTestId("view-totals-buyer")).toHaveCount(0);               // the buyer side is not in the farmer summary ...
    await page.getByTestId("view-buyer-side").locator("summary").click();
    await expect(page.getByTestId("view-purchaser-row")).toHaveCount(2);
    await expect(page.getByTestId("view-buyer-totals").getByTestId("total-buyer-purchase")).toHaveText("18900");   // ... but once, with the purchasers
    await expect(page.getByTestId("view-expense-row").filter({ hasText: "Motor Bhada" })).toHaveCount(1);
  });

  test("the bill is found by its number and its print is the farmer bill equal to the saved bill", async ({ page, request }) => {
    await page.goto("/");
    await page.getByTestId("search-bill-no").fill(String(billNo));
    await expect(page.getByTestId("bill-row")).toHaveCount(1);
    await expect(page.getByTestId("bill-no")).toHaveText(String(billNo));
    await expect(page.getByTestId("bill-net")).toHaveText("39176");
    await page.getByTestId("bill-open").click();
    await page.getByTestId("open-print").click();

    const api = await (await request.get(`${API}/aadat/bills/${billId}`)).json();
    await expect(page.getByTestId("print-title")).toHaveText(f.ownerA.firmName);
    await expect(page.getByTestId("print-address")).toHaveText("Synthetic Road, Synthetic Town");
    await expect(page.getByTestId("print-contact")).toContainText("LIC-SYN-1");
    await expect(page.getByTestId("print-ref")).toHaveText(String(api.billNo));
    // the farmer bill shows its date; the financial year, the purchasers and the commission charged to them are not on it
    await expect(page.getByTestId("print-date")).toHaveText("2026-10-24");
    await expect(page.getByTestId("print-fy")).toHaveCount(0);
    await expect(page.getByTestId("print-purchasers")).toHaveCount(0);
    await expect(page.getByTestId("print-purchaser-row")).toHaveCount(0);
    await expect(page.getByTestId("print-bill-type")).toHaveText("Credit (Baaki)");     // the label and its own value, not mixed with the bhada
    await expect(page.getByTestId("print-bhada")).toHaveText("Yes (1000)");
    await expect(page.getByTestId("print-lot-row")).toHaveCount(api.lots.length);
    await expect(page.getByTestId("print-expense-row")).toHaveCount(api.expenses.length);
    // every total on the sheet is the saved figure: the component calculates nothing
    await expect(page.getByTestId("print-gross")).toHaveText(String(api.totals.farmerSide.grossOfEnteredLotValues));
    await expect(page.getByTestId("print-total-expenses")).toHaveText(String(api.totals.farmerSide.totalOfEnteredExpenses));
    await expect(page.getByTestId("print-net")).toHaveText(String(api.totals.farmerSide.netOfEnteredAmounts));
    await expect(page.getByTestId("print-net")).toHaveText("39176");
    await expect(page.getByTestId("print-sheet")).toContainText("Buyer / commission agent signature");

    // printed page: no application chrome; A4 PDF is produced
    await page.emulateMedia({ media: "print" });
    await expect(page.locator(".topbar")).toBeHidden();
    await expect(page.getByTestId("print-button")).toBeHidden();
    const pdf = await page.pdf({ format: "A4", printBackground: true });
    expect(pdf.subarray(0, 4).toString("latin1")).toBe("%PDF");
    expect(pdf.length).toBeGreaterThan(8000);
    await page.emulateMedia({ media: "screen" });

    // the same bill in Hindi: Devanagari labels, and a Devanagari font was really loaded for them
    await page.getByTestId("lang-select").selectOption("hi");
    await expect(page.getByTestId("print-doc-title")).toHaveText("आढ़त बिल");
    await expect(page.getByTestId("print-sheet")).toContainText("क्रेता / कमीशन एजेंट के हस्ताक्षर");
    await expect(page.getByTestId("print-sheet")).toContainText("नेट रकम");
    await expect(page.getByTestId("print-net")).toHaveText("39176");
    const fontLoaded = await page.evaluate(async () => {
      await document.fonts.ready;
      return Array.from(document.fonts).some((ff) => /devanagari/i.test(ff.family) && ff.status === "loaded");
    });
    expect(fontLoaded).toBe(true);
    await page.screenshot({ path: "test-results/mvp-print-hi.png", fullPage: true });
    await page.getByTestId("lang-select").selectOption("en");
    await page.screenshot({ path: "test-results/mvp-print-en.png", fullPage: true });
  });

  test("another firm cannot see the bill, and without a session nothing can be read", async ({ browser, page }) => {
    const other = await browser.newContext({ baseURL: WEB_URL });
    await apiSignIn(other, f.ownerC.email, f.password);
    const asOther = authedRequest(other, f.ownerC.firmId);
    expect((await asOther.post(`${API}/auth/active-firm`, { data: { firmId: f.ownerC.firmId } })).ok()).toBeTruthy();
    expect((await asOther.get(`${API}/aadat/bills/${billId}`)).status()).toBe(404);
    const search = await (await asOther.get(`${API}/aadat/bills?billNo=${billNo}`)).json();
    expect(search.content).toHaveLength(0);
    // the other firm has its own series: its expected number is unaffected by this firm's bills
    const next = await (await asOther.get(`${API}/aadat/bills/next-number`)).json();
    expect(next.expectedNext).toBe(Number(sql(`select coalesce((select next_no from bill_number_counter where firm_id = ${f.ownerC.firmId}), 1)`)));
    await other.close();

    await page.context().clearCookies();
    const anonymous = await page.request.get(`${API}/aadat/bills/${billId}`);
    expect(anonymous.status()).toBe(401);
  });

  test("the starting number of a firm can be set before its first bill and is then locked", async ({ browser }) => {
    const owner = await browser.newContext({ baseURL: WEB_URL });
    await apiSignIn(owner, f.ownerC.email, f.password);
    const api = authedRequest(owner, f.ownerC.firmId);
    expect((await api.post(`${API}/auth/active-firm`, { data: { firmId: f.ownerC.firmId } })).ok()).toBeTruthy();
    const page = await owner.newPage();
    await page.goto("/");
    await page.goto("/settings/firm");
    await expect(page.getByTestId("numbering-next")).toHaveText("1");
    await page.getByTestId("numbering-start").fill("7000");
    await page.getByTestId("numbering-set").click();
    await expect(page.getByTestId("settings-saved")).toBeVisible();
    await expect(page.getByTestId("numbering-next")).toHaveText("7000");

    const item = await (await api.post(`${API}/masters/items`, { data: { name: `Mvp Item ${sfx}` } })).json();
    const made = await api.post(`${API}/aadat/bills`, {
      data: { billDate: "2026-10-24", farmerId: f.ownerC.farmerId, lots: [{ itemId: item.id, pieces: 1, weight: 1, rate: 10, purchasers: [] }], expenses: [] },
    });
    expect(made.status()).toBe(201);
    expect((await made.json()).billNo).toBe(7000);

    await page.reload();
    await expect(page.getByTestId("numbering-locked")).toBeVisible();
    await expect(page.getByTestId("numbering-start")).toHaveCount(0);
    expect((await api.put(`${API}/firm/bill-numbering`, { data: { nextNumber: 5 } })).status()).toBe(409);
    await owner.close();
  });
  test("changing a configured head rate changes the calculated expense of the next bill", async ({ page, request }) => {
    const name = `Mvp Rate Head ${sfx}`;
    const created = await request.post(`${API}/masters/expense-heads`, { data: { name, rateBasis: "PER_PIECE", rate: 4 } });
    expect(created.ok()).toBeTruthy();
    const headId = (await created.json()).id as number;

    // 10 pieces x a configured rate of 4 = 40 (synthetic rate, configured by this test)
    await page.goto("/bills/new");
    await fillLot(page, { item: "Onion", pieces: "10", weight: "100", rate: "1000" });
    await addExpense(page, name);
    await expect(page.getByTestId("expense-amount-calc").last()).toHaveText("40");

    // the operator changes the rate in the master data; a fresh bill uses the new rate
    const updated = await request.put(`${API}/masters/expense-heads/${headId}`, { data: { name, rateBasis: "PER_PIECE", rate: 6 } });
    expect(updated.ok()).toBeTruthy();
    await page.goto("/bills/new");
    await fillLot(page, { item: "Onion", pieces: "10", weight: "100", rate: "1000" });
    await addExpense(page, name);
    await expect(page.getByTestId("expense-amount-calc").last()).toHaveText("60");
  });
});
