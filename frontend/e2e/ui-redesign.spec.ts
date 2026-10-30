import type { Page } from "@playwright/test";
import { expect, test } from "./fixtures";
import { lotRow, pickMaster } from "./support";

// UI behaviour of the indigo bill workflow. Synthetic data only; nothing here is saved to a bill.
const sfx = String(Date.now()).slice(-6);

async function openNewBill(page: Page) {
  await page.goto("/bills/new");
  await expect(page.getByTestId("lot-detail")).toBeVisible();
  await expect(page.getByTestId("lot-item").first().locator("option")).not.toHaveCount(1); // masters loaded
}

test.describe("bill entry: keyboard", () => {
  test("Enter moves through the lot fields and never saves the bill", async ({ page }) => {
    await openNewBill(page);
    const item = page.getByTestId("lot-item");
    await item.selectOption({ label: "Onion" });
    await item.press("Enter");
    await expect(page.getByTestId("lot-pieces")).toBeFocused();
    await page.keyboard.type("5");
    await page.keyboard.press("Enter");
    await expect(page.getByTestId("lot-weight")).toBeFocused();
    await page.keyboard.type("50");
    await page.keyboard.press("Enter");
    await expect(page.getByTestId("lot-rate")).toBeFocused();
    await page.keyboard.type("3");
    await page.keyboard.press("Enter");
    await expect(page.getByTestId("lot-marka")).toBeFocused();
    await page.keyboard.press("Enter");                       // last typed field of the lot (its value is calculated): nothing is added, nothing is saved
    await expect(page.getByTestId("purchaser-row")).toHaveCount(0);
    await expect(page.getByTestId("lot-tab")).toHaveCount(1);
    await expect(page).toHaveURL(/\/bills\/new$/);             // Enter never saved the bill
  });

  test("a purchaser row is added from the keyboard only once a purchaser was chosen; Shift+Enter goes back", async ({ page }) => {
    await openNewBill(page);
    await page.getByTestId("add-purchaser").click();
    await expect(page.getByTestId("purchaser-row")).toHaveCount(1);
    await page.getByTestId("purchaser-rate").first().focus();
    await page.keyboard.press("Enter");                      // last typed field of the row, but no purchaser chosen yet
    await expect(page.getByTestId("purchaser-row")).toHaveCount(1);
    await page.keyboard.press("Shift+Enter");
    await expect(page.getByTestId("purchaser-weight").first()).toBeFocused();
    await pickMaster(page.getByTestId("purchaser-buyer").first(), "Demo Buyer 1");
    await page.getByTestId("purchaser-rate").first().focus();
    await page.keyboard.press("Enter");
    await expect(page.getByTestId("purchaser-row")).toHaveCount(2);
    await expect(page.getByTestId("purchaser-buyer").nth(1)).toBeFocused();
  });

  test("expense grid supports the same keyboard flow", async ({ page }) => {
    await openNewBill(page);
    await page.getByTestId("add-expense").click();
    const head = page.getByTestId("expense-head").first();
    await expect(head).toBeFocused();
    await head.selectOption({ label: "Demo Expense A (placeholder)" });
    await head.press("Enter");
    await expect(page.getByTestId("expense-amount").first()).toBeFocused();
    await page.keyboard.type("25");
    await page.keyboard.press("Enter");
    await expect(page.getByTestId("expense-row")).toHaveCount(2);
    await expect(page.getByTestId("expense-head").nth(1)).toBeFocused();
  });
});

test.describe("bill entry: add and remove", () => {
  test("removing an empty lot is immediate; removing a filled lot asks first (Cancel, Esc, then confirm)", async ({ page }) => {
    await openNewBill(page);
    await page.getByTestId("add-lot").click();
    await expect(page.getByTestId("lot-tab")).toHaveCount(2);

    // Empty lot: removed without a dialog.
    await page.getByTestId("lot-remove").nth(1).click();
    await expect(page.getByTestId("lot-tab")).toHaveCount(1);
    await expect(page.getByTestId("remove-dialog")).not.toBeVisible();

    // Filled lot: dialog; Cancel keeps it.
    await page.getByTestId("add-lot").click();
    await lotRow(page, 1).getByTestId("lot-weight").fill("123");
    await page.getByTestId("lot-remove").nth(1).click();
    const dialog = page.getByTestId("remove-dialog");
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText("2");
    await expect(page.getByTestId("confirm-cancel")).toBeFocused(); // safe default focus
    await page.getByTestId("confirm-cancel").click();
    await expect(dialog).not.toBeVisible();
    await expect(page.getByTestId("lot-tab")).toHaveCount(2);
    await expect(lotRow(page, 1).getByTestId("lot-weight")).toHaveValue("123");     // nothing was lost

    // Esc also keeps it.
    await page.getByTestId("lot-remove").nth(1).click();
    await expect(dialog).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(dialog).not.toBeVisible();
    await expect(page.getByTestId("lot-tab")).toHaveCount(2);

    // Confirm removes it.
    await page.getByTestId("lot-remove").nth(1).click();
    await page.getByTestId("confirm-ok").click();
    await expect(page.getByTestId("lot-tab")).toHaveCount(1);
  });

  test("a purchaser row with data is removed only after confirming", async ({ page }) => {
    await openNewBill(page);
    await page.getByTestId("add-purchaser").click();
    await page.getByTestId("purchaser-pieces").fill("4");
    await page.getByTestId("purchaser-remove").click();
    await expect(page.getByTestId("remove-dialog")).toBeVisible();
    await page.getByTestId("confirm-cancel").click();
    await expect(page.getByTestId("purchaser-pieces")).toHaveValue("4");
    await page.getByTestId("purchaser-remove").click();
    await page.getByTestId("confirm-ok").click();
    await expect(page.getByTestId("purchaser-row")).toHaveCount(0);
  });

  test("the last remaining lot cannot be removed", async ({ page }) => {
    await openNewBill(page);
    await expect(page.getByTestId("lot-remove")).toBeDisabled();
  });
});

test.describe("theme and accessibility", () => {
  test("blue-indigo page, primary button, section heads and the current lot", async ({ page }) => {
    await openNewBill(page);
    await page.getByTestId("add-lot").click();
    const color = (sel: string, prop: string) => page.locator(sel).first().evaluate((el, p) => getComputedStyle(el).getPropertyValue(p), prop);

    expect(await color("body", "background-color")).toBe("rgb(243, 246, 251)");             // --page
    expect(await color(".card2", "background-color")).toBe("rgb(255, 255, 255)");            // white work surface
    expect(await color("[data-testid=bill-save]", "background-color")).toBe("rgb(31, 79, 207)");   // --ind-700
    expect(await color("[data-testid=bill-save]", "color")).toBe("rgb(255, 255, 255)");
    expect(await color(".card2-head", "background-color")).toBe("rgba(0, 0, 0, 0)");         // plain heading, no tinted band
    expect(await color(".card2-head h2", "color")).toBe("rgb(23, 42, 110)");                // --ind-900
    // the lot being edited is marked by colour and by aria-current (never by colour alone)
    const current = page.getByTestId("lot-tab").nth(1);
    await expect(current).toHaveAttribute("aria-current", "true");
    expect(await current.evaluate((el) => getComputedStyle(el).backgroundColor)).toBe("rgb(31, 79, 207)");   // the number of the current lot is filled
    expect(await current.evaluate((el) => getComputedStyle(el).color)).toBe("rgb(255, 255, 255)");
    expect(await lotRow(page, 1).evaluate((el) => getComputedStyle(el).borderLeftColor)).toBe("rgb(31, 79, 207)");   // and its card has the indigo left border (--ind-700)
    expect(await lotRow(page, 0).evaluate((el) => getComputedStyle(el).borderLeftColor)).not.toBe("rgb(31, 79, 207)");   // the other lot does not
    await page.screenshot({ path: "test-results/ui-bill-form.png", fullPage: true });
  });

  test("keyboard focus shows a visible 3px indigo ring", async ({ page }) => {
    await page.goto("/bills/new");
    await page.getByTestId("bill-vehicle").focus();
    const ring = await page.getByTestId("bill-vehicle").evaluate((el) => {
      const s = getComputedStyle(el);
      return { width: s.outlineWidth, style: s.outlineStyle, color: s.outlineColor };
    });
    expect(ring).toEqual({ width: "3px", style: "solid", color: "rgb(61, 106, 224)" });
    await page.getByTestId("bill-save").focus();
    await page.keyboard.press("Shift+Tab"); // move off and back to make sure :focus-visible applies to buttons
    await page.keyboard.press("Tab");
    const btn = await page.getByTestId("bill-save").evaluate((el) => getComputedStyle(el).outlineStyle);
    expect(btn).toBe("solid");
  });

  test("status colours: success green, error red, warning amber", async ({ page }) => {
    await page.goto("/masters/villages");
    await expect(page.getByTestId("demo-banner")).toBeVisible();
    const banner = await page.getByTestId("demo-banner").evaluate((el) => getComputedStyle(el).backgroundColor);
    expect(banner).toBe("rgb(255, 244, 214)"); // amber pale
    // Error: empty name is blocked by the browser, so force a server-side error with whitespace only.
    await page.getByTestId("master-name").fill("   ");
    await page.getByTestId("master-save").click();
    const err = page.getByTestId("master-error");
    await expect(err).toBeVisible();
    expect(await err.evaluate((el) => getComputedStyle(el).color)).toBe("rgb(180, 35, 24)");
    // Success
    await page.getByTestId("master-name").fill(`UI Farmer ${sfx}`);
    await page.getByTestId("master-save").click();
    const ok = page.getByTestId("master-saved");
    await expect(ok).toBeVisible();
    expect(await ok.evaluate((el) => getComputedStyle(el).color)).toBe("rgb(23, 104, 63)");
    await page.screenshot({ path: "test-results/ui-masters.png", fullPage: true });
  });
});

test.describe("masters: deactivate confirmation", () => {
  test("deactivating asks first; Cancel keeps the record active", async ({ page }) => {
    await page.goto("/masters/villages");
    const name = `Confirm Village ${sfx}`;
    await page.getByTestId("master-name").fill(name);
    await page.getByTestId("master-save").click();
    await expect(page.getByTestId("master-saved")).toBeVisible();
    // The list is paged (50 per page), so a new record is found by searching for it.
    await page.getByTestId("master-search").fill(name);
    const row = page.getByTestId("master-row").filter({ hasText: name });
    await expect(row).toHaveCount(1);

    await row.getByTestId("master-toggle").click();
    const dialog = page.getByTestId("deactivate-dialog");
    await expect(dialog).toBeVisible();
    await page.getByTestId("confirm-cancel").click();
    await expect(dialog).not.toBeVisible();
    await expect(row).toContainText("Active");

    await row.getByTestId("master-toggle").click();
    await page.getByTestId("confirm-ok").click();
    await expect(row).toContainText("Inactive");
    await expect(row).toHaveClass(/inactive/);
    // Re-activating needs no confirmation.
    await row.getByTestId("master-toggle").click();
    await expect(row).not.toHaveClass(/inactive/);
  });
});

test.describe("Hindi UI", () => {
  test("dialog, hints and lot grid are translated", async ({ page }) => {
    await page.goto("/bills/new");
    await page.getByTestId("lang-select").selectOption("hi");
    await expect(page.getByTestId("section-lots")).toContainText("नग");
    await expect(page.getByTestId("bill-summary")).toContainText("किसान पक्ष");
    await page.getByTestId("add-lot").click();
    await lotRow(page, 1).getByTestId("lot-weight").fill("9");
    await page.getByTestId("lot-remove").nth(1).click();
    await expect(page.getByTestId("remove-dialog")).toContainText("लॉट 2 हटाएँ?");
    await page.screenshot({ path: "test-results/ui-dialog-hi.png", fullPage: true });
    await page.getByTestId("confirm-cancel").click();
  });
});

// ---------------------------------------------------------------------------------------------
// Application-wide polish: navigation, bills list, masters tabs, bill history, Hindi layout.
// ---------------------------------------------------------------------------------------------
const API = "/api/v1";

async function createSyntheticBill(request: import("@playwright/test").APIRequestContext, tag: string) {
  const farmer = await (await request.post(`${API}/masters/farmers`, { data: { name: `Polish Farmer ${tag}`, nameHi: `पॉलिश किसान ${tag}` } })).json();
  const items = await (await request.get(`${API}/masters/items`)).json();
  const onion = items.find((i: { name: string }) => i.name === "Onion");
  const res = await request.post(`${API}/aadat/bills`, {
    data: { billDate: "2026-10-19", farmerId: farmer.id, vehicleNo: `PL-${tag}`, lots: [{ itemId: onion.id, pieces: 3, weight: 30, rate: 2 }], expenses: [] },
  });
  expect(res.ok()).toBeTruthy();
  const bill = await res.json();
  return { farmer, billId: bill.id as number };
}

test.describe("navigation and call to action", () => {
  test("indigo sidebar and a prominent New bill button on every page", async ({ page }) => {
    for (const path of ["/", "/masters/farmers"]) {   // the bill desk hides it: you are already on a new bill
      await page.goto(path);
      const bar = await page.locator(".sidebar").evaluate((el) => getComputedStyle(el).backgroundColor);
      expect(bar).toBe("rgb(23, 42, 110)"); // --nav blue-indigo: the sidebar
      const cta = page.getByTestId("nav-new-bill");
      await expect(cta).toBeVisible();
      const style = await cta.evaluate((el) => ({ bg: getComputedStyle(el).backgroundColor, color: getComputedStyle(el).color }));
      expect(style).toEqual({ bg: "rgb(31, 79, 207)", color: "rgb(255, 255, 255)" }); // the primary action of the top bar
    }
  });

  test("the bills list has its own large primary New bill button and it opens the form", async ({ page }) => {
    await page.goto("/");
    const big = page.getByTestId("list-new-bill");
    await expect(big).toBeVisible();
    const style = await big.evaluate((el) => ({ bg: getComputedStyle(el).backgroundColor, size: parseFloat(getComputedStyle(el).fontSize), h: el.getBoundingClientRect().height }));
    expect(style.bg).toBe("rgb(31, 79, 207)");
    expect(style.size).toBeGreaterThanOrEqual(17);
    expect(style.h).toBeGreaterThanOrEqual(44);
    await big.click();
    await expect(page).toHaveURL(/\/bills\/new$/);
    await expect(page.getByTestId("nav-new-bill")).toHaveAttribute("aria-current", "page");
  });

  test("language selector stays usable in the top bar", async ({ page }) => {
    await page.goto("/");
    await page.getByTestId("lang-select").selectOption("hi");
    await expect(page.getByTestId("nav-new-bill")).toContainText("नया बिल");
  });
});

test.describe("bills list is compact and scannable", () => {
  test("small rows, zebra striping, sticky header, record count and clear-filters", async ({ page, request }) => {
    const tag = String(Date.now()).slice(-6);
    await createSyntheticBill(request, tag);
    await createSyntheticBill(request, tag + "b");
    await page.goto("/");
    await page.getByTestId("search-q").fill(`Polish Farmer ${tag}`);
    await expect(page.getByTestId("bill-row")).toHaveCount(2);
    await expect(page.getByTestId("bill-count")).toHaveText("2 bills");
    await page.mouse.move(0, 0);

    const rows = page.getByTestId("bill-row");
    const height = await rows.first().evaluate((el) => el.getBoundingClientRect().height);
    expect(height).toBeLessThanOrEqual(44); // compact: many records fit on one screen
    const bg = (n: number) => rows.nth(n).locator("td").first().evaluate((el) => getComputedStyle(el).backgroundColor);
    expect(await bg(0)).toBe("rgb(255, 255, 255)");
    expect(await bg(1)).toBe("rgb(246, 248, 253)"); // --surface-alt on even rows
    expect(await page.getByTestId("bill-table").locator("th").first().evaluate((el) => getComputedStyle(el).position)).toBe("sticky");

    await expect(page.getByTestId("search-clear")).toBeEnabled();
    await page.getByTestId("search-clear").click();
    await expect(page.getByTestId("search-q")).toHaveValue("");
    await expect(page.getByTestId("search-clear")).toBeDisabled();
    await page.screenshot({ path: "test-results/ui-bills-list.png", fullPage: true });
  });

  test("hovering a row highlights it in pale indigo", async ({ page, request }) => {
    await createSyntheticBill(request, "hv" + String(Date.now()).slice(-5));
    await page.goto("/");
    const row = page.getByTestId("bill-row").first();
    await expect(row).toBeVisible();
    await row.hover();
    expect(await row.locator("td").first().evaluate((el) => getComputedStyle(el).backgroundColor)).toBe("rgb(239, 244, 253)");
  });
});

test.describe("masters pages", () => {
  test("tabs switch between master types and mark the current one", async ({ page }) => {
    await page.goto("/masters/farmers");
    const active = page.getByTestId("master-tab-farmers");
    await expect(active).toHaveAttribute("aria-current", "page");
    expect(await active.evaluate((el) => getComputedStyle(el).backgroundColor)).toBe("rgb(255, 255, 255)");
    await page.getByTestId("master-tab-villages").click();
    await expect(page).toHaveURL(/masters\/villages$/);
    await expect(page.getByTestId("master-tab-villages")).toHaveAttribute("aria-current", "page");
    await expect(page.getByTestId("master-tab-farmers")).not.toHaveAttribute("aria-current", "page");
    // The page heading names the selected master (not a generic "Masters").
    await expect(page.getByRole("heading", { name: "Villages", level: 1 })).toBeVisible();
    await page.screenshot({ path: "test-results/ui-masters-tabs.png", fullPage: true });
  });
});

test.describe("audit history", () => {
  test("a saved bill shows its history and the read-only explanation (English and Hindi)", async ({ page, request }) => {
    const { billId } = await createSyntheticBill(request, "au" + String(Date.now()).slice(-5));
    await page.goto(`/bills/${billId}`);
    await expect(page.getByTestId("history-row")).toHaveCount(1);
    await expect(page.getByTestId("history-row")).toContainText("Created");
    await expect(page.getByTestId("history-details")).toContainText("Lots: 1");
    await expect(page.getByTestId("history-details")).toContainText("Calculation status: Calculated");
    await expect(page.getByTestId("history-details")).not.toContainText("calculationStatus"); // no raw field names
    await expect(page.getByTestId("readonly-note")).toContainText("cannot be edited or cancelled yet");
    // No edit / cancel / delete control exists on a saved bill.
    await expect(page.getByRole("button", { name: /edit|cancel|delete|बदलें|रद्द|हटाएँ/i })).toHaveCount(0);
    // the action word is never split across lines
    const actionCell = page.getByTestId("history-row").locator("td").nth(1);
    expect(await actionCell.evaluate((el) => getComputedStyle(el).whiteSpace)).toBe("nowrap");
    expect(await actionCell.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
    await page.screenshot({ path: "test-results/ui-bill-view.png", fullPage: true });

    await page.getByTestId("lang-select").selectOption("hi");
    await expect(page.getByTestId("history-card")).toContainText("इतिहास");
    await expect(page.getByTestId("history-row")).toContainText("बनाया गया");
    await expect(page.getByTestId("history-details")).toContainText("लॉट: 1");
    await expect(page.getByTestId("history-details")).toContainText("गणना की गई");
    await expect(page.getByTestId("history-details")).not.toContainText("CALCULATED");
    await expect(page.getByTestId("readonly-note")).toContainText("अभी बदले या रद्द नहीं");
  });

  test("master changes are recorded; the history API is read-only", async ({ request }) => {
    const tag = String(Date.now()).slice(-6);
    const f = await (await request.post(`${API}/masters/farmers`, { data: { name: `Hist ${tag}` } })).json();
    await request.put(`${API}/masters/farmers/${f.id}`, { data: { name: `Hist ${tag} renamed` } });
    const events = await (await request.get(`${API}/audit?entityType=FARMER&entityId=${f.id}`)).json();
    expect(events.map((e: { action: string }) => e.action)).toEqual(["UPDATED", "CREATED"]); // newest first
    const del = await request.delete(`${API}/audit?entityType=FARMER&entityId=${f.id}`);
    expect(del.status()).toBeGreaterThanOrEqual(400);
    expect((await (await request.get(`${API}/audit?entityType=FARMER&entityId=${f.id}`)).json()).length).toBe(2);
  });
});

test.describe("Hindi layout does not overflow", () => {
  for (const width of [1280, 1024, 800]) {
    test(`no horizontal page scroll at ${width}px`, async ({ page, request }) => {
      await page.setViewportSize({ width, height: 800 });
      const { billId } = await createSyntheticBill(request, "ov" + String(Date.now()).slice(-5));
      await page.goto("/");
      await page.getByTestId("lang-select").selectOption("hi");
      for (const path of ["/", "/bills/new", "/masters/farmers", `/bills/${billId}`, `/bills/${billId}/print`]) {
        await page.goto(path);
        await expect(page.getByTestId("demo-banner")).toContainText("डेमो");
        await page.waitForLoadState("networkidle");
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
        expect(overflow, `${path} overflows by ${overflow}px at ${width}px`).toBeLessThanOrEqual(1);
      }
    });
  }
});
