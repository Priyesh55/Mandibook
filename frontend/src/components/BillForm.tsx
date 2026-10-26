"use client";

import { useSearchParams } from "next/navigation";
import { DeskDialogs } from "./bill/DeskParts";
import { layoutById } from "./bill/layouts";
import { LayoutClassic } from "./bill/layouts/LayoutClassic";
import { LayoutLotCards } from "./bill/layouts/LayoutLotCards";
import { LayoutPreview } from "./bill/layouts/LayoutPreview";
import { LayoutSheet } from "./bill/layouts/LayoutSheet";
import { LayoutSplit } from "./bill/layouts/LayoutSplit";
import { LayoutSteps } from "./bill/layouts/LayoutSteps";
import { useBillDesk } from "./bill/useBillDesk";

/**
 * The new-bill page. The bill logic is ONE hook (useBillDesk); a layout only decides how it is laid out. While the design is being
 * decided there are several layouts, chosen with ?layout=N from the menu or the small switcher in the header (the draft survives a
 * switch, because the page stays mounted). Without a choice the classic desk is shown.
 */
export function BillForm() {
  const desk = useBillDesk();
  const layout = layoutById(useSearchParams().get("layout"));
  const Layout = { 1: LayoutClassic, 2: LayoutLotCards, 3: LayoutSteps, 4: LayoutSheet, 5: LayoutSplit, 6: LayoutPreview }[layout.id] ?? LayoutClassic;
  return (
    <>
      <form onSubmit={desk.onSave} noValidate data-testid="bill-form" className={`desk-form lay-${layout.id}`} data-layout={layout.id}>
        <Layout desk={desk} layout={layout} />
      </form>
      <DeskDialogs desk={desk} />
    </>
  );
}
