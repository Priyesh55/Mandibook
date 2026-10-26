import { expect, test } from "./fixtures";

// Regression test: an EMPTY new bill shows the "lots without a value" status chip, which once made the 390 px page 177 px wider
// than the window (the chip could not wrap). The page must fit the window once it has settled, empty and with the village list open.
test.use({ viewport: { width: 390, height: 844 } });

test("the empty new bill fits a 390 px screen without sideways scrolling", async ({ page }) => {
  await page.goto("/bills/new");
  await page.waitForLoadState("networkidle");
  await expect(page.getByTestId("lots-without-value")).toBeVisible();
  const overflow = () => page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(await overflow(), "empty bill overflows").toBeLessThanOrEqual(1);
  await page.getByTestId("bill-village").click();
  expect(await overflow(), "empty bill with the village list open overflows").toBeLessThanOrEqual(1);
});
