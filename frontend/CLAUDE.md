# Frontend notes (Mandi Book)

Loaded when working under `frontend/`. Project-wide rules are in the root `CLAUDE.md`; the Next.js warning is in `AGENTS.md`.

## UI
White-and-blue theme from CSS tokens in `frontend/src/app/globals.css` (see `docs/UI_GUIDE.md`); `src/lib/theme.test.ts` enforces WCAG AA contrast. Status colours only for status. Lot/expense grids are keyboard-driven (Enter / Shift+Enter). The printed bill is a **draft** layout (`docs/business-rules.md` BR-107).

## Sign-in and session
- Frontend: `SessionProvider`/`AuthGate` (src/lib/session.tsx, components/AuthGate.tsx) mirror the SERVER session in memory only; no password, id or token is stored by the page. `lib/api.ts` adds the CSRF token (cookie, retried once after `CSRF_REJECTED`) and `X-Mandi-Firm-Check` (the firm of the ready state) and tells the session layer about 401 / stale firm / no access. The page under an expired session stays mounted (inert) beneath a sign-in dialog; the app is re-created when the firm changes.

## Bill entry
- Farmer, village and buyer selection use `MasterPicker` (paged search); in Playwright use `pickMaster()` from `e2e/support.ts`.
- The bill form (`components/BillForm.tsx`) is one draft state, the hook `components/bill/useBillDesk.tsx`, laid out by `components/bill/LotCardsLayout.tsx` (the lot cards layout; the other layouts that were compared were removed). The sections it uses are in `components/bill/`; browser helpers are in `e2e/support.ts` (`fillLot`, `addPurchaser`, `addExpense`, `chooseLot`).
- Masters are added on the bill in dialogs (`MasterDialog`, village, farmer, purchaser) that reuse the masters-page form (`MasterForm`) and API. Dialogs are rendered OUTSIDE the bill `<form>` (a nested form would submit the bill). Cancel, Escape and failures leave the draft untouched.
- Design tokens are a blue-indigo ramp (`--ind-*`); layout max width 1440 px. Current screenshot evidence lives in `docs/evidence/submission-2026-10-30/` (the 2026-10-26 set is historical and shows a removed layout).
