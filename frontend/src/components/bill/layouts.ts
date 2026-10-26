/**
 * The layouts of the bill entry that can be compared (a TEMPORARY choice while the design is being decided: the menu lists them under
 * "Layouts" and the bill page has a small switcher). Every layout uses the same bill logic (useBillDesk), so switching keeps what was
 * typed. The names are plain English on purpose: they are working names, not product text.
 */
export interface LayoutInfo {
  id: number;
  name: string;
  note: string;
}

export const LAYOUTS: LayoutInfo[] = [
  { id: 1, name: "Classic desk", note: "Details strip, lots grid with the parties of the selected lot below, expenses on the right, totals bar." },
  { id: 2, name: "Lot cards", note: "Same frame, but every lot is a card with its own parties inside: all lots visible at once." },
  { id: 3, name: "Guided steps", note: "One step at a time: details, lots and parties, expenses, review. Large controls, Back and Next." },
  { id: 4, name: "Sheet", note: "A dense spreadsheet: lot rows with their party rows under them, borderless cells, grid lines." },
  { id: 5, name: "Split view", note: "Lot list on the left, the selected lot with party cards in the middle, expense tiles and the bill details on the right." },
  { id: 6, name: "Live bill", note: "Details and lots on the left, and the bill as it will look, updating while you type, on the right." },
];

export const DEFAULT_LAYOUT = 1;

export function layoutById(raw: string | null | undefined): LayoutInfo {
  const n = Number(raw);
  return LAYOUTS.find((l) => l.id === n) ?? LAYOUTS[0];
}
