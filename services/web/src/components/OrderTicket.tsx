import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError } from "../api/backend";
import type { AccountView, OrderType, Side, TimeInForce } from "../api/types";
import { instrumentOf, useWorkspace } from "../context";
import { useMarket } from "../hooks/useMarket";
import { decimalsFor, fmtMoney, fmtQty, roundToTick } from "../lib/format";
import { Panel } from "./Panel";

export interface TicketPreset {
  side: Side;
  price: number;
  nonce: number;
}

export function OrderTicket({
  symbol,
  account,
  preset,
}: {
  symbol: string;
  account: AccountView | null;
  preset?: TicketPreset;
}) {
  const ws = useWorkspace();
  const inst = instrumentOf(ws, symbol);
  const m = useMarket(ws.store, symbol);
  const [side, setSide] = useState<Side>("BUY");
  const [type, setType] = useState<OrderType>("LIMIT");
  const [tif, setTif] = useState<TimeInForce>("GTC");
  const [qty, setQty] = useState("100");
  const [price, setPrice] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const dec = decimalsFor(inst.tickSize);

  // Seed the limit price from the touch when switching symbols, and from clicked book levels.
  const seededFor = useRef<string | null>(null);
  useEffect(() => {
    setPrice("");
    setError(null);
    seededFor.current = null;
  }, [symbol]);
  useEffect(() => {
    if (seededFor.current === symbol || m.bestBid == null || m.bestAsk == null) return;
    seededFor.current = symbol;
    setPrice((side === "BUY" ? m.bestBid : m.bestAsk).toFixed(dec));
  }, [symbol, m.bestBid, m.bestAsk, side, dec]);
  useEffect(() => {
    if (!preset) return;
    setSide(preset.side);
    setType("LIMIT");
    setPrice(preset.price.toFixed(dec));
  }, [preset, dec]);

  const q = Number(qty);
  const px = type === "MARKET" ? (side === "BUY" ? m.bestAsk : m.bestBid) : Number(price);
  const notional = px && q > 0 ? px * q : null;
  const pos = account?.portfolio.positions.find((p) => p.symbol === symbol);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!Number.isInteger(q) || q <= 0 || q % inst.lotSize !== 0)
      return setError(`Quantity must be a positive multiple of ${inst.lotSize}`);
    let limit: number | undefined;
    if (type === "LIMIT") {
      limit = Number(price);
      if (!(limit > 0)) return setError("Enter a limit price");
      limit = roundToTick(limit, inst.tickSize);
    }
    setBusy(true);
    try {
      const r = await ws.backend.placeOrder({
        symbol,
        side,
        type,
        timeInForce: type === "MARKET" ? "IOC" : tif,
        price: limit,
        qty: q,
      });
      const what =
        `${side} ${fmtQty(q)} ${symbol} ${type === "MARKET" ? "MKT" : `LMT ${limit!.toFixed(dec)}`} ${type === "MARKET" ? "" : tif}`.trim();
      ws.notify(
        r.status === "REJECTED" ? "error" : "success",
        `#${r.orderId} ${what}: ${r.status.replace("_", " ")}${r.filledQty ? ` (filled ${fmtQty(r.filledQty)})` : ""}${r.reason ? ` — ${r.reason}` : ""}`,
      );
    } catch (err) {
      const msg = err instanceof ApiError ? `${err.reason}: ${err.message}` : String(err);
      setError(msg);
      ws.notify("error", `Order rejected — ${msg}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel title={`Order Entry · ${symbol}`} className="ticket" testId="order-ticket">
      <form onSubmit={submit} aria-label="Order ticket" autoComplete="off">
        <div className="side-toggle" role="group" aria-label="Side">
          <button
            type="button"
            className={`buy ${side === "BUY" ? "on" : ""}`}
            aria-pressed={side === "BUY"}
            onClick={() => setSide("BUY")}
          >
            BUY
          </button>
          <button
            type="button"
            className={`sell ${side === "SELL" ? "on" : ""}`}
            aria-pressed={side === "SELL"}
            onClick={() => setSide("SELL")}
          >
            SELL
          </button>
        </div>
        <div className="ticket-grid">
          <label>
            Order Type
            <select value={type} onChange={(e) => setType(e.target.value as OrderType)} aria-label="Order type">
              <option value="LIMIT">LMT</option>
              <option value="MARKET">MKT</option>
            </select>
          </label>
          <label>
            Time in Force
            <select
              value={type === "MARKET" ? "IOC" : tif}
              disabled={type === "MARKET"}
              onChange={(e) => setTif(e.target.value as TimeInForce)}
              aria-label="Time in force"
            >
              <option value="GTC">GTC</option>
              <option value="IOC">IOC</option>
              <option value="FOK">FOK</option>
            </select>
          </label>
          <label>
            Quantity
            <input inputMode="numeric" value={qty} onChange={(e) => setQty(e.target.value)} aria-label="Quantity" />
          </label>
          <label>
            Limit Price
            <input
              inputMode="decimal"
              value={type === "MARKET" ? "MKT" : price}
              disabled={type === "MARKET"}
              onChange={(e) => setPrice(e.target.value)}
              aria-label="Limit price"
            />
          </label>
        </div>
        <dl className="ticket-info">
          <dt>Est. {side === "BUY" ? "Cost" : "Proceeds"}</dt>
          <dd>{fmtMoney(notional)}</dd>
          <dt>Available Funds</dt>
          <dd>{fmtMoney(account?.portfolio.availableCash)}</dd>
          <dt>Position · Avail</dt>
          <dd>
            {fmtQty(pos?.qty ?? 0)} · {fmtQty(pos?.availableQty ?? 0)}
          </dd>
        </dl>
        {error && (
          <div className="form-error" role="alert">
            {error}
          </div>
        )}
        <button type="submit" className={`btn-submit ${side === "BUY" ? "buy" : "sell"}`} disabled={busy}>
          {busy ? "Transmitting…" : `${side === "BUY" ? "Buy" : "Sell"} ${symbol}`}
        </button>
      </form>
    </Panel>
  );
}
