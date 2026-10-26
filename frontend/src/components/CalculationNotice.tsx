"use client";

import type { CalculationResult } from "@/lib/types";
import { useI18n } from "@/lib/i18n";

/** Always shown next to any calculated figure. Unverified results are never presented as real amounts. */
export function CalculationNotice({ result }: { result: Pick<CalculationResult, "mode" | "noticeKey"> }) {
  const { t } = useI18n();
  if (!result.noticeKey) return null;
  const demo = result.mode === "DEMO_UNVERIFIED";
  return (
    <div className={demo ? "notice demo" : "notice"} role="note" data-testid="calc-notice" data-mode={result.mode}>
      {t(result.noticeKey)}
    </div>
  );
}
