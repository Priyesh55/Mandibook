import { expect, test } from "./fixtures";

// The on-screen print sheet must not have overlapping or clipped table cells on a phone (390 px) or on a desktop screen, in English
// and in Hindi. A cell "overflows" when its text is wider than the cell, which is what makes neighbouring headers run into each other.

const API = "/api/v1";

async function saveBill(request: import("@playwright/test").APIRequestContext): Promise<number> {
  const itemId = ((await (await request.get(`${API}/masters/items?q=Onion`)).json()) as { id: number }[])[0].id;
  const potatoId = ((await (await request.get(`${API}/masters/items?q=Potato`)).json()) as { id: number }[])[0].id;
  const farmerId = ((await (await request.get(`${API}/masters/farmers?q=Demo Farmer 1`)).json()) as { id: number }[])[0].id;
  const ratedHead = ((await (await request.get(`${API}/masters/expense-heads?q=Demo Rated Expense`)).json()) as { id: number }[])[0].id;
  const typedHead = ((await (await request.get(`${API}/masters/expense-heads?q=Demo Expense A`)).json()) as { id: number }[])[0].id;
  const body = {
    billDate: "2026-10-26", farmerId, vehicleNo: "MH-12-AB-1234", transport: "Synthetic Carrier", bhadaPaid: true, bhadaAmount: 1000, billType: "CREDIT",
    lots: [
      { itemId, pieces: 20, weight: 1000, rate: 1800, purchasers: [] },
      { itemId: potatoId, pieces: 15, weight: 1500, rate: 1500, purchasers: [] },
    ],
    expenses: [{ expenseHeadId: ratedHead }, { expenseHeadId: typedHead, amount: 100 }],
  };
  const r = await request.post(`${API}/aadat/bills`, { data: body, headers: { "Idempotency-Key": `pm-${Date.now()}` } });
  expect(r.status(), await r.text()).toBe(201);
  return ((await r.json()) as { id: number }).id;
}

/** Cells of the print sheet whose text does not fit inside them, with the facts needed to understand why. */
async function overflowingCells(page: import("@playwright/test").Page) {
  return page.evaluate(() => {
    const out: string[] = [];
    for (const table of document.querySelectorAll<HTMLElement>("[data-testid=print-sheet] table")) {
      const tag = table.getAttribute("data-testid") ?? table.className ?? "table";
      for (const cell of table.querySelectorAll<HTMLElement>("th, td")) {
        const range = document.createRange();
        range.selectNodeContents(cell);
        const text = range.getBoundingClientRect();
        const box = cell.getBoundingClientRect();
        if (box.width > 0 && text.width > 0 && text.right > box.right + 1) {
          const cs = getComputedStyle(cell);
          out.push(`${tag}: "${(cell.textContent ?? "").trim().slice(0, 24)}" text ${Math.round(text.width)}px in cell ${Math.round(box.width)}px (white-space ${cs.whiteSpace}, wrap ${cs.overflowWrap}, word-break ${cs.wordBreak}, table-layout ${getComputedStyle(table).tableLayout})`);
        }
      }
    }
    return out;
  });
}

for (const [label, width, height] of [["390 px phone", 390, 844], ["1440 px desktop", 1440, 900]] as const) {
  for (const lang of ["en", "hi"] as const) {
    test(`the print sheet has no overflowing or overlapping cells at ${label} in ${lang === "en" ? "English" : "Hindi"}`, async ({ page, request }) => {
      const id = await saveBill(request);
      await page.setViewportSize({ width, height });
      await page.goto(`/bills/${id}/print`);
      if (lang === "hi") await page.getByTestId("lang-select").selectOption("hi");
      await expect(page.getByTestId("print-title")).toBeVisible();
      await page.waitForLoadState("networkidle");
      if (process.env.E2E_EVIDENCE_DIR) {
        // optional evidence capture (viewport-only at desktop width: a full-page capture stretches the fixed sidebar)
        await page.mouse.move(width - 3, height - 3);
        await page.screenshot({ path: `${process.env.E2E_EVIDENCE_DIR}/print-${width}-${lang}.png`, fullPage: width < 800 });
      }
      const bad = await overflowingCells(page);
      expect(bad, `cells whose text is wider than the cell:\n${bad.join("\n")}`).toEqual([]);
      // every table must stay inside the bordered sheet, and no header may be broken letter by letter (a one-character line)
      const outside = await page.evaluate(() => {
        const sheet = document.querySelector<HTMLElement>("[data-testid=print-sheet]")!;
        const s = sheet.getBoundingClientRect();
        const cs = getComputedStyle(sheet);
        const right = s.right - parseFloat(cs.paddingRight) - parseFloat(cs.borderRightWidth);
        const out: string[] = [];
        for (const t of sheet.querySelectorAll<HTMLElement>("table")) {
          const r = t.getBoundingClientRect();
          if (r.right > right + 1 || r.left < s.left) out.push(`${t.getAttribute("data-testid") ?? t.className}: right edge ${Math.round(r.right)} beyond the sheet's content edge ${Math.round(right)}`);
        }
        for (const th of sheet.querySelectorAll<HTMLElement>("th")) {
          const text = (th.textContent ?? "").trim();
          const range = document.createRange();
          range.selectNodeContents(th);
          const lines = new Set([...range.getClientRects()].map((r) => Math.round(r.top))).size;   // real text lines of this header
          if (text.length >= 4 && !/s/.test(text) && lines >= text.length - 1) out.push(`header "${text}" is broken letter by letter`);
        }
        return out;
      });
      expect(outside, outside.join("\n")).toEqual([]);
      const page_overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      expect(page_overflow, "the page must not scroll sideways").toBeLessThanOrEqual(1);
    });
  }
}
