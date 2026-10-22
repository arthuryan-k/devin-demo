import type { AccountView } from "../api/types";
import { fmtMoney, fmtPrice, fmtQty, fmtSigned, pnlClass } from "../lib/format";
import { Panel } from "./Panel";

export function PortfolioPanel({ account, onSelect }: { account: AccountView | null; onSelect: (s: string) => void }) {
  const p = account?.portfolio;
  return (
    <Panel title="Portfolio" className="portfolio" testId="portfolio">
      <dl className="acct-summary">
        <Row k="Net Liquidation" v={fmtMoney(p?.equity)} strong testId="pf-equity" />
        <Row k="Total Cash" v={fmtMoney(p?.cash)} testId="pf-cash" />
        <Row k="Reserved (open orders)" v={fmtMoney(p?.reservedCash)} testId="pf-reserved" />
        <Row k="Available Funds" v={fmtMoney(p?.availableCash)} testId="pf-available" />
        <Row k="Session P&L" v={fmtSigned(p?.sessionPnl)} cls={pnlClass(p?.sessionPnl)} strong testId="pf-session-pnl" />
        <Row k="Realized P&L" v={fmtSigned(p?.realizedPnl)} cls={pnlClass(p?.realizedPnl)} testId="pf-realized" />
        <Row k="Unrealized P&L" v={fmtSigned(p?.unrealizedPnl)} cls={pnlClass(p?.unrealizedPnl)} testId="pf-unrealized" />
      </dl>
      <table className="grid compact positions" data-testid="positions">
        <thead>
          <tr>
            <th className="l">Pos</th>
            <th>Qty</th>
            <th>Rsvd</th>
            <th>Avg</th>
            <th>Last</th>
            <th>Mkt Val</th>
            <th>Unrl P&L</th>
            <th>Rlzd</th>
          </tr>
        </thead>
        <tbody>
          {!p ? (
            <tr>
              <td colSpan={8} className="empty">
                Loading…
              </td>
            </tr>
          ) : p.positions.length === 0 ? (
            <tr>
              <td colSpan={8} className="empty">
                No positions
              </td>
            </tr>
          ) : (
            p.positions.map((x) => (
              <tr key={x.symbol} className="clickable" onClick={() => onSelect(x.symbol)} data-testid={`pos-${x.symbol}`}>
                <td className="l sym">{x.symbol}</td>
                <td className="num" data-testid={`pos-${x.symbol}-qty`}>
                  {fmtQty(x.qty)}
                </td>
                <td className="num dim">{x.reservedQty ? fmtQty(x.reservedQty) : ""}</td>
                <td className="num">{fmtPrice(x.avgCost)}</td>
                <td className="num">{fmtPrice(x.markPrice)}</td>
                <td className="num">{fmtMoney(x.marketValue)}</td>
                <td className={`num ${pnlClass(x.unrealizedPnl)}`}>{fmtSigned(x.unrealizedPnl)}</td>
                <td className={`num ${pnlClass(x.realizedPnl)}`}>{fmtSigned(x.realizedPnl)}</td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </Panel>
  );
}

function Row({ k, v, cls, strong, testId }: { k: string; v: string; cls?: string; strong?: boolean; testId?: string }) {
  return (
    <>
      <dt className={strong ? "strong" : ""}>{k}</dt>
      <dd className={`${cls ?? ""} ${strong ? "strong" : ""}`} data-testid={testId}>
        {v}
      </dd>
    </>
  );
}
