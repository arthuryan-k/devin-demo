/**
 * Decimal <-> integer-tick conversion. Clients send and receive decimal strings; the engine only sees integers.
 * Conversion is exact (BigInt over the decimal digits), never floating point.
 */

export class TickError extends Error {}

/** A decimal string as an integer mantissa and a scale (number of fraction digits). */
interface Scaled {
  units: bigint;
  scale: number;
}

const DECIMAL = /^[+-]?(\d+)(?:\.(\d+))?$/;

function parse(name: string, text: string): Scaled {
  const m = DECIMAL.exec(text.trim());
  if (!m) {
    throw new TickError(`${name} must be a decimal number`);
  }
  const negative = text.trim().startsWith("-");
  const frac = m[2] ?? "";
  const units = BigInt((m[1] ?? "0") + frac) * (negative ? -1n : 1n);
  return { units, scale: frac.length };
}

function rescale(value: Scaled, scale: number): bigint {
  return value.units * 10n ** BigInt(scale - value.scale);
}

/** Price conversion for one instrument; one tick is {@link tickSize} (e.g. "0.01"). */
export class TickConverter {
  private readonly tick: Scaled;

  constructor(readonly tickSize: string) {
    this.tick = parse("tickSize", tickSize);
    if (this.tick.units <= 0n) {
      throw new TickError("tickSize must be positive");
    }
  }

  /** Decimal price -> ticks; rejects non-positive prices and prices that are not a multiple of the tick size. */
  toTicks(name: string, value: string | number | undefined | null): number {
    if (value === undefined || value === null || String(value).trim() === "") {
      throw new TickError(`${name} is required`);
    }
    const price = parse(name, String(value));
    const scale = Math.max(price.scale, this.tick.scale);
    const p = rescale(price, scale);
    const t = rescale(this.tick, scale);
    if (p % t !== 0n) {
      throw new TickError(`${name} must be a multiple of ${this.tickSize}`);
    }
    const ticks = p / t;
    if (ticks <= 0n) {
      throw new TickError(`${name} must be positive`);
    }
    if (ticks > BigInt(Number.MAX_SAFE_INTEGER)) {
      throw new TickError(`${name} is too large`);
    }
    return Number(ticks);
  }

  /** Ticks -> canonical decimal string with exactly the tick size's fraction digits. */
  toDecimal(ticks: number | bigint): string {
    const units = BigInt(ticks) * this.tick.units;
    const scale = this.tick.scale;
    const negative = units < 0n;
    const digits = (negative ? -units : units).toString().padStart(scale + 1, "0");
    const whole = digits.slice(0, digits.length - scale);
    const frac = digits.slice(digits.length - scale);
    return (negative ? "-" : "") + (scale > 0 ? `${whole}.${frac}` : whole);
  }
}

/** Positive integer quantity that is a multiple of {@code lotSize}. */
export function parseQty(name: string, value: string | number | undefined | null, lotSize = 1): number {
  if (value === undefined || value === null || String(value).trim() === "") {
    throw new TickError(`${name} is required`);
  }
  const text = String(value).trim();
  if (!/^\d+$/.test(text)) {
    throw new TickError(`${name} must be a positive integer`);
  }
  const qty = Number(text);
  if (!Number.isSafeInteger(qty) || qty <= 0) {
    throw new TickError(`${name} must be a positive integer`);
  }
  if (qty % lotSize !== 0) {
    throw new TickError(`${name} must be a multiple of the lot size ${lotSize}`);
  }
  return qty;
}
