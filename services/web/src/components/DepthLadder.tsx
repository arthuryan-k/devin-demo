import { useMemo } from "react";
import type { OpenOrder } from "../api/types";
import { useWorkspace } from "../context";
import { useMarket } from "../hooks/useMarket";
import { fmtPrice, fmtQty } from "../lib/format";
import { Panel } from "./Panel";

const ROWS = 12;

/** Level II depth: asks above (best at the bottom), bids below, with cumulative-size bars and own-order markers. */
export function DepthLadder({
  symbol,
  openOrders,
  onPickPrice,
}: {
  symbol: string;
  openOrders: OpenOrder[];
  onPickPrice: (price: number, side: "BUY" | "SELL") => void;
}) {
  const { store } = useWorkspace();
  const m = useMarket(store, symbol);
  const asks = m.asks.slice(0, ROWS);
  const bids = m.bids.slice(0, ROWS);
  const mine = useMemo(() => {
    const map = new Map<string, number>();
    for (const o of openOrders) {
      if (o.symbol !== symbol || o.price == null) continue;
      const k = `${o.side === "BUY" ? "B" : "S"}:${o.price.toFixed(2)}`;
      map.set(k, (map.get(k) ?? 0) + o.qty);
    }
    return map;
  }, [openOrders, symbol]);
  const cum = (levels: typeof asks) => {
    let c = 0;
    return levels.map((l) => (c += l.qty));
  };
  const askCum = cum(asks);
  const bidCum = cum(bids);
  const max = Math.max(1, askCum[askCum.length - 1] ?? 0, bidCum[bidCum.length - 1] ?? 0);
  const spread = m.bestAsk != null && m.bestBid != null ? m.bestAsk - m.bestBid : null;
  const mid = m.bestAsk != null && m.bestBid != null ? (m.bestAsk + m.bestBid) / 2 : null;

  return (
    <Panel title={`Book Depth · ${symbol}`} className="depth" testId="order-book" actions={<span className="dim">seq {m.seq < 0 ? "—" : m.seq}</span>}>
      <table className="grid ladder">
        <thead>
          <tr>
            <th>My Size</th>
            <th>Bid Size</th>
            <th className="c">Price</th>
            <th>Ask Size</th>
            <th>Orders</th>
          </tr>
        </thead>
        <tbody>
          {asks
            .map((l, i) => ({ l, c: askCum[i] }))
            .reverse()
            .map(({ l, c }) => (
              <tr key={`a${l.price}`} className="ask-row clickable" data-testid="ask-level" onClick={() => onPickPrice(l.price, "BUY")}>
                <td className="num mine-size">{fmtMine(mine.get(`S:${l.price.toFixed(2)}`))}</td>
                <td />
                <td className="c price ask">{fmtPrice(l.price)}</td>
                <td className="num bar-cell">
                  <span className="bar ask-bar" style={{ width: `${(c / max) * 100}%` }} />
                  <span className="bar-val">{fmtQty(l.qty)}</span>
                </td>
                <td className="num dim">{l.orders}</td>
              </tr>
            ))}
          <tr className="spread-row">
            <td colSpan={5}>
              {mid == null ? "Waiting for quotes…" : `Mid ${mid.toFixed(3)} · Spread ${fmtPrice(spread)} · Last ${fmtPrice(m.last)}`}
            </td>
          </tr>
          {bids.map((l, i) => (
            <tr key={`b${l.price}`} className="bid-row clickable" data-testid="bid-level" onClick={() => onPickPrice(l.price, "SELL")}>
              <td className="num mine-size">{fmtMine(mine.get(`B:${l.price.toFixed(2)}`))}</td>
              <td className="num bar-cell">
                <span className="bar bid-bar" style={{ width: `${(bidCum[i] / max) * 100}%` }} />
                <span className="bar-val">{fmtQty(l.qty)}</span>
              </td>
              <td className="c price bid">{fmtPrice(l.price)}</td>
              <td />
              <td className="num dim">{l.orders}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Panel>
  );
}

function fmtMine(q: number | undefined): string {
  return q ? fmtQty(q) : "";
}
