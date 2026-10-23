import { useEffect, useState } from "react";
import type { AccountView } from "../api/types";
import { useWorkspace } from "../context";
import { fmtMoney, fmtSigned, pnlClass } from "../lib/format";

export type View = "trade" | "portfolio";

const NAV: { id: View | null; label: string }[] = [
  { id: "trade", label: "Trade" },
  { id: "portfolio", label: "Portfolio" },
  { id: null, label: "Research" },
  { id: null, label: "Reports" },
];

export function TopBar({
  account,
  view,
  onView,
  onLogout,
}: {
  account: AccountView | null;
  view: View;
  onView: (v: View) => void;
  onLogout: () => void;
}) {
  const { session, backend } = useWorkspace();
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(t);
  }, []);
  const p = account?.portfolio;
  return (
    <header className="topbar">
      <div className="brand">
        <span className="brand-mark" aria-hidden>
          ▲
        </span>
        <span>
          <b>DEVIN</b>DEMO
        </span>
        <span className="brand-product">Trader Workstation</span>
      </div>
      <nav className="topnav" aria-label="Main" role="tablist">
        {NAV.map(({ id, label }) =>
          id ? (
            <button
              key={label}
              role="tab"
              aria-selected={view === id}
              className={view === id ? "active" : ""}
              onClick={() => onView(id)}
            >
              {label}
            </button>
          ) : (
            <button key={label} role="tab" aria-selected={false} disabled title="Coming soon">
              {label}
            </button>
          ),
        )}
      </nav>
      <div className="topbar-metrics">
        <Metric label="Net Liq" value={fmtMoney(p?.equity)} />
        <Metric label="Avail Funds" value={fmtMoney(p?.availableCash)} />
        <Metric label="Daily P&L" value={fmtSigned(p?.sessionPnl)} cls={pnlClass(p?.sessionPnl)} />
      </div>
      <div className="topbar-right">
        {backend.kind === "mock" ? (
          <span className="badge badge-demo" title="Static demo: in-browser simulated market and account">
            DEMO DATA
          </span>
        ) : (
          <span className="badge badge-live">LIVE</span>
        )}
        <span className="account-id" data-testid="account-id">
          {session.accountId || `P${session.participantId}`} · {session.username}
        </span>
        <span className="clock">{now.toLocaleTimeString("en-US", { hour12: false })}</span>
        <button className="btn-ghost" onClick={onLogout}>
          Log Out
        </button>
      </div>
    </header>
  );
}

function Metric({ label, value, cls }: { label: string; value: string; cls?: string }) {
  return (
    <div className="metric">
      <span className="metric-label">{label}</span>
      <span className={`metric-value ${cls ?? ""}`}>{value}</span>
    </div>
  );
}
