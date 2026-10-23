import { useWorkspace } from "../context";
import { useMarket } from "../hooks/useMarket";
import { fmtPrice, fmtQty, fmtTime } from "../lib/format";
import { Panel } from "./Panel";

export function TimeAndSales({ symbol }: { symbol: string }) {
  const { store } = useWorkspace();
  const m = useMarket(store, symbol);
  return (
    <Panel title={`Time & Sales · ${symbol}`} className="tape" testId="time-and-sales">
      {m.trades.length === 0 ? (
        <div className="empty">No prints yet</div>
      ) : (
        <table className="grid compact">
          <thead>
            <tr>
              <th className="l">Time</th>
              <th>Price</th>
              <th>Size</th>
              <th>Side</th>
            </tr>
          </thead>
          <tbody>
            {m.trades.map((t) => (
              <tr
                key={t.id}
                className={`${t.side === "BUY" ? "bid" : t.side === "SELL" ? "ask" : ""} ${t.mine ? "mine" : ""}`}
              >
                <td className="l dim">{fmtTime(t.time)}</td>
                <td className="num">{fmtPrice(t.price)}</td>
                <td className="num">{fmtQty(t.qty)}</td>
                <td className="num">{t.side === "BUY" ? "↑ BUY" : t.side === "SELL" ? "↓ SELL" : ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  );
}
