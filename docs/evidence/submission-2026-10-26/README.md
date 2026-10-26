# Curated browser evidence (2026-10-26)

Screenshots of the Mandi Book bill journey, taken by `frontend/e2e/evidence-screenshots.spec.ts` in a real browser (Chrome through Playwright) against a disposable stack: its own new PostgreSQL container, the real backend and a production build of the web application. All names, firms and numbers are synthetic ("E2E Firm A …", "Ev Farmer …", "Demo …"). Each image was opened and checked by eye; only images that are complete and unclipped are listed. Figures can be verified by hand: gross 18000 + 22500 = 40500, expenses 70 + 100 + 1000 = 1170, net 39330.

| File | What it shows |
|---|---|
| `01-village-first-farmer-list-1440.png` | The farmer is offered only after the village is chosen, and only that village's farmers |
| `02-master-dialog-1440.png` | A master (village) is added in a dialog on top of the bill; the draft stays untouched |
| `03-lots-and-purchasers-1440.png` | Two lots, a purchaser allocation with calculated value and 5 % commission, the automatic freight expense |
| `04-review-ready-to-save-1440.png` | Per-piece expense (35 pieces at a rate of 2), a typed expense, totals, "Ready to save" |
| `05-saved-bill-1440.png` | The saved bill with its calculation status and number |
| `06-bill-list-1440.png` | Search and list, with the calculation status |
| `07-print-english-1440.png` | The printable farmer bill, English |
| `08-print-hindi-1440.png` | The printable farmer bill and the interface in Hindi |
| `09-print-english-1024.png` | The same print page at 1024 px |
| `10-new-bill-empty-390.png` | The empty new bill on a 390 px phone screen, no sideways scrolling |
| `11-lots-and-purchasers-390.png` | A filled bill on a 390 px phone screen |
| `12-print-english-390.png` | The print sheet on a 390 px phone, after the layout fix: no overlapping headers, every table inside the sheet |
| `13-print-hindi-390.png` | The same in Hindi |

## Known limitations of this evidence
- A **full-page** capture of a page taller than the window shows the fixed navigation sidebar stretched over the content. This is a capture artifact: the live sidebar measured 68 px with `data-expanded=false`, and the viewport-only capture of the same page is clean. The images above are viewport captures or pages that fit the window.
- The on-screen print sheet on a phone is small: the lots table uses 10.5 to 11.5 px text so that it fits 390 px. The printed A4 layout is a draft (`docs/business-rules.md` BR-107) and real printer output has not been tested.
- No 1280 px image was selected (the 1440 and 1024 images cover the desktop range).
- Rates shown are demonstration values, not a statement of any firm's rates.
