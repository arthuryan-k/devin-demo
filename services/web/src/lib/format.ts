export function decimalsFor(tickSize: number): number {
  const s = tickSize.toString();
  const i = s.indexOf(".");
  return i < 0 ? 0 : s.length - i - 1;
}

export function fmtPrice(v: number | null | undefined, decimals = 2): string {
  return v == null || !Number.isFinite(v) ? "—" : v.toFixed(decimals);
}

const money = new Intl.NumberFormat("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const qtyFmt = new Intl.NumberFormat("en-US", { maximumFractionDigits: 0 });

export function fmtMoney(v: number | null | undefined): string {
  return v == null || !Number.isFinite(v) ? "—" : money.format(v);
}

export function fmtSigned(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return "—";
  const s = money.format(Math.abs(v));
  return v > 0 ? `+${s}` : v < 0 ? `-${s}` : s;
}

export function fmtPct(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return "—";
  return `${v > 0 ? "+" : ""}${v.toFixed(2)}%`;
}

export function fmtQty(v: number | null | undefined): string {
  return v == null ? "—" : qtyFmt.format(v);
}

export function fmtVolume(v: number): string {
  if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(2)}M`;
  if (v >= 10_000) return `${(v / 1_000).toFixed(1)}K`;
  return qtyFmt.format(v);
}

export function fmtTime(ms: number): string {
  const d = new Date(ms);
  return d.toLocaleTimeString("en-US", { hour12: false });
}

export function fmtDateTime(ms: number): string {
  const d = new Date(ms);
  const date = d.toLocaleDateString("en-CA");
  return `${date} ${fmtTime(ms)}`;
}

export function pnlClass(v: number | null | undefined): string {
  return v == null || v === 0 ? "" : v > 0 ? "pos" : "neg";
}

/** Rounds a price to the instrument's tick grid. */
export function roundToTick(price: number, tickSize: number): number {
  const d = decimalsFor(tickSize);
  return Number((Math.round(price / tickSize) * tickSize).toFixed(d));
}

export function toTicks(price: number, tickSize: number): number {
  return Math.round(price / tickSize);
}

export function fromTicks(ticks: number, tickSize: number): number {
  return Number((ticks * tickSize).toFixed(decimalsFor(tickSize)));
}
