# UI Guide (white-and-blue theme)

## Design rules
- White page, one restrained blue for primary actions, headings and the active tab; pale blue for hover, alternating lots, current row and table headers.
- Status colours only for status: **green** success, **amber** warning/demo banner, **red** errors and destructive actions. The demonstration-calculation notice is red because it warns that figures are not real.
- Light theme only (the earlier dark mode was removed for consistency). Printed bill stays black on white for paper.
- All colours are CSS variables at the top of `frontend/src/app/globals.css`. `src/lib/theme.test.ts` reads that file and fails if any text/background pair drops below WCAG AA (4.5:1) or a stray colour appears.

## Tokens
| Token | Value | Use |
|---|---|---|
| `--blue` / `--blue-hover` | #1d4f91 / #163f75 | primary button, links, headings |
| `--blue-pale` / `--blue-pale-strong` | #eaf2fb / #d6e6f8 | alternating lots, table headers, current row |
| `--surface-alt` | #f5f8fc | hover |
| `--green` on `--green-pale` | #17683f / #e6f5ec | success |
| `--amber` on `--amber-pale` | #7a4b00 / #fff4d6 | warnings, demo banner |
| `--red` on `--red-pale`, `--red-dark` | #b42318 / #fdecea, #8f1b12 | errors, remove, demo notice |
| `--text`, `--muted` | #17212b, #4b5b6b | body, secondary |

## Components
Buttons (primary, secondary, danger, solid-danger, small) · inputs/selects with blue hover/focus and a 3px focus ring · tables with pale-blue header · alerts (`.notice`, `.error`, `.success`) · badges · confirm dialog (`ConfirmDialog`, native `<dialog>`; Cancel focused first, Esc cancels).

## Lot and expense entry
- Each lot is a row: number, item, pieces, weight, rate, buyer, remove. Alternate rows are pale blue; the row being edited is darker blue with a left marker.
- **Keyboard:** `Enter` = next field · `Shift+Enter` = previous field · `Enter` in the last field of a started row adds a row and moves the cursor into it. Enter in these grids never saves the bill. The same works for expense rows.
- Remove asks for confirmation only when the row has data. The last remaining lot cannot be removed.
- Deactivating a master record asks first; re-activating does not.

## What is deliberately unchanged
Field names, entry order (header, lots, expenses), amounts shown exactly as entered, "not calculated" notices, demo mode rules, and the draft printed bill.

## Update 2026-10-19 (application-wide polish)
- **Page and surfaces:** soft blue-gray page (`--page` #eaeff6), white cards (`--surface`), subtle shadow.
- **Navigation:** deep-blue bar (`--nav` #0e2a52) with white brand, light-blue links, a pale active link, and a white **+ New bill** button that is visible on every page. The bills list also has a large blue **+ New bill** button.
- **Tables:** `.data-table` is compact (about 40 px rows), zebra striped, with a sticky header and pale-blue hover; `.table-scroll` scrolls inside the card.
- **Masters:** tab bar (current tab white with a blue top edge), same compact table.
- **Bill view:** key/value summary, lots and expenses tables, and a **History** card (audit events) plus the read-only explanation.
- **Filters:** one compact bar with a Clear filters button and a record count.
- Hindi layout is tested for horizontal overflow at 1280, 1024 and 800 px.

## Update 2026-10-20 (Mandi Book navigation)
- Product name in the UI is **Mandi Book**. Navigation is a two-row bar: brand, **+ New bill** and language on top; grouped dropdown menu below (Menu button on small screens); breadcrumbs above each page. Source of truth: `src/lib/navigation.ts`.
- Features that do not exist are shown as disabled labels with "Not available yet" and have no route. Never turn one into a link before its workflow exists.
- New styles use existing tokens only (no new colours).
