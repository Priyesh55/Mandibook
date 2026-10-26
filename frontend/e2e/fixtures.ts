import { test as base } from "@playwright/test";
import { apiSignIn, authedRequest, fixtures } from "./support";

export { expect } from "@playwright/test";

/**
 * The tests of the existing features (navigation, masters, bills, history) run as a real, signed-in owner of one firm: the
 * `page` is already signed in through the real endpoints (cookie jar of the browser context) and `request` carries the same
 * session. Sign-in itself, firm selection, employees and every refusal are tested in auth.spec.ts without this shortcut.
 */
export const test = base.extend({
  page: async ({ page, context }, provide) => {
    const f = fixtures();
    await apiSignIn(context, f.ownerA.email, f.password);
    await provide(page);
  },
  request: async ({ context, page }, provide) => {
    void page; // signed in first
    await provide(authedRequest(context, fixtures().ownerA.firmId));
  },
});
