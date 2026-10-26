package com.mandi.billing.aadat;

/**
 * The label an operator puts on a bill (credit/balance or cash). A label only: it has NO accounting effect, because the
 * meaning of a cash bill is not confirmed (docs/business-rules.md).
 */
public enum BillType {
    CREDIT,
    CASH
}
