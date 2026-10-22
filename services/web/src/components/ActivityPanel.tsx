import { useEffect, useState } from "react";
import { ApiError } from "../api/backend";
import type { AccountView, OpenOrder, OrderHistoryRow, TradeHistoryRow } from "../api/types";
import { useWorkspace } from "../context";
import { fmtDateTime, fmtMoney, fmtPrice, fmtQty, fmtTime } from "../lib/format";
import { Panel } from "./Panel";

type Tab = "orders" | "executions" | "orderHistory" | "tradeHistory";
const TABS: [Tab, string][] = [
  ["orders", "Live Orders"],
  ["executions", "Executions"],
  ["orderHistory", "Order History"],
  ["tradeHistory", "Trade History"],
];

export function ActivityPanel({ account }: { account: AccountView | null }) {
  const [tab, setTab] = useState<Tab>("orders");
  const open = account?.openOrders ?? [];
  return (
    <Panel
      className="activity"
      testId="activity"
      title={
        <div className="tabs" role="tablist">
          {TABS.map(([id, label]) => (
            <button key={id} role="tab" aria-selected={tab === id} onClick={() => setTab(id)}>
              {label}
              {id === "orders" && open.length > 0 && <span className="count">{open.length}</span>}
            </button>
          ))}
        </div>
      }
    >
      {tab === "orders" && <LiveOrders orders={open} />}
      {tab === "executions" && <Trades account={account} sessionOnly />}
      {tab === "orderHistory" && <OrderHistory account={account} />}
      {tab === "tradeHistory" && <Trades account={account} />}
    </Panel>
  );
}

function LiveOrders({ orders }: { orders: OpenOrder[] }) {
  if (!orders.length) return <div className="empty">No working orders</div>;
  return (
    <table className="grid compact" data-testid="live-orders">
      <thead>
        <tr>
          <th className="l">Order</th>
          <th className="l">Symbol</th>
          <th className="l">Action</th>
          <th className="l">Type</th>
          <th className="l">TIF</th>
          <th>Lmt Price</th>
          <th>Remaining</th>
          <th>Filled</th>
          <th>Reserved</th>
          <th className="l">Time</th>
          <th />
        </tr>
      </thead>
      <tbody>
        {orders.map((o) => (
          <LiveOrderRow key={o.id} order={o} />
        ))}
      </tbody>
    </table>
  );
}

function LiveOrderRow({ order: o }: { order: OpenOrder }) {
  const ws = useWorkspace();
  const [editing, setEditing] = useState(false);
  const [price, setPrice] = useState(o.price?.toFixed(2) ?? "");
  const [qty, setQty] = useState(String(o.qty));
  const [busy, setBusy] = useState(false);

  const run = async (label: string, fn: () => Promise<{ orderId: number; status: string }>) => {
    setBusy(true);
    try {
      const r = await fn();
      ws.notify("success", `#${r.orderId} ${label}: ${r.status.replace("_", " ")}`);
      setEditing(false);
    } catch (err) {
      ws.notify("error", `#${o.id} ${label} failed — ${err instanceof ApiError ? err.message : String(err)}`);
    } finally {
      setBusy(false);
    }
  };
  const amend = () => {
    const change: { price?: number; qty?: number } = {};
    if (Number(price) !== o.price) change.price = Number(price);
    if (Number(qty) !== o.qty) change.qty = Number(qty);
    if (!Object.keys(change).length) return setEditing(false);
    void run("modify", () => ws.backend.amendOrder(o.id, change));
  };

  return (
    <tr className={o.side === "BUY" ? "row-buy" : "row-sell"} data-testid={`order-${o.id}`}>
      <td className="l dim">#{o.id}</td>
      <td className="l sym">{o.symbol}</td>
      <td className={`l ${o.side === "BUY" ? "bid" : "ask"}`}>{o.side}</td>
      <td className="l">{o.type === "LIMIT" ? "LMT" : "MKT"}</td>
      <td className="l">{o.timeInForce}</td>
      <td className="num">
        {editing ? <input className="cell-input" aria-label="New price" value={price} onChange={(e) => setPrice(e.target.value)} /> : fmtPrice(o.price)}
      </td>
      <td className="num">
        {editing ? <input className="cell-input" aria-label="New quantity" value={qty} onChange={(e) => setQty(e.target.value)} /> : fmtQty(o.qty)}
      </td>
      <td className="num">{fmtQty(o.filledQty)}</td>
      <td className="num dim">{o.reservedCash ? fmtMoney(o.reservedCash) : ""}</td>
      <td className="l dim">{fmtTime(o.createdAt)}</td>
      <td className="actions">
        {editing ? (
          <>
            <button className="btn-xs primary" disabled={busy} onClick={amend}>
              Transmit
            </button>
            <button className="btn-xs" onClick={() => setEditing(false)}>
              ✕
            </button>
          </>
        ) : (
          <>
            <button className="btn-xs" disabled={busy} onClick={() => setEditing(true)}>
              Modify
            </button>
            <button className="btn-xs danger" disabled={busy} onClick={() => void run("cancel", () => ws.backend.cancelOrder(o.id))}>
              Cancel
            </button>
          </>
        )}
      </td>
    </tr>
  );
}

/** Re-queries the history tables whenever the account changes (fills, cancels), at most every 1.5s. */
function useHistory<T>(load: () => Promise<T[]>, account: AccountView | null): { rows: T[] | null; error: string | null } {
  const [rows, setRows] = useState<T[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const sig = account ? `${account.openOrders.map((o) => `${o.id}:${o.qty}`).join(",")}|${account.portfolio.cash}` : "";
  useEffect(() => {
    const t = setTimeout(() => setTick((n) => n + 1), rows ? 1500 : 0);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sig]);
  useEffect(() => {
    let live = true;
    load().then(
      (r) => live && (setRows(r), setError(null)),
      (e) => live && setError(String(e?.message ?? e)),
    );
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tick]);
  return { rows, error };
}

function OrderHistory({ account }: { account: AccountView | null }) {
  const ws = useWorkspace();
  const { rows, error } = useHistory<OrderHistoryRow>(() => ws.backend.orderHistory({ limit: 200 }), account);
  if (error) return <div className="empty">History unavailable: {error}</div>;
  if (!rows) return <div className="empty">Loading…</div>;
  if (!rows.length) return <div className="empty">No orders yet</div>;
  return (
    <table className="grid compact" data-testid="order-history">
      <thead>
        <tr>
          <th className="l">Time</th>
          <th className="l">Order</th>
          <th className="l">Symbol</th>
          <th className="l">Action</th>
          <th className="l">Type</th>
          <th className="l">TIF</th>
          <th>Lmt</th>
          <th>Qty</th>
          <th>Filled</th>
          <th>Avg Px</th>
          <th className="l">Status</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.orderId}>
            <td className="l dim">{fmtDateTime(r.createdAt)}</td>
            <td className="l dim">#{r.orderId}</td>
            <td className="l sym">{r.symbol}</td>
            <td className={`l ${r.side === "BUY" ? "bid" : "ask"}`}>{r.side}</td>
            <td className="l">{r.type === "LIMIT" ? "LMT" : "MKT"}</td>
            <td className="l">{r.timeInForce}</td>
            <td className="num">{fmtPrice(r.price)}</td>
            <td className="num">{fmtQty(r.qty)}</td>
            <td className="num">{fmtQty(r.filledQty)}</td>
            <td className="num">{fmtPrice(r.avgFillPrice)}</td>
            <td className={`l status-${r.status.toLowerCase()}`} title={r.reason}>
              {r.status.replace("_", " ")}
              {r.reason && <span className="dim"> · {r.reason}</span>}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Trades({ account, sessionOnly }: { account: AccountView | null; sessionOnly?: boolean }) {
  const ws = useWorkspace();
  const [since] = useState(() => Date.now() - 1000);
  const { rows, error } = useHistory<TradeHistoryRow>(() => ws.backend.tradeHistory({ limit: 200 }), account);
  if (error) return <div className="empty">History unavailable: {error}</div>;
  if (!rows) return <div className="empty">Loading…</div>;
  const shown = sessionOnly ? rows.filter((r) => r.time >= since) : rows;
  if (!shown.length) return <div className="empty">{sessionOnly ? "No executions this session" : "No trades yet"}</div>;
  return (
    <table className="grid compact" data-testid={sessionOnly ? "executions" : "trade-history"}>
      <thead>
        <tr>
          <th className="l">Time</th>
          <th className="l">Exec ID</th>
          <th className="l">Order</th>
          <th className="l">Symbol</th>
          <th className="l">Action</th>
          <th>Qty</th>
          <th>Price</th>
          <th>Proceeds</th>
          <th className="l">Liquidity</th>
        </tr>
      </thead>
      <tbody>
        {shown.map((r) => (
          <tr key={r.tradeId}>
            <td className="l dim">{sessionOnly ? fmtTime(r.time) : fmtDateTime(r.time)}</td>
            <td className="l dim">{r.tradeId}</td>
            <td className="l dim">#{r.orderId}</td>
            <td className="l sym">{r.symbol}</td>
            <td className={`l ${r.side === "BUY" ? "bid" : "ask"}`}>{r.side === "BUY" ? "BOT" : "SLD"}</td>
            <td className="num">{fmtQty(r.qty)}</td>
            <td className="num">{fmtPrice(r.price)}</td>
            <td className="num">{fmtMoney((r.side === "BUY" ? -1 : 1) * r.price * r.qty)}</td>
            <td className="l dim">{r.liquidity === "MAKER" ? "Added" : "Removed"}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
