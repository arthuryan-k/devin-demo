import { act, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PortfolioPanel } from "../src/components/PortfolioPanel";
import { MockBackend } from "../src/api/mock/mockBackend";
import type { AccountView } from "../src/api/types";
import { toPortfolio } from "../src/api/http";

const view = (cash: number, acmeQty: number, mark: number): AccountView => ({
  openOrders: [],
  // the portfolio service's /v1/accounts body, integer cents
  portfolio: toPortfolio({
    cash: cash * 100,
    reservedCash: 50000,
    availableCash: (cash - 500) * 100,
    startingCash: 10000000,
    equity: (cash + acmeQty * mark + 410) * 100,
    realizedPnl: 350,
    unrealizedPnl: (acmeQty * (mark - 100) + 10) * 100,
    sessionPnl: acmeQty * (mark - 100) * 100,
    positions: [
      {
        symbol: "ACME",
        shares: acmeQty,
        reservedShares: 0,
        availableShares: acmeQty,
        costBasis: acmeQty * 10000,
        avgCost: 10000,
        markPrice: mark * 100,
        marketValue: acmeQty * mark * 100,
        realizedPnl: 0,
        unrealizedPnl: acmeQty * (mark - 100) * 100,
      },
      {
        symbol: "GLOBX",
        shares: 10,
        reservedShares: 4,
        availableShares: 6,
        costBasis: 40000,
        avgCost: 4000,
        markPrice: 4100,
        marketValue: 41000,
        realizedPnl: 350,
        unrealizedPnl: 1000,
      },
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

describe("portfolio tab", () => {
  it("switches between the trade layout and a full portfolio page", async () => {
    const { Workstation } = await import("../src/components/Workstation");
    const { renderInWorkspace, quietMock } = await import("./helpers");
    const { fireEvent } = await import("@testing-library/react");
    const b = quietMock();
    await b.login("demo", "demo1234");
    renderInWorkspace(<Workstation notices={[]} onLogout={() => {}} />, b);
    const tab = (name: string) => screen.getByRole("tab", { name });
    expect(tab("Trade")).toHaveAttribute("aria-selected", "true");
    expect(tab("Research")).toBeEnabled();
    expect(tab("Reports")).toBeDisabled();
    expect(screen.queryByTestId("portfolio-page")).toBeNull();
    expect(screen.queryByTestId("research-page")).toBeNull();

    fireEvent.click(tab("Research"));
    expect(tab("Research")).toHaveAttribute("aria-selected", "true");
    expect(screen.getByTestId("research-page")).toBeEmptyDOMElement();
    expect(screen.queryByTestId("portfolio-page")).toBeNull();

    fireEvent.click(tab("Portfolio"));
    expect(tab("Portfolio")).toHaveAttribute("aria-selected", "true");
    const page = screen.getByTestId("portfolio-page");
    expect(page).toContainElement(screen.getByTestId("positions"));
    expect(page).toContainElement(screen.getByTestId("activity"));
    expect(await screen.findByTestId("pos-ACME")).toBeInTheDocument();

    fireEvent.click(screen.getByTestId("pos-ACME"));
    expect(tab("Trade")).toHaveAttribute("aria-selected", "true");
    expect(screen.queryByTestId("portfolio-page")).toBeNull();
  });
});
