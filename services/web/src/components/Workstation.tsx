import { useCallback, useState } from "react";
import { useWorkspace } from "../context";
import { useAccount } from "../hooks/useAccount";
import { ActivityPanel } from "./ActivityPanel";
import { DepthLadder } from "./DepthLadder";
import { OrderTicket, type TicketPreset } from "./OrderTicket";
import { PortfolioPanel } from "./PortfolioPanel";
import { PriceChart } from "./PriceChart";
import { QuoteHeader } from "./QuoteHeader";
import { TimeAndSales } from "./TimeAndSales";
import { TopBar, type View } from "./TopBar";
import { Watchlist } from "./Watchlist";
import { StatusBar } from "./StatusBar";
import type { Notice } from "../context";

export function Workstation({ notices, onLogout }: { notices: Notice[]; onLogout: () => void }) {
  const ws = useWorkspace();
  const [symbol, setSymbol] = useState(ws.instruments[0]?.symbol ?? "");
  const [preset, setPreset] = useState<TicketPreset>();
  const [view, setView] = useState<View>("trade");
  const { view: account, error } = useAccount(ws.backend);
  const pick = useCallback((price: number, side: "BUY" | "SELL") => setPreset({ price, side, nonce: Date.now() }), []);

  return (
    <div className="workstation">
      <TopBar account={account} view={view} onView={setView} onLogout={onLogout} />
      {error && <div className="banner-error">Account service unavailable: {error.message}</div>}
      {view === "portfolio" ? (
        <main className="layout-portfolio" data-testid="portfolio-page">
          <PortfolioPanel
            account={account}
            onSelect={(s) => {
              setSymbol(s);
              setView("trade");
            }}
          />
          <ActivityPanel account={account} />
        </main>
      ) : view === "research" ? (
        <main className="layout-research" data-testid="research-page">
          <h1>Hello world!</h1>
        </main>
      ) : (
        <main className="layout">
          <div className="col-left">
            <Watchlist selected={symbol} onSelect={setSymbol} />
            <PortfolioPanel account={account} onSelect={setSymbol} />
          </div>
          <div className="col-center">
            <QuoteHeader symbol={symbol} />
            <PriceChart symbol={symbol} />
            <ActivityPanel account={account} />
          </div>
          <div className="col-book">
            <DepthLadder symbol={symbol} openOrders={account?.openOrders ?? []} onPickPrice={pick} />
          </div>
          <div className="col-right">
            <OrderTicket symbol={symbol} account={account} preset={preset} />
            <TimeAndSales symbol={symbol} />
          </div>
        </main>
      )}
      <StatusBar notices={notices} />
    </div>
  );
}
