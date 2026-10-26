"use client";

import type { BillDraft } from "@/lib/billDraft";
import { useI18n } from "@/lib/i18n";
import type { Master } from "@/lib/types";
import { MasterPicker } from "../MasterPicker";

interface Props {
  draft: BillDraft;
  patch: (p: Partial<BillDraft>) => void;
  /** Message about the date (unreadable) or null. It never changes any other field. */
  dateProblem: string | null;
  villageKnown: Master | null;
  farmerKnown: Master | null;
  onVillage: (id: string, village: Master | null) => void;
  onFarmer: (id: string, farmer: Master | null) => void;
  /** Number of active farmers in the chosen village (undefined while unknown). */
  farmersInVillage: number | undefined;
  openDialog: (type: "villages" | "farmers") => void;
  /** How the same fields are arranged: one row (strip), a roomy two-column form, one vertical column, or a dense toolbar. */
  variant?: "strip" | "form" | "column" | "compact";
}

/**
 * The bill details as ONE compact strip, in the order of the real work: date, village, then the farmer OF THAT VILLAGE, then how the
 * goods came. Only date and farmer are required. The bill shows its date only (the financial year follows from it); every
 * reference number is optional and folded away.
 */
export function BillHeaderSection({ draft, patch, dateProblem, villageKnown, farmerKnown, onVillage, onFarmer, farmersInVillage, openDialog, variant = "strip" }: Props) {
  const { t } = useI18n();
  const noVillage = draft.villageId === "";
  return (
    <section className={`card2 strip v-${variant}`} aria-label={t("bill.section.header")} data-testid="section-header">
      <div className="strip-grid">
        <label className="field">
          <span className="field-label">{t("bill.date")}</span>
          <input
            type="date" value={draft.billDate} onChange={(e) => patch({ billDate: e.target.value })} data-testid="bill-date"
            aria-invalid={dateProblem !== null} aria-describedby={dateProblem ? "date-error" : undefined}
          />
        </label>
        <div className="field">
          <span className="field-label">
            <span>{t("bill.village")}</span>
            <button type="button" className="link-button" onClick={() => openDialog("villages")} data-testid="new-village">+ {t("bill.newVillage")}</button>
          </span>
          <MasterPicker type="villages" value={draft.villageId} known={villageKnown} onChange={onVillage} testId="bill-village" ariaLabel={t("bill.village")} />
        </div>
        <div className="field">
          <span className="field-label">
            <span>{t("bill.farmer")}</span>
            <button type="button" className="link-button" disabled={noVillage} onClick={() => openDialog("farmers")} data-testid="new-farmer">+ {t("bill.newFarmer")}</button>
          </span>
          <MasterPicker
            type="farmers" value={draft.farmerId} known={farmerKnown} onChange={onFarmer} testId="bill-farmer" ariaLabel={t("bill.farmer")}
            disabled={noVillage} query={noVillage ? "" : `villageId=${draft.villageId}`}
            placeholder={noVillage ? t("bill.villageFirst") : undefined}
          />
          {!noVillage && farmersInVillage === 0 && <span className="inline-note" data-testid="no-farmers-hint">{t("bill.noFarmersInVillage")}</span>}
        </div>
        <label className="field">
          <span className="field-label">{t("bill.vehicleNo")}</span>
          <input value={draft.vehicleNo} onChange={(e) => patch({ vehicleNo: e.target.value })} maxLength={30} data-testid="bill-vehicle" />
        </label>
        <label className="field">
          <span className="field-label">{t("bill.transport")}</span>
          <input value={draft.transport} onChange={(e) => patch({ transport: e.target.value })} maxLength={100} data-testid="bill-transport" />
        </label>
        <div className="field wide">
          <span className="field-label">{t("bill.bhadaPaid")} / {t("bill.bhadaAmount")}</span>
          <div className="joined">
            <select
              value={draft.bhadaPaid} aria-label={t("bill.bhadaPaid")}
              onChange={(e) => patch({ bhadaPaid: e.target.value as BillDraft["bhadaPaid"], ...(e.target.value === "yes" ? {} : { bhadaAmount: "" }) })}
              data-testid="bill-bhada-paid"
            >
              <option value="yes">{t("bill.yes")}</option>
              <option value="no">{t("bill.no")}</option>
            </select>
            <input
              type="number" min="0" step="any" value={draft.bhadaAmount} disabled={draft.bhadaPaid !== "yes"} aria-label={t("bill.bhadaAmount")}
              onChange={(e) => patch({ bhadaAmount: e.target.value })} data-testid="bill-bhada-amount"
            />
          </div>
        </div>
        <label className="field">
          <span className="field-label">{t("bill.billType")}</span>
          <select value={draft.billType} onChange={(e) => patch({ billType: e.target.value as BillDraft["billType"] })} data-testid="bill-type">
            <option value="CREDIT">{t("bill.billType.credit")}</option>
            <option value="CASH">{t("bill.billType.cash")}</option>
          </select>
        </label>
      </div>
      {dateProblem && <div className="error" role="alert" id="date-error" data-testid="date-error" style={{ marginTop: 10 }}>{dateProblem}</div>}
      <details className="refs" data-testid="bill-refs">
        <summary>{t("bill.refNumbers")}</summary>
        <div className="strip-grid">
          <label className="field"><span className="field-label">{t("bill.panjiyan")}</span><input value={draft.panjiyanNo} onChange={(e) => patch({ panjiyanNo: e.target.value })} maxLength={40} data-testid="bill-panjiyan" /></label>
          <label className="field"><span className="field-label">{t("bill.bookNo")}</span><input value={draft.bookNo} onChange={(e) => patch({ bookNo: e.target.value })} maxLength={30} data-testid="bill-book-no" /></label>
          <label className="field"><span className="field-label">{t("bill.receiptNo")}</span><input value={draft.receiptNo} onChange={(e) => patch({ receiptNo: e.target.value })} maxLength={30} data-testid="bill-receipt-no" /></label>
          <label className="field"><span className="field-label">{t("bill.entryNo")}</span><input value={draft.entryNo} onChange={(e) => patch({ entryNo: e.target.value })} maxLength={30} data-testid="bill-entry-no" /></label>
          <label className="field"><span className="field-label">{t("bill.wsNo")}</span><input value={draft.wsNo} onChange={(e) => patch({ wsNo: e.target.value })} maxLength={30} data-testid="bill-ws-no" /></label>
        </div>
      </details>
    </section>
  );
}
