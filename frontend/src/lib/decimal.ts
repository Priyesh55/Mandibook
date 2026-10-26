// Exact decimal arithmetic for the amounts shown while a bill is being typed. JavaScript numbers would show 0.1 + 0.2 as
// 0.30000000000000004; here every value is a whole number of millionths (the database scale), so sums are exact. The last section
// holds the owner-confirmed bill arithmetic (weight x rate, commission, per-piece expenses, rounding). The server calculates and
// stores the real figures with the same rules; the browser test compares both.

const SCALE = 6;
const ZERO = BigInt(0);
const FACTOR = BigInt(10) ** BigInt(SCALE);

/** "12.5" -> 12500000n. Blank, non-numeric or negative text gives null. */
export function parseScaled(text: string | number | null | undefined): bigint | null {
  if (text === null || text === undefined) return null;
  const s = String(text).trim();
  if (!/^\d+(\.\d{1,6})?$/.test(s)) return null;
  const [whole, frac = ""] = s.split(".");
  return BigInt(whole) * FACTOR + BigInt(frac.padEnd(SCALE, "0"));
}

export function sumScaled(values: (bigint | null)[]): bigint {
  return values.reduce<bigint>((acc, v) => acc + (v ?? ZERO), ZERO);
}

/** 12500000n -> "12.5"; 1355500000n -> "1355.5"; trailing zeros removed, sign kept. */
export function formatScaled(v: bigint): string {
  const negative = v < ZERO;
  const abs = negative ? -v : v;
  const whole = abs / FACTOR;
  const frac = (abs % FACTOR).toString().padStart(SCALE, "0").replace(/0+$/, "");
  return `${negative ? "-" : ""}${whole}${frac ? "." + frac : ""}`;
}

// ---- the owner-confirmed arithmetic (2026-10-25). The server is authoritative and stores its own result; this mirrors it so the
// operator sees the same figures while typing. Rounding is half-up, away from zero on a tie, exactly like the server's BigDecimal.

const TEN = BigInt(10);
const TWELVE_DIGITS = TEN ** BigInt(2 * SCALE);

/** n / d rounded half-up (ties away from zero). */
function divRound(n: bigint, d: bigint): bigint {
  const negative = n < ZERO;
  const abs = negative ? -n : n;
  const q = (abs + d / BigInt(2)) / d;
  return negative ? -q : q;
}

/** a x b (both scaled) rounded to `decimals` places and returned scaled: 333 kg x 19.37 to 0 places is 6450. */
export function mulRound(a: bigint, b: bigint, decimals: number): bigint {
  const unit = TWELVE_DIGITS / TEN ** BigInt(decimals);
  return divRound(a * b, unit) * TEN ** BigInt(SCALE - decimals);
}

/** `percent` percent of v, rounded to `decimals` places. */
export function percentRound(v: bigint, percent: number, decimals: number): bigint {
  const unit = TEN ** BigInt(SCALE - decimals) * BigInt(100);
  return divRound(v * BigInt(percent), unit) * TEN ** BigInt(SCALE - decimals);
}

/** v rounded to `decimals` places. */
export function roundTo(v: bigint, decimals: number): bigint {
  const unit = TEN ** BigInt(SCALE - decimals);
  return divRound(v, unit) * unit;
}

/** a x b / divisor rounded to `decimals` places and returned scaled: with divisor 100, a weight in kg times a rate per 100 kg. */
export function mulDivRound(a: bigint, b: bigint, divisor: number, decimals: number): bigint {
  const unit = (TWELVE_DIGITS / TEN ** BigInt(decimals)) * BigInt(divisor);
  return divRound(a * b, unit) * TEN ** BigInt(SCALE - decimals);
}
