import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { HttpBackend } from "../src/api/http";
import { quietMock } from "./helpers";

describe("login", () => {
  it("logs in with the demo account and opens the workstation", async () => {
    const user = userEvent.setup();
    render(<App backend={quietMock()} />);
    expect(screen.getByLabelText("Username")).toHaveValue("demo");
    await user.click(screen.getByRole("button", { name: "Log In" }));
    expect(await screen.findByTestId("watchlist")).toBeInTheDocument();
    expect(screen.getByTestId("account-id")).toHaveTextContent("demo");
    for (const s of ["ACME", "GLOBX", "INITD", "NVTA"]) expect(screen.getByTestId(`watch-${s}`)).toBeInTheDocument();
  });

  it("shows the server's error on bad credentials and stays on the login screen", async () => {
    const user = userEvent.setup();
    render(<App backend={quietMock()} />);
    await user.clear(screen.getByLabelText("Password"));
    await user.type(screen.getByLabelText("Password"), "wrong-password");
    await user.click(screen.getByRole("button", { name: "Log In" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Invalid username or password");
    expect(screen.queryByTestId("watchlist")).not.toBeInTheDocument();
  });

  it("signs up a new account with an empty portfolio", async () => {
    const user = userEvent.setup();
    render(<App backend={quietMock()} />);
    await user.click(screen.getByRole("tab", { name: "Open Account" }));
    await user.clear(screen.getByLabelText("Username"));
    await user.type(screen.getByLabelText("Username"), "alice");
    await user.clear(screen.getByLabelText("Password"));
    await user.type(screen.getByLabelText("Password"), "s3cret-pass");
    await user.click(screen.getByRole("button", { name: "Create Account" }));
    expect(await screen.findByTestId("account-id")).toHaveTextContent("alice");
    expect(await screen.findByText("No positions")).toBeInTheDocument();
    expect(screen.getByTestId("pf-cash")).toHaveTextContent("100,000.00");
  });

  it("posts credentials to the gateway and sends the JWT on later calls", async () => {
    const fetchImpl = vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith("/api/auth/login")) {
        expect(JSON.parse(String(init?.body))).toEqual({ username: "bob", password: "pw" });
        return new Response(
          JSON.stringify({
            token: "jwt-123",
            tokenType: "Bearer",
            user: { id: 42, username: "bob", kind: "human" },
            account: { id: 42, participantId: 1042, label: "bob", startingCash: "10000.00" },
          }),
        );
      }
      expect((init?.headers as Record<string, string>).Authorization).toBe("Bearer jwt-123");
      return new Response(JSON.stringify([{ symbol: "ACME", name: "Acme Corp", tickSize: "0.01", lotSize: 1 }]));
    });
    const b = new HttpBackend({ fetchImpl: fetchImpl as unknown as typeof fetch, storage: sessionStorage });
    const s = await b.login("bob", "pw");
    expect(s).toEqual({ token: "jwt-123", username: "bob", participantId: 1042, accountId: "42" });
    expect(await b.instruments()).toEqual([{ symbol: "ACME", name: "Acme Corp", tickSize: 0.01, lotSize: 1 }]);
    expect(new HttpBackend({ fetchImpl: fetchImpl as unknown as typeof fetch }).restoreSession()?.token).toBe("jwt-123");
  });

  it("maps a 401 from the gateway to a login error", async () => {
    const fetchImpl = async () => new Response(JSON.stringify({ error: "invalid_credentials", message: "Invalid username or password" }), { status: 401 });
    const b = new HttpBackend({ fetchImpl: fetchImpl as unknown as typeof fetch });
    await expect(b.login("x", "y")).rejects.toMatchObject({ status: 401, message: "Invalid username or password" });
    await waitFor(() => expect(b.restoreSession()).toBeNull());
  });
});
