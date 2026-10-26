"use client";

import { BillChecks, BillTotalsBar } from "../BillSidebar";
import { DeskAlerts, DeskHeader, DetailsPanel, ExpensesPanel } from "../DeskParts";
import { LotsSection } from "../LotsSection";
import type { LayoutInfo } from "../layouts";
import type { Desk } from "../useBillDesk";

/** Layout 1: the classic desk. A details strip, the lots as a grid with the parties of the selected lot below it, expenses on the right, a totals bar. */
export function LayoutClassic({ desk, layout }: { desk: Desk; layout: LayoutInfo }) {
  return (
    <>
      <DeskHeader desk={desk} layout={layout} />
      <DeskAlerts desk={desk} />
      <DetailsPanel desk={desk} variant="strip" />
      <div className="desk2">
        <div className="desk2-main">
          <LotsSection
            lots={desk.draft.lots} items={desk.items} selected={desk.selectedIndex} onSelect={desk.setSelected}
            updateLot={desk.updateLot} addLot={desk.addLot} duplicateLot={desk.duplicateLot} requestRemoveLot={desk.requestRemoveLot}
            addPurchaser={desk.addPurchaser} duplicatePurchaser={desk.duplicatePurchaser} updatePurchaser={desk.updatePurchaser} removePurchaser={desk.requestRemovePurchaser}
            buyerKnown={desk.buyerKnown} rememberBuyer={(m) => m && desk.setBuyerKnown((k) => ({ ...k, [String(m.id)]: m }))} openBuyerDialog={() => desk.setDialog("buyers")}
          />
        </div>
        <div className="desk2-side">
          <ExpensesPanel desk={desk} />
          <BillChecks problems={desk.attempted ? desk.problemTexts : []} warnings={desk.warnings} />
        </div>
      </div>
      <BillTotalsBar totals={desk.totals} problemCount={desk.problems.length} />
    </>
  );
}
