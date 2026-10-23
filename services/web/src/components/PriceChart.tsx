import { useEffect, useRef, useState } from "react";
import {
  ColorType,
  CrosshairMode,
  LineType,
  createChart,
  type IChartApi,
  type ISeriesApi,
  type UTCTimestamp,
} from "lightweight-charts";
import type { PricePoint } from "../api/types";
import { useWorkspace } from "../context";
import { useMarket } from "../hooks/useMarket";
import { Panel } from "./Panel";

type Mode = "line" | "candles";
const BUCKET_S = 15;

interface Bar {
  time: UTCTimestamp;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

/** Aggregates prints into fixed time buckets (OHLC + volume). */
export function toBars(points: PricePoint[], bucketS = BUCKET_S): Bar[] {
  const bars: Bar[] = [];
  for (const p of points) {
    const t = (Math.floor(p.time / 1000 / bucketS) * bucketS) as UTCTimestamp;
    const b = bars[bars.length - 1];
    if (b && b.time === t) {
      b.high = Math.max(b.high, p.price);
      b.low = Math.min(b.low, p.price);
      b.close = p.price;
      b.volume += p.qty;
    } else if (!b || t > b.time) {
      bars.push({ time: t, open: p.price, high: p.price, low: p.price, close: p.price, volume: p.qty });
    }
  }
  return bars;
}

/** Time-based EMA of bar closes: sparse prints are followed closely, dense bursts are smoothed. */
export function smooth(bars: Bar[], halfLifeS = 20): { time: UTCTimestamp; value: number }[] {
  let ema: number | null = null;
  let prevT = 0;
  return bars.map((b) => {
    const a = ema == null ? 1 : 1 - Math.pow(0.5, (b.time - prevT) / halfLifeS);
    ema = ema == null ? b.close : ema + a * (b.close - ema);
    prevT = b.time;
    return { time: b.time, value: Number(ema.toFixed(4)) };
  });
}

export function PriceChart({ symbol }: { symbol: string }) {
  const { store } = useWorkspace();
  const m = useMarket(store, symbol);
  const [mode, setMode] = useState<Mode>("line");
  const host = useRef<HTMLDivElement>(null);
  const chart = useRef<IChartApi | null>(null);
  const series = useRef<{ main: ISeriesApi<"Area"> | ISeriesApi<"Candlestick">; vol: ISeriesApi<"Histogram"> } | null>(
    null,
  );
  const shown = useRef<string>("");

  useEffect(() => {
    if (!host.current) return;
    const c = createChart(host.current, {
      autoSize: true,
      layout: { background: { type: ColorType.Solid, color: "#0d1117" }, textColor: "#8b95a5", fontSize: 11 },
      grid: { vertLines: { color: "#161b23" }, horzLines: { color: "#161b23" } },
      crosshair: { mode: CrosshairMode.Normal },
      rightPriceScale: { borderColor: "#252b36" },
      timeScale: { borderColor: "#252b36", timeVisible: true, secondsVisible: false },
      localization: { priceFormatter: (p: number) => p.toFixed(2) },
    });
    const main =
      mode === "line"
        ? c.addAreaSeries({
            lineColor: "#3d9cf0",
            topColor: "rgba(61,156,240,0.28)",
            bottomColor: "rgba(61,156,240,0.02)",
            lineWidth: 2,
            lineType: LineType.Curved,
            priceLineColor: "#3d9cf0",
          })
        : c.addCandlestickSeries({
            upColor: "#1fb76e",
            downColor: "#e5484d",
            wickUpColor: "#1fb76e",
            wickDownColor: "#e5484d",
            borderVisible: false,
          });
    const vol = c.addHistogramSeries({ priceScaleId: "vol", priceFormat: { type: "volume" }, color: "#2a3342" });
    c.priceScale("vol").applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });
    main.priceScale().applyOptions({ scaleMargins: { top: 0.08, bottom: 0.22 } });
    chart.current = c;
    series.current = { main, vol };
    shown.current = "";
    return () => {
      c.remove();
      chart.current = null;
      series.current = null;
    };
  }, [mode, symbol]);

  useEffect(() => {
    const s = series.current;
    if (!s || !m.series.length) return;
    const bars = toBars(m.series);
    const key = `${symbol}:${mode}`;
    const vols = bars.map((b) => ({ time: b.time, value: b.volume, color: b.close >= b.open ? "#1d4a3a" : "#4d2328" }));
    if (shown.current !== key) {
      if (mode === "line") (s.main as ISeriesApi<"Area">).setData(smooth(bars));
      else (s.main as ISeriesApi<"Candlestick">).setData(bars);
      s.vol.setData(vols);
      chart.current?.timeScale().fitContent();
      shown.current = key;
      return;
    }
    const last = bars[bars.length - 1];
    if (mode === "line") {
      const sm = smooth(bars.slice(-60));
      (s.main as ISeriesApi<"Area">).update(sm[sm.length - 1]);
    } else (s.main as ISeriesApi<"Candlestick">).update(last);
    s.vol.update(vols[vols.length - 1]);
  }, [m.series, m.trades, mode, symbol]);

  return (
    <Panel
      title={`Chart · ${symbol} · ${BUCKET_S}s`}
      className="chart-panel"
      testId="price-chart"
      actions={
        <div className="seg">
          <button aria-pressed={mode === "line"} onClick={() => setMode("line")}>
            Line
          </button>
          <button aria-pressed={mode === "candles"} onClick={() => setMode("candles")}>
            Candles
          </button>
        </div>
      }
    >
      <div ref={host} className="chart-host" />
    </Panel>
  );
}
