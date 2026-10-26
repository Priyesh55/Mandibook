import { expect, test } from "./fixtures";

// Master lists are paged (50 per page, sorted by name then id). Synthetic data only; needs the backend.
const API = "/api/v1";
const tag = `PgTest${String(Date.now()).slice(-6)}`;

test.describe("master list paging", () => {
  let seeded = false;
  test.beforeEach(async ({ request }) => {
    if (seeded) return;
    seeded = true;
    for (let i = 1; i <= 55; i++) {
      const res = await request.post(`${API}/masters/villages`, { data: { name: `${tag} V${String(i).padStart(2, "0")}` } });
      expect(res.ok()).toBeTruthy();
    }
  });

  test("55 matching records show as two pages with a count, and Previous/Next move between them", async ({ page }) => {
    await page.goto("/masters/villages");
    await page.getByTestId("master-search").fill(tag);
    await expect(page.getByTestId("master-count")).toHaveText("55 records");
    await expect(page.getByTestId("master-row")).toHaveCount(50);
    await expect(page.getByTestId("master-page")).toHaveText("1 / 2");
    await expect(page.getByTestId("master-prev")).toBeDisabled();
    await expect(page.getByTestId("master-row").first()).toContainText(`${tag} V01`);

    await page.getByTestId("master-next").click();
    await expect(page.getByTestId("master-page")).toHaveText("2 / 2");
    await expect(page.getByTestId("master-row")).toHaveCount(5);
    await expect(page.getByTestId("master-row").first()).toContainText(`${tag} V51`); // continues where page 1 ended
    await expect(page.getByTestId("master-next")).toBeDisabled();

    await page.getByTestId("master-prev").click();
    await expect(page.getByTestId("master-page")).toHaveText("1 / 2");
    await expect(page.getByTestId("master-row")).toHaveCount(50);
  });

  test("changing the search goes back to the first page; the Hindi count label is translated", async ({ page }) => {
    await page.goto("/masters/villages");
    await page.getByTestId("master-search").fill(tag);
    await page.getByTestId("master-next").click();
    await expect(page.getByTestId("master-page")).toHaveText("2 / 2");
    await page.getByTestId("master-search").fill(`${tag} V0`); // V01..V09
    await expect(page.getByTestId("master-page")).toHaveText("1 / 1");
    await expect(page.getByTestId("master-count")).toHaveText("9 records");
    await page.getByTestId("lang-select").selectOption("hi");
    await expect(page.getByTestId("master-count")).toHaveText("9 रिकॉर्ड");
  });

  test("switching master type starts from a clean search on the first page", async ({ page }) => {
    await page.goto("/masters/villages");
    await page.getByTestId("master-search").fill(tag);
    await page.getByTestId("master-next").click();
    await page.getByTestId("master-tab-buyers").click();
    await expect(page).toHaveURL(/masters\/buyers$/);
    await expect(page.getByTestId("master-search")).toHaveValue("");
    await expect(page.getByTestId("master-page")).toHaveText(/^1 \/ /);
  });

  test("pick-lists still receive every active record through the existing list endpoint", async ({ request }) => {
    const res = await request.get(`${API}/masters/villages?activeOnly=true&q=${tag}`);
    expect(res.ok()).toBeTruthy();
    expect((await res.json()).length).toBe(55);
    expect(res.headers()["x-result-truncated"]).toBeUndefined();
  });
});
