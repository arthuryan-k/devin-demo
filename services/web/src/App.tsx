import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { Backend } from "./api/backend";
import type { Instrument, Session } from "./api/types";
import { LoginScreen } from "./components/LoginScreen";
import { Workstation } from "./components/Workstation";
import { WorkspaceContext, type Notice, type NoticeLevel, type Workspace } from "./context";
import { MarketStore } from "./marketdata/marketStore";

export function App({ backend }: { backend: Backend }) {
  const [session, setSession] = useState<Session | null>(() => backend.restoreSession());
  if (!session) return <LoginScreen backend={backend} onSession={setSession} />;
  return (
    <SignedIn
      key={session.username}
      backend={backend}
      session={session}
      onLogout={() => {
        backend.logout();
        setSession(null);
      }}
    />
  );
}

function SignedIn({ backend, session, onLogout }: { backend: Backend; session: Session; onLogout: () => void }) {
  const [instruments, setInstruments] = useState<Instrument[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notices, setNotices] = useState<Notice[]>([]);
  const nextId = useRef(1);

  useEffect(() => {
    backend.instruments().then(setInstruments, (e) => setError(String(e?.message ?? e)));
  }, [backend]);

  const store = useMemo(
    () => (instruments ? new MarketStore(backend, instruments, (l) => l === "YOU" || l === session.username) : null),
    [backend, instruments, session.username],
  );
  useEffect(() => {
    store?.start();
    return () => store?.stop();
  }, [store]);

  const notify = useCallback((level: NoticeLevel, text: string) => {
    setNotices((n) => [{ id: nextId.current++, time: Date.now(), level, text }, ...n].slice(0, 50));
  }, []);

  const ws = useMemo<Workspace | null>(
    () => (store && instruments ? { backend, store, session, instruments, notify } : null),
    [backend, store, session, instruments, notify],
  );

  if (error) return <div className="splash error">Reference data unavailable: {error}</div>;
  if (!ws) return <div className="splash">Loading instruments…</div>;
  return (
    <WorkspaceContext.Provider value={ws}>
      <Workstation notices={notices} onLogout={onLogout} />
    </WorkspaceContext.Provider>
  );
}
