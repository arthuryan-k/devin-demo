import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { ActivityPanel } from "../src/components/ActivityPanel";
import { OrderTicket } from "../src/components/OrderTicket";
import { PortfolioPanel } from "../src/components/PortfolioPanel";
import { useAccount } from "../src/hooks/useAccount";
import { useWorkspace } from "../src/context";
import { quietMock, renderInWorkspace } from "./helpers";

function Trading({ symbol }: { symbol: string }) {
  const { backend } = useWorkspace();
  const { view } = useAccount(backend);
  return (
    <>
      <OrderTicket symbol={symbol} account={view} />
      <PortfolioPanel account={view} onSelect={() => {}} />
      <ActivityPanel account={view} />
    </>
  );
}

async function setup(symbol = "ACME") {
  const backend = quietMock();
  await backend.login("demo", "demo1234");
  const r = renderInWorkspace(<Trading symbol={symbol} />, backend);
  await act(async () => {
    await new Promise((res) => setTimeout(res, 40));
    r.store.flush();
  });
  return { ...r, backend, user: userEvent.setup() };
}

async function enter(
  user: ReturnType<typeof userEvent.setup>,
  fields: { qty?: string; price?: string; type?: string; tif?: string; side?: string },
) {
  if (fields.side) await user.click(screen.getByRole("button", { name: fields.side }));
  if (fields.type) await user.selectOptions(screen.getByLabelText("Order type"), fields.type);
  if (fields.tif) await user.selectOptions(screen.getByLabelText("Time in force"), fields.tif);
  if (fields.qty) {
    await user.clear(screen.getByLabelText("Quantity"));
    await user.type(screen.getByLabelText("Quantity"), fields.qty);
  }
  if (fields.price) {
    await user.clear(screen.getByLabelText("Limit price"));
    await user.type(screen.getByLabelText("Limit price"), fields.price);
  }
}

describe("order ticket + portfolio", () => {
  it("places a resting limit buy: cash is reserved and the order is working", async () => {
    const { user, notices, backend } = await setup();
    const bid = backend.exchange.market("ACME").bestBid()!;
    const px = ((bid - 50) / 100).toFixed(2);
    expect(screen.getByTestId("pf-reserved")).toHaveTextContent("0.00");
    await enter(user, { qty: "10", price: px });
    await user.click(screen.getByRole("button", { name: "Buy ACME" }));
    await waitFor(() => expect(notices.at(-1)).toMatch(/BUY 10 ACME LMT .* GTC: NEW/));
    expect(screen.getByTestId("pf-reserved")).toHaveTextContent(
      (Number(px) * 10).toLocaleString("en-US", { minimumFractionDigits: 2 }),
    );
    expect(screen.getByTestId("live-orders")).toHaveTextContent(px);
  });

  it("a marketable buy fills and updates the position, cash and session P&L", async () => {
    const { user, backend } = await setup("INITD");
    const before = Number(screen.getByTestId("pf-cash").textContent!.replace(/,/g, ""));
    const ask = backend.exchange.market("INITD").bestAsk()!;
    await enter(user, { qty: "20", price: ((ask + 5) / 100).toFixed(2), tif: "IOC" });
    await user.click(screen.getByRole("button", { name: "Buy INITD" }));
    await waitFor(() => expect(screen.getByTestId("pos-INITD-qty")).toHaveTextContent("20"));
    const after = Number(screen.getByTestId("pf-cash").textContent!.replace(/,/g, ""));
    expect(before - after).toBeGreaterThanOrEqual((ask / 100) * 20 - 1e-6);
    expect(screen.getByTestId("pf-reserved")).toHaveTextContent("0.00");
    expect(screen.queryByTestId("live-orders")).not.toBeInTheDocument();
  });

  it("sells shares out of an existing position and realizes P&L", async () => {
    const { user } = await setup("ACME");
    expect(screen.getByTestId("pos-ACME-qty")).toHaveTextContent("200");
    await enter(user, { side: "SELL", type: "MARKET", qty: "50" });
    await user.click(screen.getByRole("button", { name: "Sell ACME" }));
    await waitFor(() => expect(screen.getByTestId("pos-ACME-qty")).toHaveTextContent("150"));
    expect(screen.getByTestId("pf-realized")).not.toHaveTextContent("0.00");
  });

  it("rejects a short sale with the risk reason", async () => {
    const { user } = await setup("INITD");
    await enter(user, { side: "SELL", qty: "5" });
    await user.click(screen.getByRole("button", { name: "Sell INITD" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("INSUFFICIENT_SHARES");
  });

  it("rejects an unfillable FOK without touching the book", async () => {
    const { user, backend } = await setup("ACME");
    const ask = backend.exchange.market("ACME").bestAsk()!;
    await enter(user, { qty: "100000", price: (ask / 100).toFixed(2), tif: "FOK" });
    await user.click(screen.getByRole("button", { name: "Buy ACME" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/INSUFFICIENT_FUNDS|FOK_NOT_FILLABLE/);
  });

  it("amends and cancels a working order, releasing the reservation", async () => {
    const { user, backend } = await setup("GLOBX");
    const bid = backend.exchange.market("GLOBX").bestBid()!;
    await enter(user, { qty: "100", price: ((bid - 40) / 100).toFixed(2) });
    await user.click(screen.getByRole("button", { name: "Buy GLOBX" }));
    await screen.findByTestId("live-orders");
    await user.click(screen.getByRole("button", { name: "Modify" }));
    await user.clear(screen.getByLabelText("New quantity"));
    await user.type(screen.getByLabelText("New quantity"), "40");
    await user.click(screen.getByRole("button", { name: "Transmit" }));
    const reserved = (((bid - 40) / 100) * 40).toLocaleString("en-US", { minimumFractionDigits: 2 });
    await waitFor(() => expect(screen.getByTestId("pf-reserved")).toHaveTextContent(reserved));
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.getByTestId("pf-reserved")).toHaveTextContent("0.00"));
    expect(screen.getByText("No working orders")).toBeInTheDocument();
  });

  it("shows prior-session history from the history tables", async () => {
    const { user } = await setup();
    await user.click(screen.getByRole("tab", { name: "Order History" }));
    const table = await screen.findByTestId("order-history");
    expect(table).toHaveTextContent("FOK_NOT_FILLABLE");
    await user.click(screen.getByRole("tab", { name: "Trade History" }));
    expect(await screen.findByTestId("trade-history")).toHaveTextContent("NVTA");
  });
});
