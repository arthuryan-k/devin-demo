import { expect, test } from "@playwright/test";

test("static Pages build loads standalone with fixture data and trades", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  const external: string[] = [];
  page.on("request", (r) => {
    const u = new URL(r.url());
    if (u.hostname !== "localhost") external.push(r.url());
  });

  await page.goto("./");
  await expect(page.getByText("DEMO DATA")).toHaveCount(0); // login screen first
  await page.getByRole("button", { name: "Log In" }).click();

  await expect(page.getByText("DEMO DATA")).toBeVisible();
  for (const s of ["ACME", "GLOBX", "INITD", "NVTA"]) await expect(page.getByTestId(`watch-${s}`)).toBeVisible();
  await expect(page.getByTestId("ask-level").first()).toBeVisible();
  await expect(page.getByTestId("bid-level").first()).toBeVisible();
  await expect(page.locator(".chart-host canvas").first()).toBeVisible();
  await expect(page.getByTestId("time-and-sales").getByRole("row").nth(3)).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId("pos-ACME-qty")).toHaveText("200");

  await page.getByTestId("watch-NVTA").click();
  await expect(page.locator(".qh-sym")).toHaveText("NVTA");
  await page.getByLabel("Quantity").fill("10");
  await page.getByLabel("Time in force").selectOption("IOC");
  const ask = await page.getByTestId("ask-level").last().locator(".price").innerText();
  await page.getByLabel("Limit price").fill((Number(ask) + 0.05).toFixed(2));
  await page.getByRole("button", { name: "Buy NVTA" }).click();
  await expect(page.getByTestId("pos-NVTA-qty")).not.toHaveText("25", { timeout: 5_000 });

  await page.getByRole("tab", { name: "Order History" }).click();
  await expect(page.getByTestId("order-history")).toContainText("NVTA");

  await page.screenshot({ path: "test-results/pages-mock.png", fullPage: true });
  expect(errors).toEqual([]);
  expect(external).toEqual([]);
});
