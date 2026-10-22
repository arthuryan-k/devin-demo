import { act, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DepthLadder } from "../src/components/DepthLadder";
import { TimeAndSales } from "../src/components/TimeAndSales";
import { ACME_DELTAS, ACME_SNAPSHOT } from "./fixtures";
import { quietMock, renderInWorkspace, withManualFeed } from "./helpers";

const rows = (testId: string) => screen.getAllByTestId(testId).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));

describe("order book from the WebSocket feed", () => {
  it("renders the snapshot, then applies deltas", () => {
    const feed = withManualFeed(quietMock());
    const { store } = renderInWorkspace(
      <>
        <DepthLadder symbol="ACME" openOrders={[]} onPickPrice={() => {}} />
        <TimeAndSales symbol="ACME" />
      </>,
      feed,
    );
    act(() => {
      feed.push("ACME", ACME_SNAPSHOT);
      store.flush();
    });
    // Asks render worst-first above the spread, bids best-first below it.
    expect(rows("ask-level").map((r) => [r[2], r[3]])).toEqual([["100.05", "800"], ["100.03", "120"], ["100.02", "200"]]);
    expect(rows("bid-level").map((r) => [r[1], r[2]])).toEqual([["300", "100.00"], ["150", "99.99"], ["500", "99.98"]]);
    expect(screen.getByText(/Spread 0.02/)).toBeInTheDocument();
    expect(screen.getByText("seq 41")).toBeInTheDocument();

    act(() => {
      ACME_DELTAS.forEach((d) => feed.push("ACME", d));
      store.flush();
    });
    expect(rows("ask-level").map((r) => r[2])).toEqual(["100.05", "100.03"]);
    expect(rows("bid-level").map((r) => [r[1], r[2]])).toEqual([["75", "100.01"], ["300", "100.00"], ["90", "99.99"], ["500", "99.98"]]);
    expect(screen.getByText("seq 43")).toBeInTheDocument();
    const tape = within(screen.getByTestId("time-and-sales")).getAllByRole("row").slice(1);
    expect(tape.map((r) => within(r).getAllByRole("cell")[1].textContent)).toEqual(["100.02", "100.01"]);
  });

  it("requests a resync on a sequence gap and recovers from the next snapshot", () => {
    const feed = withManualFeed(quietMock());
    const { store } = renderInWorkspace(<DepthLadder symbol="ACME" openOrders={[]} onPickPrice={() => {}} />, feed);
    act(() => {
      feed.push("ACME", ACME_SNAPSHOT);
      feed.push("ACME", ACME_DELTAS[1]); // seq 43 after 41: gap
      store.flush();
    });
    expect(feed.resyncs).toEqual(["ACME"]);
    act(() => {
      feed.push("ACME", { ...ACME_SNAPSHOT, seq: 60, reason: "resync", bids: [[9990, 10, 1]] });
      store.flush();
    });
    expect(rows("bid-level").map((r) => r[2])).toEqual(["99.90"]);
    expect(screen.getByText("seq 60")).toBeInTheDocument();
  });

  it("marks my resting orders on their level", () => {
    const feed = withManualFeed(quietMock());
    const { store } = renderInWorkspace(
      <DepthLadder
        symbol="ACME"
        openOrders={[{ id: 1, symbol: "ACME", side: "BUY", type: "LIMIT", timeInForce: "GTC", price: 99.99, qty: 40, filledQty: 0, reservedCash: 3999.6, createdAt: 0 }]}
        onPickPrice={() => {}}
      />,
      feed,
    );
    act(() => {
      feed.push("ACME", ACME_SNAPSHOT);
      store.flush();
    });
    expect(rows("bid-level")[1][0]).toBe("40");
  });
});
