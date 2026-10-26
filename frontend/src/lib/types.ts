export type Lang = "en" | "hi";

export type MasterType = "farmers" | "buyers" | "villages" | "items" | "expense-heads";

export interface Master {
  id: number;
  name: string;
  nameHi: string | null;
  active: boolean;
  /** Farmers only: the village the farmer belongs to (null for a farmer entered without one). */
  villageId?: number | null;
  villageName?: string | null;
  /** Expense heads only: a predefined rate per piece (bag). The bill multiplies its pieces by it; without one the amount is typed. */
  rateBasis?: "PER_PIECE" | null;
  rate?: number | null;
}

export interface MasterRef {
  id: number;
  name: string;
  nameHi: string | null;
}

export type CalculationMode = "NOT_CALCULATED" | "CALCULATED" | "DEMO_UNVERIFIED";

export interface LotAmount {
  lineNo: number;
  amount: number | null;
}

/** `verified` is false for every mode that exists today. */
export interface CalculationResult {
  mode: CalculationMode;
  ruleSetId: string;
  verified: boolean;
  noticeKey: string | null;
  lotAmounts: LotAmount[];
  lotSubtotal: number | null;
  enteredExpensesTotal: number | null;
}

export type BillType = "CREDIT" | "CASH";

export interface PurchaserRequest {
  buyerId: number;
  haste: string | null;
  pieces: number | null;
  weight: number | null;
  rate: number | null;
}

export interface LotRequest {
  itemId: number;
  pieces: number | null;
  weight: number | null;
  rate: number | null;
  marka: string | null;
  purchasers: PurchaserRequest[];
}

export interface ExpenseRequest {
  expenseHeadId: number;
  /** null for an expense head with a predefined rate: the server calculates it. */
  amount: number | null;
}

export interface BillRequest {
  billDate: string;
  financialYear: number | null;
  farmerId: number;
  villageId: number | null;
  vehicleNo: string | null;
  transport: string | null;
  bhadaPaid: boolean | null;
  bhadaAmount: number | null;
  billType: BillType | null;
  panjiyanNo: string | null;
  bookNo: string | null;
  receiptNo: string | null;
  entryNo: string | null;
  wsNo: string | null;
  lots: LotRequest[];
  expenses: ExpenseRequest[];
}

export interface PurchaserResponse {
  seq: number;
  buyer: MasterRef;
  haste: string | null;
  pieces: number | null;
  weight: number | null;
  rate: number | null;
  value: number | null;
  commissionApplies: boolean;
  commissionPercent: number | null;
  commissionAmount: number | null;
  enteredPurchaseValue: number | null;
}

export interface AllocationSummary {
  purchaserCount: number;
  pieces: number;
  weight: number;
  value: number;
  commission: number;
}

export interface LotResponse {
  lineNo: number;
  item: MasterRef;
  pieces: number | null;
  weight: number | null;
  rate: number | null;
  buyer: MasterRef | null;
  marka: string | null;
  lotValue: number | null;
  purchasers: PurchaserResponse[];
  allocation: AllocationSummary;
}

/** Plain sums of amounts the operator typed; never a business formula. */
export interface EnteredTotals {
  /** CALCULATED for a bill saved with the owner rules; SUM_OF_ENTERED for an older bill whose amounts were typed. */
  basis: "SUM_OF_ENTERED" | "CALCULATED";
  farmerSide: { grossOfEnteredLotValues: number; totalOfEnteredExpenses: number; netOfEnteredAmounts: number; lotsWithoutEnteredValue: number };
  buyerSide: { enteredValue: number; enteredCommission: number; enteredPurchaseValue: number; purchaserRowsWithoutEnteredValue: number };
}

export interface FirmProfile {
  name: string;
  nameHi: string | null;
  address: string | null;
  addressHi: string | null;
  phone: string | null;
  licenceNo: string | null;
  shopNo: string | null;
}

export interface BillResponse {
  id: number;
  billNo: number | null;
  billDate: string;
  financialYear: number;
  financialYearLabel: string;
  farmer: MasterRef;
  village: MasterRef | null;
  vehicleNo: string | null;
  transport: string | null;
  bhadaPaid: boolean | null;
  bhadaAmount: number | null;
  billType: BillType | null;
  panjiyanNo: string | null;
  bookNo: string | null;
  receiptNo: string | null;
  entryNo: string | null;
  wsNo: string | null;
  calculationStatus: CalculationMode;
  ruleSetId: string;
  createdAt: string;
  lots: LotResponse[];
  expenses: { seq: number; expenseHead: MasterRef; amount: number; quantity: number | null; rate: number | null }[];
  totals: EnteredTotals;
  firm: FirmProfile;
  calculation: CalculationResult;
}

export interface BillSummary {
  id: number;
  billNo: number | null;
  billDate: string;
  financialYear: number;
  farmer: MasterRef;
  village: MasterRef | null;
  vehicleNo: string | null;
  lotCount: number;
  calculationStatus: CalculationMode;
  gross: number;
  expenses: number;
  net: number;
}

export interface NextNumber {
  expectedNext: number;
  startLocked: boolean;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  page: number;
  size: number;
}

export interface AppConfig {
  demoCalculationEnabled: boolean;
}

export interface AuditEvent {
  id: number;
  entityType: string;
  entityId: number;
  action: "CREATED" | "UPDATED" | "ACTIVATED" | "DEACTIVATED";
  details: string | null;
  actor: string;
  createdAt: string;
}
