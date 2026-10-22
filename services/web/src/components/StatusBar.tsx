import type { Notice } from "../context";
import { fmtTime } from "../lib/format";

export function StatusBar({ notices }: { notices: Notice[] }) {
  const last = notices[0];
  return (
    <footer className="statusbar" data-testid="status-bar">
      {last ? (
        <span className={`notice ${last.level}`} role="status">
          <span className="dim">{fmtTime(last.time)}</span> {last.text}
        </span>
      ) : (
        <span className="dim">Ready</span>
      )}
      <span className="dim statusbar-right">Prices delayed 0 ms · Simulated exchange · Not investment advice</span>
    </footer>
  );
}
