import { useMarket } from "../hooks/useMarket";
import { useWorkspace } from "../context";
import type { Instrument } from "../api/types";
import { fmtPct, fmtPrice, fmtSigned, fmtVolume, pnlClass } from "../lib/format";
import { Panel } from "./Panel";
import { useFlash } from "../hooks/useFlash";

export function Watchlist({ selected, onSelect }: { selected: string; onSelect: (s: string) => void }) {
  const { instruments } = useWorkspace();
  return (
    <Panel title="Watchlist" className="watchlist" testId="watchlist">
      <table className="grid">
        <thead>
          <tr>
            <th className="l">Symbol</th>
            <th>Last</th>
            <th>Chg</th>
            <th>Chg %</th>
            <th>Bid</th>
            <th>Ask</th>
            <th>Volume</th>
          </tr>
        </thead>
        <tbody>
          {instruments.map((i) => (
            <WatchRow key={i.symbol} inst={i} selected={i.symbol === selected} onSelect={onSelect} />
          ))}
        </tbody>
      </table>
    </Panel>
  );
}

function WatchRow({ inst, selected, onSelect }: { inst: Instrument; selected: boolean; onSelect: (s: string) => void }) {
  const { store } = useWorkspace();
  const m = useMarket(store, inst.symbol);
  const chg = m.last != null && m.open != null ? m.last - m.open : null;
  const pct = chg != null && m.open ? (chg / m.open) * 100 : null;
  const flash = useFlash(m.last);
  return (
    <tr
      className={`clickable ${selected ? "selected" : ""}`}
      onClick={() => onSelect(inst.symbol)}
      aria-selected={selected}
      data-testid={`watch-${inst.symbol}`}
    >
      <td className="l">
        <span className="sym">{inst.symbol}</span>
        <span className="sym-name">{inst.name}</span>
      </td>
      <td className={`num ${flash}`}>{fmtPrice(m.last)}</td>
      <td className={`num ${pnlClass(chg)}`}>{chg == null ? "—" : fmtSigned(chg)}</td>
      <td className={`num ${pnlClass(chg)}`}>{fmtPct(pct)}</td>
      <td className="num bid">{fmtPrice(m.bestBid)}</td>
      <td className="num ask">{fmtPrice(m.bestAsk)}</td>
      <td className="num dim">{fmtVolume(m.volume)}</td>
    </tr>
  );
}
