"use client";

import { DeskDialogs } from "./bill/DeskParts";
import { LotCardsLayout } from "./bill/LotCardsLayout";
import { useBillDesk } from "./bill/useBillDesk";

/** The new-bill page. The bill logic is ONE hook (useBillDesk); the layout only decides how it is laid out. */
export function BillForm() {
  const desk = useBillDesk();
  return (
    <>
      <form onSubmit={desk.onSave} noValidate data-testid="bill-form" className="desk-form">
        <LotCardsLayout desk={desk} />
      </form>
      <DeskDialogs desk={desk} />
    </>
  );
}
