import { instrumentOf, useWorkspace } from "../context";
import { useFlash } from "../hooks/useFlash";
import { useMarket } from "../hooks/useMarket";
import { fmtPct, fmtPrice, fmtQty, fmtSigned, fmtVolume, pnlClass } from "../lib/format";

export function QuoteHeader({ symbol }: { symbol: string }) {
  const ws = useWorkspace();
  const inst = instrumentOf(ws, symbol);
  const m = useMarket(ws.store, symbol);
  const chg = m.last != null && m.open != null ? m.last - m.open : null;
  const pct = chg != null && m.open ? (chg / m.open) * 100 : null;
  const flash = useFlash(m.last);
  const bidSize = m.bids[0]?.qty;
  const askSize = m.asks[0]?.qty;
  return (
    <div className="quote-header" data-testid="quote-header">
      <div className="qh-id">
        <span className="qh-sym">{symbol}</span>
        <span className="qh-name">{inst.name} · NASDAQ.SIM · USD</span>
      </div>
      <div className={`qh-last ${flash}`} data-testid="quote-last">
        {fmtPrice(m.last)}
      </div>
      <div className={`qh-chg ${pnlClass(chg)}`}>
        {chg == null ? "—" : fmtSigned(chg)} <span>({fmtPct(pct)})</span>
      </div>
      <div className="qh-stats">
        <Stat label="Bid" value={`${fmtPrice(m.bestBid)} × ${fmtQty(bidSize)}`} cls="bid" />
        <Stat label="Ask" value={`${fmtPrice(m.bestAsk)} × ${fmtQty(askSize)}`} cls="ask" />
        <Stat label="Spread" value={m.bestBid != null && m.bestAsk != null ? fmtPrice(m.bestAsk - m.bestBid) : "—"} />
        <Stat label="High" value={fmtPrice(m.high)} />
        <Stat label="Low" value={fmtPrice(m.low)} />
        <Stat label="Volume" value={fmtVolume(m.volume)} />
        <Stat
          label="Feed"
          value={m.status.toUpperCase()}
          cls={m.status === "live" ? "pos" : "warn"}
          testId="feed-status"
        />
      </div>
    </div>
  );
}

function Stat({ label, value, cls, testId }: { label: string; value: string; cls?: string; testId?: string }) {
  return (
    <div className="qh-stat">
      <span className="lbl">{label}</span>
      <span className={`val ${cls ?? ""}`} data-testid={testId}>
        {value}
      </span>
    </div>
  );
}
