import { act, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PortfolioPanel } from "../src/components/PortfolioPanel";
import { MockBackend } from "../src/api/mock/mockBackend";
import type { AccountView } from "../src/api/types";
import { toPortfolio } from "../src/api/http";

const view = (cash: number, acmeQty: number, mark: number): AccountView => ({
  openOrders: [],
  portfolio: toPortfolio({
    cash, reservedCash: "500.00", startingCash: 100000, equity: cash + acmeQty * mark, sessionPnl: acmeQty * (mark - 100),
    positions: [
      { symbol: "ACME", qty: acmeQty, reservedQty: 0, avgCost: "100.00", markPrice: mark, unrealizedPnl: acmeQty * (mark - 100), realizedPnl: 0 },
      { symbol: "GLOBX", qty: 10, reservedQty: 4, avgCost: 40, markPrice: 41, unrealizedPnl: 10, realizedPnl: 3.5 },
    ],
  }),
});

describe("portfolio panel", () => {
  it("renders shared cash, per-symbol positions and P&L, and re-renders on updates", () => {
    const { rerender } = render(<PortfolioPanel account={view(90000, 100, 101)} onSelect={() => {}} />);
    expect(screen.getByTestId("pf-cash")).toHaveTextContent("90,000.00");
    expect(screen.getByTestId("pf-available")).toHaveTextContent("89,500.00");
    expect(screen.getByTestId("pf-session-pnl")).toHaveTextContent("+100.00");
    expect(screen.getByTestId("pf-realized")).toHaveTextContent("+3.50");
    expect(screen.getByTestId("pos-GLOBX")).toHaveTextContent("4");
    rerender(<PortfolioPanel account={view(80000, 200, 99)} onSelect={() => {}} />);
    expect(screen.getByTestId("pos-ACME-qty")).toHaveTextContent("200");
    expect(screen.getByTestId("pf-session-pnl")).toHaveTextContent("-200.00");
    expect(screen.getByTestId("pf-session-pnl")).toHaveClass("neg");
  });

  it("pushes account updates as the market marks positions", async () => {
    const b = new MockBackend({ tickMs: 0, latencyMs: 0, seed: 3 });
    await b.login("demo", "demo1234");
    const views: AccountView[] = [];
    b.subscribeAccount((v) => views.push(v));
    const acme = () => views.at(-1)!.portfolio.positions.find((p) => p.symbol === "ACME")!;
    expect(acme().qty).toBe(200);
    expect(views[0].portfolio.sessionPnl).toBe(0);
    act(() => {
      for (let i = 0; i < 200; i++) b.exchange.step("ACME");
      b.exchange.notifyAll();
    });
    const p = acme();
    expect(p.markPrice).not.toBe(views[0].portfolio.positions.find((x) => x.symbol === "ACME")!.markPrice);
    expect(p.unrealizedPnl).toBeCloseTo(p.qty * p.markPrice! - p.qty * p.avgCost!, 6);
  });
});
