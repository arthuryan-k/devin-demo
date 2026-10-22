import "@testing-library/jest-dom/vitest";
import { afterEach, vi } from "vitest";
import { cleanup } from "@testing-library/react";

// lightweight-charts needs a real canvas; the chart is covered by the Playwright run against the static build.
vi.mock("lightweight-charts", () => {
  const series = () => ({ setData: vi.fn(), update: vi.fn(), priceScale: () => ({ applyOptions: vi.fn() }) });
  return {
    ColorType: { Solid: "solid" },
    CrosshairMode: { Normal: 0 },
    LineType: { Curved: 2 },
    createChart: () => ({
      addAreaSeries: series,
      addCandlestickSeries: series,
      addHistogramSeries: series,
      priceScale: () => ({ applyOptions: vi.fn() }),
      timeScale: () => ({ fitContent: vi.fn() }),
      remove: vi.fn(),
    }),
  };
});

afterEach(() => {
  cleanup();
  localStorage.clear();
  sessionStorage.clear();
});
