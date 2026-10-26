import { expect, test } from "./fixtures";

// Mandi Book menu, breadcrumbs and branding. These checks only look at the navigation itself, so they
// do not depend on the backend being up (pages may show a loading or error state underneath).

test.describe("branding", () => {
  test("product name is Mandi Book in the bar, the tab title and in Hindi", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByTestId("brand")).toHaveText("Mandi Book");
    await expect(page).toHaveTitle("Aadat bills | Mandi Book");
    await page.goto("/bills/new");
    await expect(page).toHaveTitle("New Aadat bill | Mandi Book");
    await page.goto("/masters/villages");
    await expect(page).toHaveTitle("Villages | Mandi Book");
    await page.getByTestId("lang-select").selectOption("hi");
    await expect(page.getByTestId("brand")).toHaveText("मंडी बुक");
    await expect(page.getByTestId("nav-group-farmers-buyers")).toContainText("किसान और खरीदार");
  });
});

test.describe("desktop menu", () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test("groups open as dropdowns, links navigate, and the current page is marked", async ({ page }) => {
    await page.goto("/");
    const group = page.getByTestId("nav-group-farmers-buyers");
    const panel = page.getByTestId("nav-panel-farmers-buyers");
    await expect(panel).toBeHidden();
    await expect(group).toHaveAttribute("aria-expanded", "false");
    await group.click();
    await expect(panel).toBeVisible();
    await expect(group).toHaveAttribute("aria-expanded", "true");

    await page.getByTestId("nav-item-farmers").click();
    await expect(page).toHaveURL(/\/masters\/farmers$/);
    await expect(panel).toBeHidden(); // navigating closes the menu
    await expect(page.getByTestId("nav-group-farmers-buyers").locator("xpath=..")).toHaveAttribute("data-active", "true");
    await expect(page.getByTestId("nav-group-mandi-book").locator("xpath=..")).toHaveAttribute("data-active", "false");

    await page.getByTestId("nav-group-farmers-buyers").click();
    await expect(page.getByTestId("nav-item-farmers")).toHaveAttribute("aria-current", "page");
    await expect(page.getByTestId("nav-item-buyers")).not.toHaveAttribute("aria-current", "page");

    const crumbs = page.getByTestId("breadcrumbs");
    await expect(crumbs).toContainText("Farmers and buyers");
    await expect(crumbs.locator("[aria-current=page]")).toHaveText("Farmers");
  });

  test("New bill and Aadat bills are not both marked current", async ({ page }) => {
    await page.goto("/bills/new");
    await page.getByTestId("nav-group-mandi-book").click();
    await expect(page.getByTestId("nav-item-new-bill")).toHaveAttribute("aria-current", "page");
    await expect(page.getByTestId("nav-item-bills")).not.toHaveAttribute("aria-current", "page");
    await expect(page.getByTestId("breadcrumbs").locator("li")).toHaveText(["Mandi Book", "Aadat bills", "New bill"]);
    await expect(page.getByTestId("nav-new-bill")).toHaveAttribute("aria-current", "page");
  });

  test("unavailable features are plain labels with a visible 'Not available yet' note, never links", async ({ page }) => {
    await page.goto("/");
    for (const [group, item] of [
      ["accounts", "ledger"],
      ["accounts", "trial-balance"],
      ["receipts-payments", "receipt"],
      ["receipts-payments", "payment"],
      ["mandi-book", "rate-difference"],
      ["firm-year", "year-setup"],
    ]) {
      const btn = page.getByTestId(`nav-group-${group}`);
      if ((await btn.getAttribute("aria-expanded")) !== "true") await btn.click(); // a second click would close it
      const el = page.getByTestId(`nav-item-${item}`);
      await expect(el).toBeVisible();
      await expect(el).toContainText("Not available yet");
      await expect(el).toHaveAttribute("aria-disabled", "true");
      expect(await el.evaluate((e) => e.tagName)).not.toBe("A");
      expect(await el.getAttribute("href")).toBeNull();
    }
  });

  test("every link in the menu opens a real page", async ({ page }) => {
    await page.goto("/");
    const hrefs: string[] = [];
    for (const g of ["mandi-book", "farmers-buyers", "masters"]) {
      await page.getByTestId(`nav-group-${g}`).click();
      hrefs.push(...(await page.getByTestId(`nav-panel-${g}`).locator("a").evaluateAll((as) => as.map((a) => (a as HTMLAnchorElement).getAttribute("href") ?? ""))));
    }
    expect(hrefs.length).toBeGreaterThanOrEqual(7);
    for (const href of hrefs) {
      const res = await page.goto(href);
      expect(res?.status(), href).toBeLessThan(400);
      await expect(page.getByTestId("brand")).toBeVisible();
    }
  });

  test("only one dropdown is open at a time; Escape and an outside click close it", async ({ page }) => {
    await page.goto("/");
    await page.getByTestId("nav-group-masters").click();
    await expect(page.getByTestId("nav-panel-masters")).toBeVisible();
    await page.getByTestId("nav-group-accounts").click();
    await expect(page.getByTestId("nav-panel-accounts")).toBeVisible();
    await expect(page.getByTestId("nav-panel-masters")).toBeHidden();

    await page.keyboard.press("Escape");
    await expect(page.getByTestId("nav-panel-accounts")).toBeHidden();
    await expect(page.getByTestId("nav-group-accounts")).toBeFocused(); // focus returns to the menu button

    await page.getByTestId("nav-group-masters").click();
    await expect(page.getByTestId("nav-panel-masters")).toBeVisible();
    await page.getByTestId("sidebar-backdrop").click({ position: { x: 900, y: 300 } });   // beside the opened sidebar: the backdrop folds it
    await expect(page.getByTestId("nav-panel-masters")).toBeHidden();
  });

  test("the menu is usable by keyboard alone", async ({ page }) => {
    await page.goto("/");
    await page.getByTestId("nav-group-masters").focus();
    await page.keyboard.press("Enter");
    await expect(page.getByTestId("nav-panel-masters")).toBeVisible();
    await page.keyboard.press("Tab");
    await expect(page.getByTestId("nav-item-villages")).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/masters\/villages$/);
  });

  test("the menu toggle is hidden on a wide screen and the whole bar is visible", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByTestId("menu-toggle")).toBeHidden();
    await expect(page.getByTestId("main-menu")).toBeVisible();
    await expect(page.getByTestId("nav-new-bill")).toBeVisible();
  });
});

test.describe("the sidebar: a rail of symbols that opens to show the names", () => {
  test.use({ viewport: { width: 1920, height: 1080 } });

  test("only symbols show until one is pressed; the pressed group lists its pages below it; one group at a time", async ({ page }) => {
    await page.goto("/");
    const side = page.getByTestId("sidebar");
    const width = async () => (await side.boundingBox())!.width;
    expect(await width()).toBeLessThanOrEqual(80);                                           // the rail
    expect(await page.getByTestId("nav-group-masters").locator(".nav-label").evaluate((el) => el.getBoundingClientRect().width)).toBeLessThanOrEqual(2);   // names are not shown
    await expect(page.getByTestId("nav-group-masters").locator("svg")).toBeVisible();        // the symbol is
    await expect(page.getByTestId("nav-group-masters")).toHaveAttribute("title", "Masters");  // and says its name when pointed at

    await page.getByTestId("nav-group-masters").click();                                      // a symbol opens the rail ...
    await expect.poll(width).toBeGreaterThanOrEqual(280);
    await expect(page.getByTestId("nav-group-masters").locator(".nav-label")).toBeVisible();  // ... with every name ...
    await expect(page.getByTestId("nav-group-accounts").locator(".nav-label")).toBeVisible();
    await expect(page.getByTestId("nav-panel-masters")).toBeVisible();                        // ... and the pages of that group below it
    await expect(page.getByTestId("nav-item-villages")).toBeVisible();
    await page.getByTestId("nav-group-accounts").click();                                     // another group folds the first away
    await expect(page.getByTestId("nav-panel-accounts")).toBeVisible();
    await expect(page.getByTestId("nav-panel-masters")).toBeHidden();
    await page.getByTestId("nav-group-accounts").click();                                     // pressing the open group closes its list
    await expect(page.getByTestId("nav-panel-accounts")).toBeHidden();

    await page.getByTestId("nav-group-masters").click();
    await page.getByTestId("nav-item-items").click();                                         // choosing a page folds the rail back to symbols
    await expect(page).toHaveURL(/\/masters\/items$/);
    await expect.poll(width).toBeLessThanOrEqual(80);
    await page.getByTestId("sidebar-toggle").click();                                         // the toggle opens it too, at the current group
    await expect.poll(width).toBeGreaterThanOrEqual(280);
    await expect(page.getByTestId("nav-panel-masters")).toBeVisible();
    await page.getByTestId("sidebar-toggle").click();
    await expect.poll(width).toBeLessThanOrEqual(80);
  });

  test("the page does not move when the menu opens (it opens over the page, with a backdrop)", async ({ page }) => {
    await page.goto("/bills/new");
    const before = (await page.getByTestId("section-lots").boundingBox())!;
    await page.getByTestId("nav-group-farmers-buyers").click();
    await expect(page.getByTestId("sidebar-backdrop")).toBeVisible();
    const after = (await page.getByTestId("section-lots").boundingBox())!;
    expect(Math.abs(after.x - before.x) + Math.abs(after.width - before.width)).toBeLessThan(2);
    await page.getByTestId("sidebar-backdrop").click({ position: { x: 1000, y: 500 } });
    await expect(page.getByTestId("sidebar-backdrop")).toHaveCount(0);
  });

  test("buttons have a focus ring that touches the button (no gap) and room between neighbours", async ({ page }) => {
    await page.goto("/bills/new");
    await page.getByTestId("bill-save").focus();
    await page.keyboard.press("Shift+Tab");
    await page.keyboard.press("Tab");
    const ring = await page.getByTestId("bill-save").evaluate((el) => { const s = getComputedStyle(el); return { offset: s.outlineOffset, style: s.outlineStyle }; });
    expect(ring).toEqual({ offset: "0px", style: "solid" });
    const cancel = (await page.getByTestId("bill-cancel").boundingBox())!;
    const save = (await page.getByTestId("bill-save").boundingBox())!;
    expect(save.x - (cancel.x + cancel.width), "space between Cancel and Save bill").toBeGreaterThanOrEqual(12);
  });

  test("the reference screen is 1920 x 1080", async ({ page }) => {
    expect(page.viewportSize()).toEqual({ width: 1920, height: 1080 });
  });
});

test.describe("small screen menu", () => {
  test.use({ viewport: { width: 390, height: 800 } });

  test("menu is collapsed behind a Menu button, keeps New bill reachable and does not scroll sideways", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByTestId("main-menu")).toBeHidden();
    await expect(page.getByTestId("nav-new-bill")).toBeVisible();
    const toggle = page.getByTestId("menu-toggle");
    await expect(toggle).toBeVisible();
    await expect(toggle).toHaveAttribute("aria-expanded", "false");

    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-expanded", "true");
    await expect(page.getByTestId("main-menu")).toBeVisible();
    await page.getByTestId("nav-group-masters").click();
    await expect(page.getByTestId("nav-item-villages")).toBeVisible();
    const noSideScroll = await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
    expect(noSideScroll).toBe(true);

    await page.getByTestId("nav-item-villages").click();
    await expect(page).toHaveURL(/\/masters\/villages$/);
    await expect(page.getByTestId("main-menu")).toBeHidden(); // menu folds away after choosing a page
    await expect(toggle).toHaveAttribute("aria-expanded", "false");
    await expect(page.getByTestId("breadcrumbs")).toContainText("Villages");
    await page.screenshot({ path: "test-results/nav-mobile.png" });
  });
});

test.describe("headings and titles follow the page and the language", () => {
  const masters: [string, string, string][] = [
    ["farmers", "Farmers", "किसान"],
    ["buyers", "Buyers", "खरीदार"],
    ["villages", "Villages", "गाँव"],
    ["items", "Items", "माल"],
    ["expense-heads", "Expense heads", "खर्च के मद"],
  ];

  for (const [type, en, hi] of masters) {
    test(`master page ${type}: heading, breadcrumb and tab title name the selected master`, async ({ page }) => {
      await page.goto(`/masters/${type}`);
      await expect(page.getByRole("heading", { level: 1 })).toHaveText(en);
      await expect(page.getByRole("heading", { name: "Masters" })).toHaveCount(0);
      await expect(page.getByTestId("breadcrumbs").locator("[aria-current=page]")).toHaveText(en);
      await expect(page).toHaveTitle(`${en} | Mandi Book`);
      await page.getByTestId("lang-select").selectOption("hi");
      await expect(page.getByRole("heading", { level: 1 })).toHaveText(hi);
      await expect(page.getByTestId("breadcrumbs").locator("[aria-current=page]")).toHaveText(hi);
      await expect(page).toHaveTitle(`${hi} | मंडी बुक`);
      await expect(page.locator("html")).toHaveAttribute("lang", "hi");
    });
  }

  test("the Hindi tab title survives client-side navigation and switching back restores English", async ({ page }) => {
    await page.goto("/");
    await page.getByTestId("lang-select").selectOption("hi");
    await expect(page).toHaveTitle("आढ़त बिल | मंडी बुक");
    await page.getByTestId("nav-new-bill").click();
    await expect(page).toHaveURL(/\/bills\/new$/);
    await expect(page).toHaveTitle("नया आढ़त बिल | मंडी बुक");
    await page.getByTestId("nav-group-farmers-buyers").click();
    await page.getByTestId("nav-item-buyers").click();
    await expect(page).toHaveTitle("खरीदार | मंडी बुक");
    await page.getByTestId("lang-select").selectOption("en");
    await expect(page).toHaveTitle("Buyers | Mandi Book");
    await expect(page.locator("html")).toHaveAttribute("lang", "en");
  });

  test("menu labels and the unavailable note are in Hindi when Hindi is selected", async ({ page }) => {
    await page.goto("/");
    await page.getByTestId("lang-select").selectOption("hi");
    await page.getByTestId("nav-group-accounts").click();
    await expect(page.getByTestId("nav-item-ledger")).toContainText("खाता बही");
    await expect(page.getByTestId("nav-item-ledger")).toContainText("अभी उपलब्ध नहीं");
    await expect(page.getByTestId("main-menu")).toHaveAttribute("aria-label", "मुख्य मेनू");
  });
});
