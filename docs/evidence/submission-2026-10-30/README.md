# Current browser evidence (2026-10-30)

This is the **current-submission screenshot set**. It replaces `docs/evidence/submission-2026-10-26/`, whose images show the removed "Classic desk" bill layout with a 1-to-6 layout switcher and a menu rail that still had the removed "Layouts" group (that folder is kept as historical evidence of commit `b057a7b`, see its README).

**Where these images come from.** Images 01 to 13 were captured by `frontend/e2e/evidence-screenshots.spec.ts` (the bill journey) during the full browser run of 2026-10-30 that passed 99 of 99 (`docs/QA_EVIDENCE.md` section 1c), on a disposable stack (own database container, backend, web server), in Chrome through Playwright, with synthetic data only. They are the files that run produced, copied here unchanged; each one was opened and checked for the current layout and for synthetic data before it was selected. The code fingerprint of that run was `a84637de4bfd747df647f5be50c34ca3eb652dce7bd4f0b3fd696f5b1cb82f25`. Images 14 and 15 (sign-in) were captured afterwards from the same already-built frontend, served on the same kind of disposable stack, with a one-off Playwright script that is not part of the repository; nothing was typed on the page.

| File | What it shows | Source capture of the run |
|---|---|---|
| `01-village-first-farmer-list-1440.png` | The farmer is offered only after the village is chosen, and only that village's farmers | `1440-02-village-first-farmer-list` |
| `02-master-dialog-1440.png` | A village is added in a dialog on top of the bill; the draft stays untouched | `1440-03-master-dialog` |
| `03-lots-and-purchasers-1440.png` | The lot cards layout: two lots, each with its own parties, calculated values and 5 % commission, scrolled to the lots (the header stays at the top) | `1440-04-lots-and-purchasers-viewport` |
| `04-review-ready-to-save-1440.png` | A configured per-piece expense (35 pieces at a rate of 2 gives 70), a typed expense, the automatic freight expense, totals, "Ready to save" | `1440-06-review-and-save-viewport` |
| `05-saved-bill-1440.png` | The saved bill with its calculation status and number | `1440-07-saved-bill-viewport` |
| `06-bill-list-1440.png` | Search and list with the calculation status (all bills are synthetic bills created by the browser tests) | `1440-08-bill-list` |
| `07-print-english-1440.png` | The printable farmer bill, English | `1440-10-print-english-viewport` |
| `08-print-hindi-1440.png` | The printable farmer bill and the interface in Hindi | `1440-11-print-hindi-viewport` |
| `09-print-english-1024.png` | The print page at 1024 px | `1024-10-print-english-viewport` |
| `10-new-bill-empty-390.png` | The empty new bill on a 390 px phone screen | `390-01-new-bill-empty` |
| `11-lots-and-purchasers-390.png` | A filled bill on a 390 px phone screen (second lot and its party card) | `390-04-lots-and-purchasers-viewport` |
| `12-print-english-390.png` | The print sheet on a 390 px phone | `390-10-print-english-viewport` |
| `13-print-hindi-390.png` | The same in Hindi | `390-11-print-hindi-viewport` |
| `14-sign-in-1440.png` | The current sign-in page, 1440 px: split screen without breadcrumb | captured separately (see above) |
| `15-sign-in-390.png` | The current sign-in page, 390 px | captured separately (see above) |

## Known limitations of this evidence
- The pages in the journey come from separate bills of the run, so bill numbers differ between images (for example 8 at 1440 px, 10 at 1024 px, 11 at 390 px). The figures on each image are consistent with that image's own bill.
- The 390 px lot-cards image shows the second lot's party card after the page scrolled; the first lot is above the visible area.
- The on-screen print sheet on a phone is small, and the printed A4 layout is still a draft (`docs/business-rules.md` BR-107). Real printer output has not been tested.
- Hindi labels and the sign-in headline were written with AI help and have not had a native-speaker review.
- Rates shown (for example 2 per piece) are demonstration values configured by the tests, not any firm's rates.
- The sign-in design includes an "Accounting" chip; ledgers and accounting postings are not built (`docs/UNRESOLVED.md`).
- No screenshot of the per-lot rounding limitation exists; it is described in `docs/business-rules.md` ("Known implementation limitations").
